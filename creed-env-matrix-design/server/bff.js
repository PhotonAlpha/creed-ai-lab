/**
 * Env Matrix BFF — the process the frontend runs as outside `npm run dev`, and the only owner of the
 * Splunk session broker since it left creed-resource-env-matrix.
 *
 *   /api/env-matrix/splunk/*  answered here (server/splunk/), audit in memory, Postgres or MySQL (SPLUNK_AUDIT_STORE)
 *   /api/*                    reverse-proxied to creed-resource-env-matrix (ENV_MATRIX_API_TARGET)
 *   everything else           dist/ — the built SPA, index.html for unknown paths (client routing)
 *
 * The Splunk routes are not proxied anywhere: the Java service no longer has them.
 *
 *   npm run build && npm run bff          # :3002; secrets from env, *_FILE, or .env.server.local
 *
 * During `npm run dev`, Vite proxies /api/env-matrix/splunk here (VITE_SPLUNK_TARGET) and the rest
 * of /api straight to the backend; this process's own proxy and static serving go unused.
 */
import http from 'node:http';
import https from 'node:https';
import { createReadStream, readFileSync } from 'node:fs';
import { stat } from 'node:fs/promises';
import { dirname, extname, join, normalize, sep } from 'node:path';
import { fileURLToPath } from 'node:url';
import { createAuditStore } from './splunk/audit-store.js';
import { describe, loadConfig } from './splunk/config.js';
import { createSplunkBroker, handleSplunk } from './splunk/routes.js';

const HERE = dirname(fileURLToPath(import.meta.url));
const env = process.env;
const PORT = Number(env.BFF_PORT ?? 3002);
const HOST = env.BFF_HOST || undefined; // unset: every interface, as the Java service listened
const STATIC_DIR = env.BFF_STATIC_DIR ?? join(HERE, '..', 'dist');
const API_TARGET = new URL(env.ENV_MATRIX_API_TARGET ?? 'https://localhost:18095');
// Mirrors the Vite proxy's `secure: false`: the backend's certificate is issued by the local
// Creed-CA. Set false and point ENV_MATRIX_API_CA_FILE at the CA to verify it instead.
const API_INSECURE = !/^(false|0|no|off)$/i.test(env.ENV_MATRIX_API_INSECURE ?? 'true');
const API_CA = env.ENV_MATRIX_API_CA_FILE ? readFileSync(env.ENV_MATRIX_API_CA_FILE) : undefined;

const config = loadConfig(env);
const store = createAuditStore(config.audit);
const broker = createSplunkBroker(config, store);

// ------------------------------------------------------------------- proxy

const apiAgent = API_TARGET.protocol === 'https:'
  ? new https.Agent({ keepAlive: true, rejectUnauthorized: !API_INSECURE, ca: API_CA })
  : new http.Agent({ keepAlive: true });

/** RFC 9110 §7.6.1 — connection-scoped, never forwarded. */
const HOP_BY_HOP = ['connection', 'keep-alive', 'proxy-connection', 'transfer-encoding', 'te', 'trailer', 'upgrade'];

function proxy(req, res) {
  const headers = { ...req.headers };
  for (const h of HOP_BY_HOP) delete headers[h];
  const peer = req.socket.remoteAddress;
  headers['x-forwarded-for'] = headers['x-forwarded-for'] ? `${headers['x-forwarded-for']}, ${peer}` : peer;
  headers['x-forwarded-host'] = req.headers.host;
  headers['x-forwarded-proto'] = req.socket.encrypted ? 'https' : 'http';
  headers.host = API_TARGET.host;

  const upstream = (API_TARGET.protocol === 'https:' ? https : http).request({
    protocol: API_TARGET.protocol,
    hostname: API_TARGET.hostname,
    port: API_TARGET.port,
    method: req.method,
    path: req.url,
    headers,
    agent: apiAgent,
  }, (up) => {
    const out = { ...up.headers };
    for (const h of HOP_BY_HOP) delete out[h];
    res.writeHead(up.statusCode, out);
    up.pipe(res);
  });
  upstream.on('error', (e) => {
    console.error(`[bff] ${req.method} ${req.url} -> ${API_TARGET.origin} failed: ${e.message}`);
    if (!res.headersSent) {
      res.writeHead(502, { 'Content-Type': 'application/json; charset=utf-8' });
      res.end(JSON.stringify({ error: 'bad_gateway', message: `env-matrix backend unreachable at ${API_TARGET.origin}`, time: new Date().toISOString() }));
    } else {
      res.destroy(e);
    }
  });
  req.pipe(upstream);
}

