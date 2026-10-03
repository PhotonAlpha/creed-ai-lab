/**
 * The broker: verify a TOTP, then log the shared account into Splunk and hand back the session cookie
 * as a `document.cookie` script.
 *
 * Every step is audited, including the ones that fail — a rejected code is exactly what an audit
 * trail is for. Each row is saved on its own as its step finishes, so a Splunk failure cannot lose
 * the OTP row that preceded it. **The audit is mandatory:** if a row cannot be written the request
 * fails (500) rather than issuing an unrecorded session.
 */
import { createHash, randomUUID } from 'node:crypto';
import { splunkConfigured } from './config.js';
import { SplunkLoginError } from './login-client.js';

/** A failure with the status and `{error, message}` the page expects. */
export class BrokerError extends Error {
  constructor(status, error, message, headers = {}) {
    super(message);
    this.status = status;
    this.error = error;
    this.headers = headers;
  }
}

const truncate = (s, max) => (s == null || s.length <= max ? s ?? null : s.slice(0, max));

export const fingerprint = (value) => createHash('sha256').update(value).digest('hex').slice(0, 16);

/**
 * `document.cookie = "splunkd_8089=<value>; path=/; Secure; SameSite=Lax";` — escaped for a JS string
 * literal; a real Splunk value is hex, but a quote in it would turn a pasted script into something else.
 */
