/**
 * Splunk Web form login: `POST <loginUrl>` with `username=…&password=…`, then the session cookie
 * (`splunkd_8000` by default) read off the response's `Set-Cookie`.
 *
 * `SPLUNK_ENABLED` is the switch. Off (the default), login() returns a fabricated value and sends
 * nothing, so the page and the audit trail work with no Splunk to reach.
 *
 * Built on node:http(s) rather than fetch, for three reasons that each decide whether the real call
 * works at all:
 *  - **Redirects are never followed.** A successful login may answer 303 with the cookie on that
 *    response; following it would hand back the next page's headers and lose the cookie. node:http
 *    never follows; fetch does unless told otherwise.
 *  - **`Set-Cookie` must be readable.** `res.headers['set-cookie']` is always the full array.
 *  - **TLS verification can be switched off per request** (`SPLUNK_TLS_INSECURE`, on by default by
 *    request) without NODE_TLS_REJECT_UNAUTHORIZED, which would switch it off for the whole process —
 *    the BFF's proxy to the Java backend and the Postgres connection included.
 *
 * `cval` is fetched first (`SPLUNK_PREFETCH_CVAL`): Splunk Web 7+ sets a `cval` cookie on the login
 * page and rejects a POST that does not echo it — as both a cookie and a form field — answering 200
 * without the session cookie, which looks exactly like a wrong password.
 */
import http from 'node:http';
import https from 'node:https';
import { randomBytes } from 'node:crypto';
import { readFileSync } from 'node:fs';

export class SplunkLoginError extends Error {
  /**
   * @param reason     short code for the audit trail — no_session_cookie, io_error, …
   * @param httpStatus the status Splunk answered with, or 0 when it answered nothing
   */
  constructor(reason, httpStatus, message, cause) {
    super(message, { cause });
    this.reason = reason;
    this.httpStatus = httpStatus;
  }
}

/** Every `Set-Cookie` on a response, name → value. A later header wins, as a browser's would. */
export function cookiesOf(setCookie = []) {
  const cookies = new Map();
  for (const header of setCookie) {
    const pair = header.split(';', 1)[0];
    const eq = pair.indexOf('=');
    if (eq <= 0) continue;
    cookies.set(pair.slice(0, eq).trim(), pair.slice(eq + 1).trim().replace(/^"(.*)"$/, '$1'));
  }
  return cookies;
}

export function createLoginClient(config) {
  const mode = config.enabled ? 'real' : 'mock';
  // keepAlive off: every login is two requests minutes apart, and a fresh socket is what makes the
  // connect timeout below meaningful (a reused socket never emits 'connect').
  const agent = new https.Agent({
    keepAlive: false,
    rejectUnauthorized: !config.tlsInsecure,
    ...(config.tlsInsecure ? {} : config.caFile ? { ca: readFileSync(config.caFile) } : {}),
  });

  function send(url, method, headers, body) {
    const isHttps = url.protocol === 'https:';
    return new Promise((resolve, reject) => {
      const req = (isHttps ? https : http).request(url, {
        method,
        headers,
        agent: isHttps ? agent : false,
        signal: AbortSignal.timeout(config.readTimeoutMs),
      }, (res) => {
        const cookies = cookiesOf(res.headers['set-cookie']);
        res.resume(); // drain: only the status and headers matter
        res.on('end', () => resolve({ status: res.statusCode, cookies }));
        res.on('error', reject);
      });
      req.on('socket', (socket) => {
        const timer = setTimeout(() => req.destroy(new Error(`connect timed out after ${config.connectTimeoutMs}ms`)),
          config.connectTimeoutMs);
        socket.once('connect', () => clearTimeout(timer));
        req.once('close', () => clearTimeout(timer));
      });
      req.on('error', reject);
      req.end(body);
    });
  }

  async function call(url, method, headers, body) {
    try {
      return await send(url, method, headers, body);
    } catch (e) {
      throw new SplunkLoginError('io_error', 0, `could not reach Splunk at ${url.href}: ${e.message}`, e);
    }
  }

  return {
    mode,

    /** @returns {Promise<{cookieValue: string, httpStatus: number}>} cookieValue is a credential — never log it */
    async login() {
      console.log('login config.enabled', config.enabled)
      if (!config.enabled) return { cookieValue: `mock-${randomBytes(32).toString('hex')}`, httpStatus: 0 };

      const url = new URL(config.loginUrl);
      const cookies = new Map();
      if (config.prefetchCval) {
        const page = await call(url, 'GET', {});
        for (const [k, v] of page.cookies) cookies.set(k, v);
      }

      const form = new URLSearchParams({ username: config.username, password: config.password });
      if (cookies.has('cval')) form.set('cval', cookies.get('cval'));
      const body = form.toString();
      const headers = {
        'Content-Type': 'application/x-www-form-urlencoded',
        'Content-Length': Buffer.byteLength(body),
      };
      if (cookies.size) headers.Cookie = [...cookies].map(([k, v]) => `${k}=${v}`).join('; ');

      const reply = await call(url, 'POST', headers, body);
      const value = reply.cookies.get(config.sessionCookie);
      if (!value) {
        // 401 is a bad password; 200 without the cookie is usually a cval/CSRF rejection.
        throw new SplunkLoginError('no_session_cookie', reply.status,
          `Splunk answered ${reply.status} without a ${config.sessionCookie} cookie`);
      }
      return { cookieValue: value, httpStatus: reply.status };
    },
  };
}
