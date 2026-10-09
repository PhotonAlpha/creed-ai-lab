/**
 * Splunk session broker configuration — what used to be `env-matrix.totp.*` / `env-matrix.splunk.*`
 * in creed-resource-env-matrix. Same variable names and defaults, so an existing environment carries
 * over unchanged.
 *
 * Secrets (TOTP secret, Splunk password, audit DB password) are read from `NAME`, or from the file
 * named by `NAME_FILE` — the shape Docker/Kubernetes secrets and a Vault agent's file sink produce,
 * so a secret never has to sit in an env var readable from `ps e` or /proc. `_FILE` wins when both
 * are set. `npm run bff` additionally loads `.env.server.local` (git-ignored via `*.local`).
 *
 * **Login targets.** `SPLUNK_TARGETS=SIT,UAT` declares several Splunk instances the page offers in a
 * dropdown, each with its own `SPLUNK_TARGET_<ID>_LOGIN_URL` / `_USERNAME` / `_PASSWORD` (or
 * `_PASSWORD_FILE`) and optional `_LABEL` / `_SESSION_COOKIE` / `_SCRIPT_COOKIE_NAME` / `_SCRIPT_COOKIE_PATH` /
 * `_TUNNEL` / `_TUNNEL_DEFAULT`. There is deliberately no fallback from
 * a target's credentials to the global ones: that would send one instance's password to another
 * host. Without `SPLUNK_TARGETS` there is one target, `default`, built from the original
 * `SPLUNK_LOGIN_URL` / `SPLUNK_USERNAME` / `SPLUNK_PASSWORD`, so an existing environment carries over.
 * The `SPLUNK_TARGET_` prefix keeps a target id from colliding with a global (an id `DB` would
 * otherwise read `SPLUNK_DB_URL`).
 *
 * **Cookie names are per target and overridable per request.** `splunkd_<port>` follows the port
 * Splunk Web runs on, so a :3000 instance answers `splunkd_3000`; the globals are only the default for
 * a target that sets none, and the page may override both names for one login (see broker.issue).
 *
 * **Tunnel.** `_TUNNEL=<host>:<port>` names a TCP forward (e.g. `<server-host>:3000` → `<uat-host>:3000`)
 * the login may connect through instead of the login URL's own address — curl `--connect-to`
 * semantics: the socket goes to the tunnel, while the URL, `Host` header and TLS SNI stay the target's,
 * so Splunk sees a request for itself and certificate checks (when on) still name the real host. The
 * page offers it as a switch, off (direct) unless `_TUNNEL_DEFAULT=true`.
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
/** Becomes part of an env var name, so letters, digits and underscores only. */
const TARGET_ID = /^[A-Z0-9_]{1,32}$/;
/** An RFC 6265 cookie-name token, narrowed: it is interpolated into a `document.cookie` script. */
export const COOKIE_NAME = /^[A-Za-z0-9_.-]{1,64}$/;

function cookieName(env, name, fallback) {
  const value = env[name] || fallback;
  if (!COOKIE_NAME.test(value)) throw new Error(`${name} must match ${COOKIE_NAME}, got '${value}'`);
  return value;
}

/** `host:port` (or `[v6]:port`) → {host, port}; null when unset. Fails at startup, not on a login. */
export function parseTunnel(name, raw) {
  if (raw == null || raw.trim() === '') return null;
  const m = /^(?:\[([0-9a-fA-F:.]+)\]|([A-Za-z0-9.-]+)):(\d{1,5})$/.exec(raw.trim());
  const port = m && Number(m[3]);
  if (!m || port < 1 || port > 65535) throw new Error(`${name} must be host:port, got '${raw}'`);
  return { host: m[1] ?? m[2], port };
}

const tunnelOf = (env, name) => {
  const tunnel = parseTunnel(name, env[name]);
  return { tunnel, tunnelDefault: tunnel ? bool(env[`${name}_DEFAULT`], false) : false };
};

