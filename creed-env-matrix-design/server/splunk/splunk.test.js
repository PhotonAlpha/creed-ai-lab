/**
 * `npm run test:server` — the broker's tests, ported from the Java module they replaced.
 * No network beyond loopback stubs, no database (the pg store is exercised by hand; see HANDOFF).
 */
import { after, before, describe, test } from 'node:test';
import assert from 'node:assert/strict';
import http from 'node:http';
import https from 'node:https';
import { execFileSync } from 'node:child_process';
import { mkdtempSync, readFileSync, rmSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { Totp } from './totp.js';
import { cookiesOf, createLoginClient } from './login-client.js';
import { MemoryAuditStore } from './audit-store.js';
import { createBroker, script } from './broker.js';
import { describe as describeConfig, loadConfig } from './config.js';

// RFC 6238 appendix B's SHA-1 key ("12345678901234567890"), Base32.
const RFC_KEY = 'GEZDGNBVGY3TQOJQGEZDGNBVGY3TQOJQ';
const totpConfig = (over = {}) => ({
  secret: RFC_KEY, periodSeconds: 30, digits: 6, allowedDriftSteps: 1, rejectReplay: true,
  exposeCurrentCode: true, maxFailures: 3, failureWindowMs: 60_000, ...over,
});

describe('totp', () => {
  test('matches the RFC 6238 appendix-B SHA-1 vectors', () => {
    const vectors = [[59, '94287082'], [1111111109, '07081804'], [1111111111, '14050471'],
      [1234567890, '89005924'], [2000000000, '69279037'], [20000000000, '65353130']];
    for (const [t, code] of vectors) {
      const totp = new Totp(totpConfig({ digits: 8 }), () => t * 1000);
      assert.equal(totp.currentCode(), code, `T=${t}`);
    }
  });

  test('six digits is the eight-digit value mod 10^6', () => {
    assert.equal(new Totp(totpConfig(), () => 59_000).currentCode(), '287082');
  });

  test('accepts ±1 step and rejects ±2', () => {
    let now = 1111111111_000;
    const totp = new Totp(totpConfig({ rejectReplay: false }), () => now);
    const step = totp.currentStep();
    assert.equal(totp.verify(totp.codeAt(step - 1)).drift, -1);
    assert.equal(totp.verify(totp.codeAt(step + 1)).drift, 1);
    assert.equal(totp.verify(totp.codeAt(step + 2)).reason, 'invalid_code');
    assert.equal(totp.verify(totp.codeAt(step - 2)).reason, 'invalid_code');
  });

  test('a code cannot be used twice, nor an older one after it', () => {
    const totp = new Totp(totpConfig(), () => 1111111111_000);
    const step = totp.currentStep();
    assert.equal(totp.verify(totp.codeAt(step)).valid, true);
    assert.equal(totp.verify(totp.codeAt(step)).reason, 'replayed');
    assert.equal(totp.verify(totp.codeAt(step - 1)).reason, 'replayed');
  });

  test('malformed input', () => {
    const totp = new Totp(totpConfig());
    for (const code of ['', '12345', '1234567', 'abcdef', null]) assert.equal(totp.verify(code).reason, 'malformed');
  });

  test('secondsRemaining runs period..1', () => {
    assert.equal(new Totp(totpConfig(), () => 60_000).secondsRemaining(), 30);
    assert.equal(new Totp(totpConfig(), () => 89_000).secondsRemaining(), 1);
  });
});

describe('config', () => {
  test('secrets are read from *_FILE, which wins over the plain variable', () => {
    const dir = mkdtempSync(join(tmpdir(), 'splunk-cfg-'));
    try {
      execFileSync('sh', ['-c', `printf 's3cret\\n' > ${join(dir, 'pw')}`]);
      const cfg = loadConfig({ SPLUNK_PASSWORD: 'plain', SPLUNK_PASSWORD_FILE: join(dir, 'pw') });
      assert.equal(cfg.splunk.targets[0].password, 's3cret');
      const multi = loadConfig({ SPLUNK_TARGETS: 'sit', SPLUNK_TARGET_SIT_PASSWORD: 'plain', SPLUNK_TARGET_SIT_PASSWORD_FILE: join(dir, 'pw') });
      assert.equal(multi.splunk.targets[0].password, 's3cret');
    } finally {
      rmSync(dir, { recursive: true });
    }
  });

  test('the audit goes to memory unless SPLUNK_AUDIT_STORE is pg or mysql', () => {
    assert.equal(loadConfig({}).audit.store, 'memory');
    assert.equal(loadConfig({ SPLUNK_AUDIT_STORE: 'pg' }).audit.store, 'pg');
    assert.equal(loadConfig({ SPLUNK_AUDIT_STORE: 'mysql' }).audit.store, 'mysql');
    assert.throws(() => loadConfig({ SPLUNK_AUDIT_STORE: 'postgres' }), /SPLUNK_AUDIT_STORE/);
  });

  test('the default SPLUNK_DB_URL follows the store; a URL for the other database is rejected', () => {
    assert.equal(loadConfig({ SPLUNK_AUDIT_STORE: 'mysql' }).audit.dbUrl, 'mysql://127.0.0.1:3306/env_matrix');
    assert.equal(loadConfig({ SPLUNK_AUDIT_STORE: 'pg' }).audit.dbUrl, 'postgres://127.0.0.1:5432/env_matrix');
    assert.throws(() => loadConfig({ SPLUNK_AUDIT_STORE: 'mysql', SPLUNK_DB_URL: 'postgres://u:secret@h/db' }),
      (e) => /does not fit SPLUNK_AUDIT_STORE=mysql/.test(e.message) && !e.message.includes('secret'));
    assert.throws(() => loadConfig({ SPLUNK_AUDIT_STORE: 'pg', SPLUNK_DB_URL: 'mysql://h/db' }), /SPLUNK_AUDIT_STORE=pg/);
  });

  test('without SPLUNK_TARGETS there is one target, default, from the original variables', () => {
    const { splunk } = loadConfig({ SPLUNK_LOGIN_URL: 'https://a/login', SPLUNK_USERNAME: 'u', SPLUNK_PASSWORD: 'p' });
    assert.equal(splunk.defaultTarget, 'default');
    assert.deepEqual(splunk.targets.map(({ id, loginUrl, username, password, sessionCookie }) => ({ id, loginUrl, username, password, sessionCookie })),
      [{ id: 'default', loginUrl: 'https://a/login', username: 'u', password: 'p', sessionCookie: 'splunkd_8000' }]);
  });

  test('SPLUNK_TARGETS: each target its own URL, user and password — no fallback to the globals', () => {
    const { splunk } = loadConfig({
      SPLUNK_TARGETS: ' sit, uat ',
      SPLUNK_USERNAME: 'global-user', SPLUNK_PASSWORD: 'global-pw',
      SPLUNK_TARGET_SIT_LABEL: 'SIT (CN)', SPLUNK_TARGET_SIT_LOGIN_URL: 'https://sit/login',
      SPLUNK_TARGET_SIT_USERNAME: 'sit-user', SPLUNK_TARGET_SIT_PASSWORD: 'sit-pw',
      SPLUNK_TARGET_UAT_LOGIN_URL: 'https://uat:8443/login', SPLUNK_TARGET_UAT_SESSION_COOKIE: 'splunkd_8443',
      SPLUNK_DEFAULT_TARGET: 'uat',
    });
    assert.deepEqual(splunk.targets.map(({ id, label, username, password, sessionCookie }) => [id, label, username, password, sessionCookie]), [
      ['SIT', 'SIT (CN)', 'sit-user', 'sit-pw', 'splunkd_8000'],
      ['UAT', 'UAT', '', '', 'splunkd_8443'],
    ]);
    assert.equal(splunk.defaultTarget, 'UAT');
  });

  test('cookie names and path per target, the globals as their default', () => {
    const { splunk } = loadConfig({
      SPLUNK_TARGETS: 'sit,uat', SPLUNK_SCRIPT_COOKIE_NAME: 'splunkd_9000', SPLUNK_SCRIPT_COOKIE_PATH: '/en-US',
      SPLUNK_TARGET_UAT_SESSION_COOKIE: 'splunkd_3000', SPLUNK_TARGET_UAT_SCRIPT_COOKIE_NAME: 'splunkd_3000',
      SPLUNK_TARGET_UAT_SCRIPT_COOKIE_PATH: '/',
    });
    assert.deepEqual(splunk.targets.map((t) => [t.id, t.sessionCookie, t.scriptCookieName, t.scriptCookiePath]), [
      ['SIT', 'splunkd_8000', 'splunkd_9000', '/en-US'],
      ['UAT', 'splunkd_3000', 'splunkd_3000', '/'],
    ]);
    assert.throws(() => loadConfig({ SPLUNK_TARGETS: 'a', SPLUNK_TARGET_A_SCRIPT_COOKIE_NAME: 'x"; alert(1)//' }),
      /SPLUNK_TARGET_A_SCRIPT_COOKIE_NAME/);
    assert.throws(() => loadConfig({ SPLUNK_SESSION_COOKIE: 'a b' }), /SPLUNK_SESSION_COOKIE/);
  });

  test('a tunnel is host:port, off unless _TUNNEL_DEFAULT=true, and validated at startup', () => {
    const { splunk } = loadConfig({
      SPLUNK_TARGETS: 'sit,uat,id',
      SPLUNK_TARGET_UAT_TUNNEL: 'server-host:3000', SPLUNK_TARGET_UAT_TUNNEL_DEFAULT: 'true',
      SPLUNK_TARGET_ID_TUNNEL: '[::1]:3001',
    });
    assert.deepEqual(splunk.targets.map((t) => [t.id, t.tunnel, t.tunnelDefault]), [
      ['SIT', null, false],
      ['UAT', { host: 'server-host', port: 3000 }, true],
      ['ID', { host: '::1', port: 3001 }, false],
    ]);
    assert.deepEqual(loadConfig({ SPLUNK_TUNNEL: '10.0.0.5:8000' }).splunk.targets[0].tunnel, { host: '10.0.0.5', port: 8000 });
    assert.throws(() => loadConfig({ SPLUNK_TARGETS: 'a', SPLUNK_TARGET_A_TUNNEL: 'server-host' }), /SPLUNK_TARGET_A_TUNNEL/);
    assert.throws(() => loadConfig({ SPLUNK_TARGETS: 'a', SPLUNK_TARGET_A_TUNNEL: 'h:70000' }), /host:port/);
  });

  test('SPLUNK_TARGETS rejects a bad or repeated id and an unknown default', () => {
    assert.throws(() => loadConfig({ SPLUNK_TARGETS: 'si-t' }), /SPLUNK_TARGETS/);
    assert.throws(() => loadConfig({ SPLUNK_TARGETS: 'sit,SIT' }), /listed twice/);
    assert.throws(() => loadConfig({ SPLUNK_TARGETS: 'sit', SPLUNK_DEFAULT_TARGET: 'prod' }), /SPLUNK_DEFAULT_TARGET/);
  });

  test('describe masks every target password', () => {
    const cfg = loadConfig({ SPLUNK_TARGETS: 'a,b', SPLUNK_TARGET_A_PASSWORD: 'pa', SPLUNK_TARGET_B_PASSWORD: '' });
    const text = JSON.stringify(describeConfig(cfg));
    assert.equal(text.includes('"pa"'), false);
    assert.deepEqual(describeConfig(cfg).splunk.targets.map((t) => t.password), ['***', '<unset>']);
  });

  test('rejects a schema name that is not a plain identifier', () => {
    assert.throws(() => loadConfig({ SPLUNK_AUDIT_SCHEMA: 'x; drop table y' }), /SPLUNK_AUDIT_SCHEMA/);
  });
});

/** A Splunk Web stand-in: cval on GET, the session cookie on a 303 that must not be followed. */
function splunkStub(handler) {
  return (req, res) => {
    let body = '';
    req.on('data', (c) => { body += c; });
    req.on('end', () => {
      if (req.url !== '/en-US/account/login') {
        // Reached only by a client that followed the redirect — and it carries no session cookie.
        res.writeHead(200, { 'Set-Cookie': 'other=1' });
        return res.end('home');
      }
      if (req.method === 'GET') {
        res.writeHead(200, { 'Set-Cookie': ['cval=777; Path=/', 'session_id_8000=x; Path=/'] });
        return res.end('login page');
      }
      handler(new URLSearchParams(body), req.headers.cookie ?? '', res);
    });
  };
}

const goodLogin = (form, cookie, res) => {
  if (form.get('cval') !== '777' || !cookie.includes('cval=777')) {
    res.writeHead(200); // what Splunk 7+ does without cval: no error, just no session
    return res.end();
  }
  if (form.get('password') !== 'pw') {
    res.writeHead(401);
    return res.end();
  }
  res.writeHead(303, { Location: '/en-US/app', 'Set-Cookie': ['splunkd_8000="abc123"; Path=/; HttpOnly', 'cval=777'] });
  res.end();
};

const listen = (server) => new Promise((r) => server.listen(0, '127.0.0.1', () => r(server.address().port)));

const target = (over = {}) => ({
  id: 'SIT', label: 'SIT', loginUrl: '', username: 'admin', password: 'pw', sessionCookie: 'splunkd_8000', ...over,
});

const splunkConfig = (over = {}) => ({
  enabled: true, sessionCookie: 'splunkd_8000',
  scriptCookieName: 'splunkd_8089', scriptCookiePath: '/', prefetchCval: true, connectTimeoutMs: 2000,
  readTimeoutMs: 2000, tlsInsecure: true, caFile: null, targets: [target()], defaultTarget: 'SIT', ...over,
});

describe('login client', () => {
  let server;
  let url;
  before(async () => {
    server = http.createServer(splunkStub(goodLogin));
    url = `http://127.0.0.1:${await listen(server)}/en-US/account/login`;
  });
  after(() => server.close());

  test('disabled returns a mock value and sends nothing', async () => {
    const r = await createLoginClient(splunkConfig({ enabled: false })).login(target({ loginUrl: 'http://127.0.0.1:1/' }));
    assert.match(r.cookieValue, /^mock-[0-9a-f]{64}$/);
    assert.equal(r.httpStatus, 0);
  });

  test('echoes cval, does not follow the 303, reads the cookie off it', async () => {
    const r = await createLoginClient(splunkConfig()).login(target({ loginUrl: url }));
    assert.deepEqual(r, { cookieValue: 'abc123', httpStatus: 303 });
  });

  test('wrong password is no_session_cookie with Splunk\'s 401', async () => {
    await assert.rejects(createLoginClient(splunkConfig()).login(target({ loginUrl: url, password: 'nope' })),
      { reason: 'no_session_cookie', httpStatus: 401 });
  });

  test('without cval Splunk answers 200 and no cookie', async () => {
    await assert.rejects(createLoginClient(splunkConfig({ prefetchCval: false })).login(target({ loginUrl: url })),
      { reason: 'no_session_cookie', httpStatus: 200 });
  });

  test('unreachable is io_error', async () => {
    await assert.rejects(createLoginClient(splunkConfig()).login(target({ loginUrl: 'http://127.0.0.1:1/x' })),
      { reason: 'io_error', httpStatus: 0 });
  });

  test("a target's own session cookie name is the one read", async () => {
    await assert.rejects(createLoginClient(splunkConfig()).login(target({ loginUrl: url, sessionCookie: 'splunkd_8443' })),
      { reason: 'no_session_cookie', message: /splunkd_8443/ });
  });

  test('this login may override the session cookie name', async () => {
    await assert.rejects(createLoginClient(splunkConfig()).login(target({ loginUrl: url }), { sessionCookie: 'splunkd_3000' }),
      { reason: 'no_session_cookie', message: /splunkd_3000/ });
  });

  test('via tunnel: the socket goes to the tunnel, Host stays the login URL\'s', async () => {
    const hosts = [];
    const tunnelled = http.createServer((req, res) => { hosts.push(req.headers.host); splunkStub(goodLogin)(req, res); });
    const port = await listen(tunnelled);
    try {
      const t = target({ loginUrl: 'http://uat-host.invalid:3000/en-US/account/login', tunnel: { host: '127.0.0.1', port } });
      assert.deepEqual(await createLoginClient(splunkConfig()).login(t, { viaTunnel: true }), { cookieValue: 'abc123', httpStatus: 303 });
      assert.deepEqual(hosts, ['uat-host.invalid:3000', 'uat-host.invalid:3000']);
      // Without the tunnel the same target is unreachable — and the error names the route taken.
      await assert.rejects(createLoginClient(splunkConfig()).login(t), { reason: 'io_error' });
      await assert.rejects(createLoginClient(splunkConfig()).login({ ...t, tunnel: { host: '127.0.0.1', port: 1 } }, { viaTunnel: true }),
        { reason: 'io_error', message: /via tunnel 127\.0\.0\.1:1/ });
    } finally {
      tunnelled.close();
    }
  });

  test('cookiesOf: later header wins, quotes stripped', () => {
    assert.deepEqual([...cookiesOf(['a=1; Path=/', 'b="2"', 'a=3'])], [['a', '3'], ['b', '2']]);
  });
});

describe('login client over https with a self-signed certificate', () => {
  let dir;
  let server;
  let url;
  let openssl = true;
  before(async () => {
    dir = mkdtempSync(join(tmpdir(), 'splunk-tls-'));
    try {
      execFileSync('openssl', ['req', '-x509', '-newkey', 'rsa:2048', '-nodes', '-days', '1', '-subj', '/CN=not-this-host',
        '-keyout', join(dir, 'key.pem'), '-out', join(dir, 'cert.pem')], { stdio: 'ignore' });
    } catch {
      openssl = false;
      return;
    }
    server = https.createServer({ key: readFileSync(join(dir, 'key.pem')), cert: readFileSync(join(dir, 'cert.pem')) },
      splunkStub(goodLogin));
    url = `https://127.0.0.1:${await listen(server)}/en-US/account/login`;
  });
  after(() => {
    server?.close();
    rmSync(dir, { recursive: true, force: true });
  });

  test('tlsInsecure skips chain and hostname verification', async (t) => {
    if (!openssl) return t.skip('openssl not available');
    const r = await createLoginClient(splunkConfig({ tlsInsecure: true })).login(target({ loginUrl: url }));
    assert.equal(r.cookieValue, 'abc123');
  });

  test('via tunnel over https: SNI names the real host, not the tunnel', async (t) => {
    if (!openssl) return t.skip('openssl not available');
    const names = [];
    const onConnection = (socket) => names.push(socket.servername);
    server.on('secureConnection', onConnection);
    try {
      const port = new URL(url).port;
      const r = await createLoginClient(splunkConfig({ tlsInsecure: true })).login(target({
        loginUrl: 'https://uat-host.example:3000/en-US/account/login', tunnel: { host: '127.0.0.1', port: Number(port) },
      }), { viaTunnel: true });
      assert.equal(r.cookieValue, 'abc123');
      assert.deepEqual(names, ['uat-host.example', 'uat-host.example']);
    } finally {
      server.off('secureConnection', onConnection);
    }
  });

  test('verification on rejects the same server', async (t) => {
    if (!openssl) return t.skip('openssl not available');
    await assert.rejects(createLoginClient(splunkConfig({ tlsInsecure: false })).login(target({ loginUrl: url })),
      { reason: 'io_error' });
  });
});

describe('broker', () => {
  const client = { ip: '10.0.0.1', forwardedFor: null, userAgent: 'test' };
  let now = 1111111111_000;
  const TARGETS = [
    target({ id: 'SIT', label: 'SIT', loginUrl: 'https://sit/login', username: 'sit-user', password: 'sit-pw' }),
    target({ id: 'UAT', label: 'UAT', loginUrl: 'https://uat/login', username: 'uat-user', password: 'uat-pw', sessionCookie: 'splunkd_8443' }),
  ];
  const setup = (splunkOver = {}, login = async () => ({ cookieValue: 'v"al', httpStatus: 303 })) => {
    const store = new MemoryAuditStore();
    const totp = new Totp(totpConfig(), () => now);
    const splunk = splunkConfig({ targets: TARGETS, ...splunkOver });
    const broker = createBroker({
      totp, splunk, store, now: () => now, log: { info() {}, warn() {} },
      loginClient: { mode: splunk.enabled ? 'real' : 'mock', login },
    });
    return { broker, store, totp };
  };

  test('issue: session, script and two audit rows sharing a correlation id', async () => {
    now += 60_000;
    const { broker, store, totp } = setup();
    const s = await broker.issue(totp.currentCode(), client);
    assert.equal(s.cookieName, 'splunkd_8089');
    assert.equal(s.script, script('splunkd_8089', 'v"al', '/'));
    assert.equal(s.script, 'document.cookie = "splunkd_8089=v\\"al; path=/; Secure; SameSite=Lax";');
    const rows = await store.list(10);
    assert.deepEqual(rows.map((r) => [r.eventType, r.outcome]), [['SPLUNK_LOGIN', 'SUCCESS'], ['OTP_VERIFY', 'SUCCESS']]);
    assert.equal(rows[0].correlationId, rows[1].correlationId);
    assert.equal(rows[0].cookieFingerprint, s.cookieFingerprint);
    assert.equal(JSON.stringify(rows).includes('v"al'), false, 'the cookie value is never audited');
  });

  test("the chosen target's URL and credentials are the ones logged in with; the default when none is named", async () => {
    now += 60_000;
    const seen = [];
    const { broker, store, totp } = setup({}, async (t) => { seen.push(t); return { cookieValue: 'x', httpStatus: 303 }; });
    const s = await broker.issue(totp.currentCode(), client, 'UAT');
    assert.equal(s.target, 'UAT');
    assert.equal(s.sourceCookie, 'splunkd_8443');
    assert.deepEqual([seen[0].loginUrl, seen[0].username, seen[0].password], ['https://uat/login', 'uat-user', 'uat-pw']);
    assert.equal((await store.list(1))[0].detail, 'as uat-user on UAT, splunkd_8443 -> splunkd_8089');
    now += 60_000;
    assert.equal((await broker.issue(totp.currentCode(), client)).target, 'SIT');
    assert.equal(seen[1].username, 'sit-user');
  });

  test('info lists the targets without any password', () => {
    const { broker } = setup({ targets: [TARGETS[0], { ...TARGETS[1], password: '' }] });
    const info = broker.info();
    assert.deepEqual(info.targets.map((t) => [t.id, t.username, t.passwordSet, t.configured]),
      [['SIT', 'sit-user', true, true], ['UAT', 'uat-user', false, false]]);
    assert.equal(info.defaultTarget, 'SIT');
    assert.equal(info.splunkConfigured, true);
    assert.equal(JSON.stringify(info).includes('-pw'), false);
  });

  test('an unknown target is a 400 and the code is NOT consumed', async () => {
    now += 60_000;
    const { broker, totp } = setup();
    const code = totp.currentCode();
    await assert.rejects(broker.issue(code, client, 'PROD'), { status: 400, error: 'unknown_target' });
    assert.equal(totp.verify(code).valid, true);
  });

  test('cookie names: the target\'s by default, the request\'s when given', async () => {
    now += 60_000;
    const seen = [];
    const { broker, totp } = setup({
      targets: [{ ...TARGETS[1], scriptCookieName: 'splunkd_3000', scriptCookiePath: '/en-US' }],
      defaultTarget: 'UAT',
    }, async (_t, o) => { seen.push(o); return { cookieValue: 'x', httpStatus: 303 }; });
    const s = await broker.issue(totp.currentCode(), client);
    assert.deepEqual([s.sourceCookie, s.cookieName, s.script],
      ['splunkd_8443', 'splunkd_3000', 'document.cookie = "splunkd_3000=x; path=/en-US; Secure; SameSite=Lax";']);
    assert.deepEqual(broker.info().targets.map((t) => [t.sessionCookie, t.scriptCookieName, t.tunnel]),
      [['splunkd_8443', 'splunkd_3000', null]]);
    now += 60_000;
    const o = await broker.issue(totp.currentCode(), client, 'UAT', { sessionCookie: 'splunkd_9000', scriptCookieName: 'splunkd_9001' });
    assert.deepEqual([o.sourceCookie, o.cookieName, seen[1].sessionCookie], ['splunkd_9000', 'splunkd_9001', 'splunkd_9000']);
  });

  test('a bad cookie name override is a 400 and the code is NOT consumed', async () => {
    now += 60_000;
    const { broker, totp } = setup();
    const code = totp.currentCode();
    await assert.rejects(broker.issue(code, client, 'SIT', { scriptCookieName: 'a"; alert(1)//' }), { status: 400, error: 'validation_failed' });
    assert.equal(totp.verify(code).valid, true);
  });

  test('tunnel: the target\'s default unless the request says, a 400 without one, the route audited', async () => {
    now += 60_000;
    const seen = [];
    const tunnelled = { ...TARGETS[1], tunnel: { host: 'server-host', port: 3000 }, tunnelDefault: true };
    const { broker, store, totp } = setup({ targets: [TARGETS[0], tunnelled] },
      async (_t, o) => { seen.push(o.viaTunnel); return { cookieValue: 'x', httpStatus: 303 }; });
    assert.equal(broker.info().targets[1].tunnel, 'server-host:3000');
    const s = await broker.issue(totp.currentCode(), client, 'UAT');
    assert.equal(s.tunnel, 'server-host:3000');
    assert.match((await store.list(1))[0].detail, /on UAT via tunnel server-host:3000/);
    now += 60_000;
    assert.equal((await broker.issue(totp.currentCode(), client, 'UAT', { viaTunnel: false })).tunnel, null);
    assert.deepEqual(seen, [true, false]);
    now += 60_000;
    const code = totp.currentCode();
    await assert.rejects(broker.issue(code, client, 'SIT', { viaTunnel: true }), { status: 400, error: 'no_tunnel' });
    assert.equal(totp.verify(code).valid, true);
  });

  test('Splunk not configured: 503 and the code is NOT consumed', async () => {
    now += 60_000;
    const { broker, totp } = setup({ targets: [target({ password: '' })] });
    const code = totp.currentCode();
    await assert.rejects(broker.issue(code, client), { status: 503, error: 'not_configured' });
    assert.equal(totp.verify(code).valid, true);
  });

  test('replay is 401 otp_replayed', async () => {
    now += 60_000;
    const { broker, totp } = setup();
    const code = totp.currentCode();
    await broker.issue(code, client);
    await assert.rejects(broker.issue(code, client), { status: 401, error: 'otp_replayed' });
  });

  test('lockout after maxFailures, and the window expires', async () => {
    now += 60_000;
    const { broker, store } = setup();
    for (let i = 0; i < 3; i++) await assert.rejects(broker.issue('000000', client), { status: 401, error: 'otp_invalid' });
    await assert.rejects(broker.issue('000000', client), { status: 429, headers: { 'Retry-After': '60' } });
    assert.equal((await store.list(1))[0].reason, 'locked_out');
    now += 61_000;
    await assert.rejects(broker.issue('000000', client), { status: 401 });
  });

  test('Splunk failure is 502 and keeps the OTP row', async () => {
    now += 60_000;
    const { SplunkLoginError } = await import('./login-client.js');
    const { broker, store, totp } = setup({}, async () => { throw new SplunkLoginError('no_session_cookie', 401, 'nope'); });
    await assert.rejects(broker.issue(totp.currentCode(), client), { status: 502, error: 'splunk_no_session_cookie' });
    const rows = await store.list(10);
    assert.deepEqual(rows.map((r) => [r.eventType, r.outcome, r.httpStatus]),
      [['SPLUNK_LOGIN', 'FAILURE', 401], ['OTP_VERIFY', 'SUCCESS', null]]);
  });

  test('an audit write failure fails the request', async () => {
    now += 60_000;
    const { broker, store, totp } = setup();
    store.save = async () => { throw new Error('db down'); };
    await assert.rejects(broker.issue(totp.currentCode(), client), /db down/);
  });

  test('currentCode is hidden when exposeCurrentCode is off', () => {
    const totp = new Totp(totpConfig({ exposeCurrentCode: false }));
    const broker = createBroker({ totp, splunk: splunkConfig(), store: new MemoryAuditStore(), loginClient: { mode: 'mock' } });
    assert.throws(() => broker.currentCode(), { status: 404, error: 'code_hidden' });
    assert.equal(broker.info().codeVisible, false);
  });
});
