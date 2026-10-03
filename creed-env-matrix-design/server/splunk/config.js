/**
 * Splunk session broker configuration — what used to be `env-matrix.totp.*` / `env-matrix.splunk.*`
 * in creed-resource-env-matrix. Same variable names and defaults, so an existing environment carries
 * over unchanged.
 *
 * Secrets (TOTP secret, Splunk password, audit DB password) are read from `NAME`, or from the file
 * named by `NAME_FILE` — the shape Docker/Kubernetes secrets and a Vault agent's file sink produce,
 * so a secret never has to sit in an env var readable from `ps e` or /proc. `_FILE` wins when both
 * are set. `npm run bff` additionally loads `.env.server.local` (git-ignored via `*.local`).
 */
import { readFileSync } from 'node:fs';

export function readSecret(env, name, fallback) {
  const file = env[`${name}_FILE`];
  // trim: `echo secret > file` leaves a newline, and a trailing \n in a password fails the login.
  if (file) return readFileSync(file, 'utf8').trim();
  return env[name] ?? fallback;
}

const bool = (value, fallback) => (value == null || value === '' ? fallback : /^(true|1|yes|on)$/i.test(value));

function int(env, name, fallback, min, max) {
  const raw = env[name];
  const value = raw == null || raw === '' ? fallback : Number(raw);
  if (!Number.isInteger(value) || value < min || value > max) {
    throw new Error(`${name} must be an integer in ${min}..${max}, got '${raw}'`);
  }
  return value;
}

const IDENTIFIER = /^[a-z_][a-z0-9_]*$/;

export function loadConfig(env = process.env) {
  const totp = {
    // Blank => the broker answers 503 instead of accepting any code. The fallback is a DEMO secret.
    secret: readSecret(env, 'ENV_MATRIX_TOTP_SECRET', 'JBSWY3DPEHPK3PXP'),
    periodSeconds: int(env, 'ENV_MATRIX_TOTP_PERIOD_SECONDS', 30, 1, 3600),
    digits: int(env, 'ENV_MATRIX_TOTP_DIGITS', 6, 6, 8),
    allowedDriftSteps: int(env, 'ENV_MATRIX_TOTP_DRIFT_STEPS', 1, 0, 5),
    rejectReplay: bool(env.ENV_MATRIX_TOTP_REJECT_REPLAY, true),
    // On by default, by request: anyone who can read /splunk/totp/current passes the check.
    exposeCurrentCode: bool(env.ENV_MATRIX_TOTP_EXPOSE_CODE, true),
    maxFailures: int(env, 'ENV_MATRIX_TOTP_MAX_FAILURES', 5, 1, 1000),
    failureWindowMs: int(env, 'ENV_MATRIX_TOTP_FAILURE_WINDOW_SECONDS', 60, 1, 86400) * 1000,
  };

  const splunk = {
    // The switch for the real call. false: a fabricated cookie, no network.
    enabled: bool(env.SPLUNK_ENABLED, false),
    loginUrl: env.SPLUNK_LOGIN_URL ?? 'https://splunk.example.invalid:8000/en-US/account/login',
    username: env.SPLUNK_USERNAME ?? 'admin',
    password: readSecret(env, 'SPLUNK_PASSWORD', 'admin'),
    sessionCookie: env.SPLUNK_SESSION_COOKIE ?? 'splunkd_8000',
    scriptCookieName: env.SPLUNK_SCRIPT_COOKIE_NAME ?? 'splunkd_8089',
    scriptCookiePath: env.SPLUNK_SCRIPT_COOKIE_PATH ?? '/',
    prefetchCval: bool(env.SPLUNK_PREFETCH_CVAL, true),
    connectTimeoutMs: int(env, 'SPLUNK_CONNECT_TIMEOUT_MS', 5000, 1, 600000),
    readTimeoutMs: int(env, 'SPLUNK_READ_TIMEOUT_MS', 15000, 1, 600000),
    // On by default, by request: Splunk's certificate is NOT verified — neither the chain nor the
    // hostname. Set false to verify against the system roots plus SPLUNK_CA_FILE.
    tlsInsecure: bool(env.SPLUNK_TLS_INSECURE, true),
    caFile: env.SPLUNK_CA_FILE || null,
  };

  const audit = {
    // memory (default) | pg. memory keeps the newest 500 rows and forgets them on restart; pg writes
    // splunk_broker.splunk_audit and is the only choice that leaves a trail worth the name. The
    // SPLUNK_DB_* settings below are read either way but used only by pg.
    store: env.SPLUNK_AUDIT_STORE ?? 'memory',
    dbUrl: env.SPLUNK_DB_URL ?? 'postgres://127.0.0.1:5432/env_matrix',
    dbUser: env.SPLUNK_DB_USER ?? 'artifactory',
    dbPassword: readSecret(env, 'SPLUNK_DB_PASSWORD', 'artifactory_pw'),
    // Interpolated into DDL, so it must be a plain identifier.
    schema: env.SPLUNK_AUDIT_SCHEMA ?? 'splunk_broker',
  };
  if (!['pg', 'memory'].includes(audit.store)) {
    throw new Error(`SPLUNK_AUDIT_STORE must be 'pg' or 'memory', got '${audit.store}'`);
  }
  if (!IDENTIFIER.test(audit.schema)) {
    throw new Error(`SPLUNK_AUDIT_SCHEMA must match ${IDENTIFIER}, got '${audit.schema}'`);
  }
  base32Check(totp.secret);

  return { totp, splunk, audit };
}

/** Whether a login can be attempted; the mock needs nothing. */
export const splunkConfigured = (s) => !s.enabled || Boolean(s.loginUrl && s.username && s.password);

/** For the startup log line — every credential masked. */
export function describe({ totp, splunk, audit }) {
  const mask = (v) => (v ? '***' : '<unset>');
  return {
    totp: { ...totp, secret: mask(totp.secret) },
    splunk: { ...splunk, password: mask(splunk.password) },
    audit: { ...audit, dbPassword: mask(audit.dbPassword) },
  };
}

/** Fail at startup, not on the first submitted code. */
function base32Check(secret) {
  if (secret && /[^A-Z2-7\s=-]/i.test(secret)) throw new Error('ENV_MATRIX_TOTP_SECRET is not valid Base32');
}