// ------------------------------------------------------------------ static

const TYPES = {
  '.html': 'text/html; charset=utf-8', '.js': 'text/javascript; charset=utf-8', '.css': 'text/css; charset=utf-8',
  '.json': 'application/json', '.map': 'application/json', '.svg': 'image/svg+xml', '.png': 'image/png',
  '.ico': 'image/x-icon', '.woff2': 'font/woff2', '.woff': 'font/woff', '.txt': 'text/plain; charset=utf-8',
};

async function serveStatic(req, res, pathname) {
  if (req.method !== 'GET' && req.method !== 'HEAD') {
    res.writeHead(405, { Allow: 'GET, HEAD' });
    return res.end();
  }
  // normalize + prefix check: a `..` that escapes dist/ falls through to index.html, never to the disk.
  let file = normalize(join(STATIC_DIR, decodeURIComponent(pathname)));
  if (!file.startsWith(STATIC_DIR + sep)) file = join(STATIC_DIR, 'index.html');
  let info = await stat(file).catch(() => null);
  if (!info?.isFile()) {
    file = join(STATIC_DIR, 'index.html'); // client-side route
    info = await stat(file).catch(() => null);
    if (!info) {
      res.writeHead(404, { 'Content-Type': 'text/plain; charset=utf-8' });
      return res.end(`no build at ${STATIC_DIR} — run \`npm run build\` first`);
    }
  }
  res.writeHead(200, {
    'Content-Type': TYPES[extname(file)] ?? 'application/octet-stream',
    'Content-Length': info.size,
    // Vite fingerprints everything under assets/; index.html must always be revalidated to pick them up.
    'Cache-Control': file.includes(`${sep}assets${sep}`) ? 'public, max-age=31536000, immutable' : 'no-cache',
  });
  if (req.method === 'HEAD') return res.end();
  createReadStream(file).pipe(res);
}

// ------------------------------------------------------------------ server

const server = http.createServer(async (req, res) => {
  const url = new URL(req.url, 'http://bff');
  try {
    if (url.pathname.startsWith('/api/env-matrix/splunk/')) {
      await handleSplunk(broker, req, res, url.pathname.replace(/^\/api\/env-matrix/, ''), url.searchParams);
    } else if (url.pathname.startsWith('/api/')) {
      proxy(req, res);
    } else {
      await serveStatic(req, res, url.pathname);
    }
  } catch (e) {
    console.error('[bff] request failed', e);
    if (!res.headersSent) res.writeHead(500);
    res.end();
  }
});

console.log('[bff] config', JSON.stringify(describe(config)));
if (config.splunk.enabled && config.splunk.tlsInsecure) {
  console.warn(`[bff] SPLUNK_TLS_INSECURE: Splunk's certificate is not verified (${config.splunk.loginUrl})`);
}
if (config.audit.store === 'memory') {
  console.warn('[bff] SPLUNK_AUDIT_STORE=memory: the audit keeps the newest 500 rows and is lost on restart — set pg or mysql to persist it');
}
// pg / mysql: fail at startup, not on the first code — the broker refuses to issue a session it cannot audit.
// memory: a no-op.
await store.init();
server.listen(PORT, HOST, () => {
  console.log(`[bff] listening on http://${HOST ?? 'localhost'}:${PORT} — splunk=${config.splunk.enabled ? 'real' : 'mock'}, `
    + `audit=${config.audit.store}, /api -> ${API_TARGET.origin}, static=${STATIC_DIR}`);
});

for (const signal of ['SIGINT', 'SIGTERM']) {
  process.once(signal, () => {
    server.close();
    store.close().finally(() => process.exit(0));
  });
}