export function script(name, value, path) {
  const escaped = `${name}=${value}; path=${path}; Secure; SameSite=Lax`
    .replace(/\\/g, '\\\\')
    .replace(/"/g, '\\"')
    .replace(/[\r\n]/g, '');
  return `document.cookie = "${escaped}";`;
}

/**
 * @param totp        a Totp
 * @param loginClient from createLoginClient
 * @param splunk      the splunk config section
 * @param store       an audit store
 */
export function createBroker({ totp, loginClient, splunk, store, log = console, now = Date.now }) {
  /** client ip → timestamps of recent failed verifications. In-process: per instance, lost on restart. */
  const failures = new Map();
  const { maxFailures, failureWindowMs } = totp.config;

  const recentFailures = (ip) => {
    const recent = (failures.get(ip) ?? []).filter((t) => now() - t < failureWindowMs);
    failures.set(ip, recent);
    return recent;
  };

  const row = (correlationId, eventType, client) => ({
    correlationId,
    eventType,
    outcome: null,
    reason: null,
    detail: null,
    clientIp: truncate(client.ip, 64),
    forwardedFor: truncate(client.forwardedFor, 256),
    userAgent: truncate(client.userAgent, 256),
    serverStep: null,
    matchedStep: null,
    splunkMode: null,
    httpStatus: null,
    cookieFingerprint: null,
    durationMs: null,
  });
  const fail = (r, reason, detail) => ({ ...r, outcome: 'FAILURE', reason: truncate(reason, 32), detail: truncate(detail, 512) });

  return {
    info() {
      const c = totp.config;
      return {
        configured: totp.configured,
        periodSeconds: c.periodSeconds,
        digits: c.digits,
        allowedDriftSteps: c.allowedDriftSteps,
        serverTimeMillis: now(),
        codeVisible: c.exposeCurrentCode && totp.configured,
        splunkMode: loginClient.mode,
        splunkConfigured: splunkConfigured(splunk),
        loginUrl: splunk.enabled ? splunk.loginUrl : null,
        scriptCookieName: splunk.scriptCookieName,
      };
    },

    currentCode() {
      if (!totp.configured) throw new BrokerError(503, 'not_configured', 'ENV_MATRIX_TOTP_SECRET is not set');
      if (!totp.config.exposeCurrentCode) {
        throw new BrokerError(404, 'code_hidden', 'the current code is not exposed (ENV_MATRIX_TOTP_EXPOSE_CODE=false)');
      }
      return {
        code: totp.currentCode(),
        step: totp.currentStep(),
        secondsRemaining: totp.secondsRemaining(),
        periodSeconds: totp.config.periodSeconds,
        serverTimeMillis: now(),
      };
    },

    audit(limit) {
      return store.list(Math.min(Math.max(limit, 1), 500));
    },

    /** @param client {ip, forwardedFor, userAgent} — recorded, never trusted */
    async issue(code, client) {
      const correlationId = randomUUID();

      // --- 0. Splunk configured? -------------------------------------------------------------
      // Checked before the code is verified: verifying consumes it (replay protection), and a code
      // burned on a request that could never succeed sends the user's retry into a "replayed" 401.
      console.log('splunk configuration', splunk);
      if (!splunkConfigured(splunk)) {
        await store.save(fail({ ...row(correlationId, 'SPLUNK_LOGIN', client), splunkMode: loginClient.mode },
          'not_configured', 'SPLUNK_LOGIN_URL / SPLUNK_USERNAME / SPLUNK_PASSWORD missing'));
        throw new BrokerError(503, 'not_configured', 'Splunk login is not configured on the server — the code was not used');
      }

      // --- 1. OTP ---------------------------------------------------------------------------
      const otpRow = row(correlationId, 'OTP_VERIFY', client);
      if (!totp.configured) {
        await store.save(fail(otpRow, 'not_configured', 'ENV_MATRIX_TOTP_SECRET is not set'));
        throw new BrokerError(503, 'not_configured', 'TOTP is not configured on the server');
      }
      const recent = recentFailures(client.ip);
      if (recent.length >= maxFailures) {
        await store.save(fail(otpRow, 'locked_out', `more than ${maxFailures} failures within ${failureWindowMs / 1000}s`));
        throw new BrokerError(429, 'too_many_attempts', `too many failed codes — try again in ${failureWindowMs / 1000}s`,
          { 'Retry-After': String(failureWindowMs / 1000) });
      }

      // Synchronous: nothing between the check and the record of the accepted step may await.
      const v = totp.verify(code);
      otpRow.serverStep = v.serverStep;
      otpRow.matchedStep = v.matchedStep;
      if (!v.valid) {
        recent.push(now());
        await store.save(fail(otpRow, v.reason, null));
        log.warn(`[splunk] OTP rejected (${v.reason}) from ${client.ip} [${correlationId}]`);
        throw v.reason === 'replayed'
          ? new BrokerError(401, 'otp_replayed', 'this code has already been used — wait for the next one')
          : new BrokerError(401, 'otp_invalid', 'the code is invalid or expired');
      }
      failures.delete(client.ip);
      await store.save({ ...otpRow, outcome: 'SUCCESS', detail: `drift=${v.drift}` });

      // --- 2. Splunk ------------------------------------------------------------------------
      const loginRow = { ...row(correlationId, 'SPLUNK_LOGIN', client), splunkMode: loginClient.mode };
      const started = performance.now();
      let result;
      try {
        result = await loginClient.login();
      } catch (e) {
        if (!(e instanceof SplunkLoginError)) throw e;
        await store.save(fail({
          ...loginRow,
          durationMs: Math.round(performance.now() - started),
          httpStatus: e.httpStatus || null,
        }, e.reason, e.message));
        log.warn(`[splunk] login failed (${e.reason}) [${correlationId}]: ${e.message}`);
        // Splunk, not this service, is what failed — hence 502, with the audit reason as the error code.
        throw new BrokerError(502, `splunk_${e.reason}`, e.message);
      }

      const cookieFingerprint = fingerprint(result.cookieValue);
      await store.save({
        ...loginRow,
        outcome: 'SUCCESS',
        durationMs: Math.round(performance.now() - started),
        httpStatus: result.httpStatus || null,
        cookieFingerprint,
        detail: `as ${splunk.username}`,
      });
      log.info(`[splunk] session issued to ${client.ip} fingerprint=${cookieFingerprint} [${correlationId}]`);

      return {
        sourceCookie: splunk.sessionCookie,
        cookieName: splunk.scriptCookieName,
        cookieValue: result.cookieValue,
        script: script(splunk.scriptCookieName, result.cookieValue, splunk.scriptCookiePath),
        mode: loginClient.mode,
        correlationId,
        cookieFingerprint,
        issuedAt: new Date(now()).toISOString(),
      };
    },
  };
}
