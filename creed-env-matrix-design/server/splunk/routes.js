/**
 * `/api/env-matrix/splunk/*` — the contract the Splunk page calls, served by both the mock
 * (server/index.js, memory audit, Splunk always mocked) and the BFF (server/bff.js, pg audit):
 *
 *   GET  /splunk/totp          period/digits/server time, the login targets — drives the page
 *   GET  /splunk/totp/current  the current code (only with ENV_MATRIX_TOTP_EXPOSE_CODE)
 *   POST /splunk/session       {code, target?} → 200 session | 400 malformed or unknown target
 *                              | 401 bad code | 429 locked out | 502 Splunk | 503 unset
 *   GET  /splunk/audit?limit=  newest audit rows
 */
import { BrokerError, createBroker } from './broker.js';
import { createLoginClient } from './login-client.js';
import { Totp } from './totp.js';

export const SPLUNK_PREFIX = '/api/env-matrix/splunk';

export function createSplunkBroker(config, store, log = console) {
  return createBroker({
    totp: new Totp(config.totp),
    loginClient: createLoginClient(config.splunk),
    splunk: config.splunk,
    store,
    log,
  });
}

const send = (res, status, body, headers = {}) => {
  res.writeHead(status, { 'Content-Type': 'application/json; charset=utf-8', ...headers });
  res.end(JSON.stringify(body));
};

const error = (res, status, err, message, extra = {}, headers = {}) =>
  send(res, status, { error: err, message, ...extra, time: new Date().toISOString() }, headers);

async function readJson(req) {
  const chunks = [];
  let size = 0;
  for await (const chunk of req) {
    size += chunk.length;
    if (size > 4096) throw new BrokerError(413, 'payload_too_large', 'request body is too large');
    chunks.push(chunk);
  }
  try {
    return chunks.length ? JSON.parse(Buffer.concat(chunks).toString('utf8')) : {};
  } catch {
    throw new BrokerError(400, 'validation_failed', 'request body is not valid JSON');
  }
}

/** `no-store` on everything that carries a code or a live session credential. */
const NO_STORE = { 'Cache-Control': 'no-store' };

/**
 * @param path the request path with `/api/env-matrix` already stripped
 * @returns true when the request was a Splunk route and has been answered
 */
export async function handleSplunk(broker, req, res, path, params) {
  if (!path.startsWith('/splunk/')) return false;
  try {
    if (req.method === 'GET' && path === '/splunk/totp') {
      send(res, 200, broker.info());
    } else if (req.method === 'GET' && path === '/splunk/totp/current') {
      send(res, 200, broker.currentCode(), NO_STORE);
    } else if (req.method === 'POST' && path === '/splunk/session') {
      const body = await readJson(req);
      if (typeof body.code !== 'string' || !/^\d{6,8}$/.test(body.code)) {
        error(res, 400, 'validation_failed', 'request payload is invalid',
          { fields: [{ field: 'code', message: 'must be 6-8 digits' }] });
      } else if (body.target != null && (typeof body.target !== 'string' || body.target.length > 64)) {
        error(res, 400, 'validation_failed', 'request payload is invalid',
          { fields: [{ field: 'target', message: 'must be a login target id' }] });
      } else {
        const client = {
          ip: req.socket.remoteAddress ?? null,
          forwardedFor: req.headers['x-forwarded-for'] ?? null,
          userAgent: req.headers['user-agent'] ?? null,
        };
        send(res, 200, await broker.issue(body.code, client, body.target ?? undefined), NO_STORE);
      }
    } else if (req.method === 'GET' && path === '/splunk/audit') {
      send(res, 200, await broker.audit(Number(params.get('limit') ?? 50) || 50));
    } else {
      error(res, 404, 'not_found', `no route for ${req.method} ${SPLUNK_PREFIX}${path.slice('/splunk'.length)}`);
    }
  } catch (e) {
    if (e instanceof BrokerError) {
      error(res, e.status, e.error, e.message, {}, e.headers);
    } else {
      // Most likely the audit store — the broker fails closed rather than issue an unrecorded session.
      console.error('[splunk] request failed', e);
      error(res, 500, 'internal_error', 'the Splunk broker failed — see the server log');
    }
  }
  return true;
}