/**
 * The login targets. Each is everything a login needs; the shared settings (TLS, timeouts, cval,
 * script cookie) stay on the splunk section.
 */
function loadTargets(env, defaults) {
  const ids = (env.SPLUNK_TARGETS ?? '').split(',').map((id) => id.trim().toUpperCase()).filter(Boolean);
  if (ids.length === 0) {
    return [{
      id: 'default',
      label: env.SPLUNK_LABEL || 'default',
      loginUrl: env.SPLUNK_LOGIN_URL ?? 'https://splunk.example.invalid:8000/en-US/account/login',
      username: env.SPLUNK_USERNAME ?? 'admin',
      password: readSecret(env, 'SPLUNK_PASSWORD', 'admin'),
      ...defaults,
      ...tunnelOf(env, 'SPLUNK_TUNNEL'),
      // What a missing value is called in this configuration, for the audit trail and the 503.
      settings: 'SPLUNK_LOGIN_URL / SPLUNK_USERNAME / SPLUNK_PASSWORD',
    }];
  }
  const seen = new Set();
  return ids.map((id) => {
    if (!TARGET_ID.test(id)) throw new Error(`SPLUNK_TARGETS: '${id}' must match ${TARGET_ID}`);
    if (seen.has(id)) throw new Error(`SPLUNK_TARGETS: '${id}' is listed twice`);
    seen.add(id);
    const prefix = `SPLUNK_TARGET_${id}_`;
    return {
      id,
      label: env[`${prefix}LABEL`] || id,
      loginUrl: env[`${prefix}LOGIN_URL`] ?? '',
      username: env[`${prefix}USERNAME`] ?? '',
      password: readSecret(env, `${prefix}PASSWORD`, ''),
      sessionCookie: cookieName(env, `${prefix}SESSION_COOKIE`, defaults.sessionCookie),
      scriptCookieName: cookieName(env, `${prefix}SCRIPT_COOKIE_NAME`, defaults.scriptCookieName),
      scriptCookiePath: env[`${prefix}SCRIPT_COOKIE_PATH`] || defaults.scriptCookiePath,
      ...tunnelOf(env, `${prefix}TUNNEL`),
      settings: `${prefix}LOGIN_URL / ${prefix}USERNAME / ${prefix}PASSWORD`,
    };
  });
}

