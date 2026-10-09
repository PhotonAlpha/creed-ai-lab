/**
 * The broker: verify a TOTP, then log the chosen target's shared account into Splunk and hand back
 * the session cookie as a `document.cookie` script. Each target (config.splunk.targets) carries its
 * own login URL and credentials; the page only ever learns a target's id, label, URL, username and
 * whether a password is set.
 *
 * Every step is audited, including the ones that fail — a rejected code is exactly what an audit
 * trail is for. Each row is saved on its own as its step finishes, so a Splunk failure cannot lose
 * the OTP row that preceded it. **The audit is mandatory:** if a row cannot be written the request
 * fails (500) rather than issuing an unrecorded session.
 */
import { createHash, randomUUID } from 'node:crypto';
import { COOKIE_NAME, targetConfigured } from './config.js';
import { SplunkLoginError, tunnelAddress } from './login-client.js';

/** A failure with the status and `{error, message}` the page expects. */
export class BrokerError extends Error {
  constructor(status, error, message, headers = {}) {
    super(message);
    this.status = status;
    this.error = error;
    this.headers = headers;
  }
}

/** True for an https login URL; plain http or anything unparsable is not. */
export function isHttps(loginUrl) {
  try {
    return new URL(loginUrl).protocol === 'https:';
  } catch {
    return false;
  }
}

const truncate = (s, max) => (s == null || s.length <= max ? s ?? null : s.slice(0, max));

export const fingerprint = (value) => createHash('sha256').update(value).digest('hex').slice(0, 16);

/**
 * `document.cookie = "splunkd_8089=<value>; path=/; Secure; SameSite=Lax";` — `Secure` only when
 * Splunk Web is https: the script runs on Splunk Web's own page, and a browser ignores a Secure cookie
 * set from an http one. Escaped for a JS string literal; a real Splunk value is hex, but a quote in it
 * would turn a pasted script into something else.
 */
export function script(name, value, path, secure = true) {
  const escaped = `${name}=${value}; path=${path}${secure ? '; Secure' : ''}; SameSite=Lax`
    .replace(/\\/g, '\\\\')
    .replace(/"/g, '\\"')
    .replace(/[\r\n]/g, '');
  return `document.cookie = "${escaped}";`;
}

/**
 * @param totp        a Totp
 * @param loginClient from createLoginClient
 * @param splunk      the splunk config section, with `targets` and `defaultTarget`
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

  /** What the page may know about a target — never the password itself. */
  const publicTarget = (t) => ({
    id: t.id,
    label: t.label,
    loginUrl: t.loginUrl || null,
    username: t.username || null,
    passwordSet: Boolean(t.password),
    configured: targetConfigured(splunk, t),
    sessionCookie: t.sessionCookie ?? splunk.sessionCookie,
    scriptCookieName: t.scriptCookieName ?? splunk.scriptCookieName,
    scriptCookiePath: t.scriptCookiePath ?? splunk.scriptCookiePath,
    tunnel: t.tunnel ? tunnelAddress(t.tunnel) : null,
    tunnelDefault: Boolean(t.tunnel && t.tunnelDefault),
  });

  /** A request naming no target gets the default; one naming an unknown target is a 400. */
  const resolveTarget = (id) => {
    const wanted = id ?? splunk.defaultTarget;
    const target = splunk.targets.find((t) => t.id === wanted);
    if (!target) throw new BrokerError(400, 'unknown_target', `no Splunk login target '${wanted}' — the code was not used`);
    return target;
  };

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
        // At least one target can log in; the page checks the one it has selected.
        splunkConfigured: splunk.targets.some((t) => targetConfigured(splunk, t)),
        targets: splunk.targets.map(publicTarget),
        defaultTarget: splunk.defaultTarget,
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

    /**
     * @param client   {ip, forwardedFor, userAgent} — recorded, never trusted
     * @param targetId a configured target's id; the default target when omitted
     * @param options  this login's overrides of the target's settings, each optional:
     *                 {sessionCookie, scriptCookieName, viaTunnel} — viaTunnel omitted = the target's
     *                 `tunnelDefault`
     */
    async issue(code, client, targetId, options = {}) {
      const correlationId = randomUUID();

      // --- 0. Target known and configured, overrides usable? ----------------------------------
      // Checked before the code is verified: verifying consumes it (replay protection), and a code
      // burned on a request that could never succeed sends the user's retry into a "replayed" 401.
      const target = resolveTarget(targetId);
      for (const field of ['sessionCookie', 'scriptCookieName']) {
        const value = options[field];
        if (value != null && (typeof value !== 'string' || !COOKIE_NAME.test(value))) {
          throw new BrokerError(400, 'validation_failed', `${field} must match ${COOKIE_NAME} — the code was not used`);
        }
      }
      const viaTunnel = options.viaTunnel ?? Boolean(target.tunnel && target.tunnelDefault);
      if (viaTunnel && !target.tunnel) {
        throw new BrokerError(400, 'no_tunnel', `Splunk login target '${target.label}' has no tunnel configured — the code was not used`);
      }
      const sessionCookie = options.sessionCookie || (target.sessionCookie ?? splunk.sessionCookie);
      const scriptCookieName = options.scriptCookieName || (target.scriptCookieName ?? splunk.scriptCookieName);
      const scriptCookiePath = target.scriptCookiePath ?? splunk.scriptCookiePath;
      const route = viaTunnel ? ` via tunnel ${tunnelAddress(target.tunnel)}` : '';
      if (!targetConfigured(splunk, target)) {
        await store.save(fail({ ...row(correlationId, 'SPLUNK_LOGIN', client), splunkMode: loginClient.mode },
          'not_configured', `target ${target.id}: ${target.settings ?? 'login URL / username / password'} missing`));
        throw new BrokerError(503, 'not_configured',
          `Splunk login target '${target.label}' is not configured on the server — the code was not used`);
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
        result = await loginClient.login(target, { sessionCookie, viaTunnel });
      } catch (e) {
        if (!(e instanceof SplunkLoginError)) throw e;
        await store.save(fail({
          ...loginRow,
          durationMs: Math.round(performance.now() - started),
          httpStatus: e.httpStatus || null,
        }, e.reason, `[${target.id}${route}] ${e.message}`));
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
        detail: `as ${target.username} on ${target.id}${route}, ${sessionCookie} -> ${scriptCookieName}`,
      });
      log.info(`[splunk] session issued to ${client.ip} target=${target.id}${route} fingerprint=${cookieFingerprint} [${correlationId}]`);

      const secure = isHttps(target.loginUrl);
      return {
        target: target.id,
        sourceCookie: sessionCookie,
        cookieName: scriptCookieName,
        cookieValue: result.cookieValue,
        script: script(scriptCookieName, result.cookieValue, scriptCookiePath, secure),
        secure,
        tunnel: viaTunnel ? tunnelAddress(target.tunnel) : null,
        mode: loginClient.mode,
        correlationId,
        cookieFingerprint,
        issuedAt: new Date(now()).toISOString(),
      };
    },
  };
}