export function loadConfig(env = process.env) {
  const totp = {
    // Blank => the broker answers 503 instead of accepting any code. The fallback is a DEMO secret.
    secret: readSecret(env, 'ENV_MATRIX_TOTP_SECRET', 'JBSWY3DPEHPK3PXP'),
    // 60 by request (as creed-gateway-proxy). Authenticator apps assume 30: one reading the same secret
    // would show different codes — the page's exposed code is what this broker accepts.
    periodSeconds: int(env, 'ENV_MATRIX_TOTP_PERIOD_SECONDS', 60, 1, 3600),
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
    // Defaults for every target's own cookie settings (splunkd_<port> — it follows Splunk Web's port).
    sessionCookie: cookieName(env, 'SPLUNK_SESSION_COOKIE', 'splunkd_8000'),
    scriptCookieName: cookieName(env, 'SPLUNK_SCRIPT_COOKIE_NAME', 'splunkd_8089'),
    scriptCookiePath: env.SPLUNK_SCRIPT_COOKIE_PATH || '/',
    prefetchCval: bool(env.SPLUNK_PREFETCH_CVAL, true),
    connectTimeoutMs: int(env, 'SPLUNK_CONNECT_TIMEOUT_MS', 5000, 1, 600000),
    readTimeoutMs: int(env, 'SPLUNK_READ_TIMEOUT_MS', 15000, 1, 600000),
    // On by default, by request: Splunk's certificate is NOT verified — neither the chain nor the
    // hostname. Set false to verify against the system roots plus SPLUNK_CA_FILE.
    tlsInsecure: bool(env.SPLUNK_TLS_INSECURE, true),
    caFile: env.SPLUNK_CA_FILE || null,
  };
  splunk.targets = loadTargets(env, {
    sessionCookie: splunk.sessionCookie,
    scriptCookieName: splunk.scriptCookieName,
    scriptCookiePath: splunk.scriptCookiePath,
  });
  // The dropdown's initial choice, and the target of a request that names none.
  splunk.defaultTarget = splunk.targets[0].id;
  const wanted = (env.SPLUNK_DEFAULT_TARGET ?? '').trim();
  if (wanted) {
    const found = splunk.targets.find((t) => t.id === wanted.toUpperCase() || t.id === wanted);
    if (!found) throw new Error(`SPLUNK_DEFAULT_TARGET '${wanted}' is not one of SPLUNK_TARGETS`);
    splunk.defaultTarget = found.id;
  }

  const audit = {
    // memory (default) | pg | mysql. memory keeps the newest 500 rows and forgets them on restart;
    // pg writes splunk_broker.splunk_audit, mysql writes splunk_audit in SPLUNK_DB_URL's database.
    // The SPLUNK_DB_* settings below are read either way but used only by pg and mysql.
    store: env.SPLUNK_AUDIT_STORE ?? 'memory',
    dbUrl: env.SPLUNK_DB_URL ?? (env.SPLUNK_AUDIT_STORE === 'mysql'
      ? 'mysql://127.0.0.1:3306/env_matrix'
      : 'postgres://127.0.0.1:5432/env_matrix'),
    dbUser: env.SPLUNK_DB_USER ?? 'artifactory',
    dbPassword: readSecret(env, 'SPLUNK_DB_PASSWORD', 'artifactory_pw'),
    // pg only (MySQL uses the URL's database). Interpolated into DDL, so a plain identifier.
    schema: env.SPLUNK_AUDIT_SCHEMA ?? 'splunk_broker',
  };
  if (!['memory', 'pg', 'mysql'].includes(audit.store)) {
    throw new Error(`SPLUNK_AUDIT_STORE must be 'memory', 'pg' or 'mysql', got '${audit.store}'`);
  }
  // A postgres:// URL handed to the mysql store (or the reverse) fails later with a protocol error
  // that does not mention the setting; say which one is wrong instead.
  const expected = { pg: /^postgres(ql)?:/, mysql: /^mysql:/ }[audit.store];
  if (expected && !expected.test(audit.dbUrl)) {
    throw new Error(`SPLUNK_DB_URL '${audit.dbUrl.replace(/\/\/[^@/]*@/, '//***@')}' does not fit SPLUNK_AUDIT_STORE=${audit.store}`);
  }
  if (!IDENTIFIER.test(audit.schema)) {
    throw new Error(`SPLUNK_AUDIT_SCHEMA must match ${IDENTIFIER}, got '${audit.schema}'`);
  }
  base32Check(totp.secret);

  return { totp, splunk, audit };
}

/** Whether a login to this target can be attempted; the mock needs nothing. */
export const targetConfigured = (s, target) =>
  !s.enabled || Boolean(target?.loginUrl && target.username && target.password);

/** For the startup log line — every credential masked. */
export function describe({ totp, splunk, audit }) {
  const mask = (v) => (v ? '***' : '<unset>');
  return {
    totp: { ...totp, secret: mask(totp.secret) },
    splunk: { ...splunk, targets: splunk.targets.map(({ settings, ...t }) => ({ ...t, password: mask(t.password) })) },
    audit: { ...audit, dbPassword: mask(audit.dbPassword) },
  };
}

/** Fail at startup, not on the first submitted code. */
function base32Check(secret) {
  if (secret && /[^A-Z2-7\s=-]/i.test(secret)) throw new Error('ENV_MATRIX_TOTP_SECRET is not valid Base32');
}
