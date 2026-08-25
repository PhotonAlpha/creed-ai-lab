#!/usr/bin/env node
/**
 * Throughput benchmark. Spawns its own server on a throwaway port so it can control the two
 * variables that otherwise dominate the numbers:
 *
 *   CREED_MOCK_CHAOS=false     — injected delays would measure setTimeout, not the server
 *   CREED_MOCK_LOG_LEVEL=warn  — per-request pino output costs more than the handler itself
 *
 * The point of the comparison is the "precomputed" column: a mock whose body contains no
 * templates is serialised once at load time, so its request path does no rendering at all.
 *
 *   npm run bench
 *   BENCH_DURATION=20 BENCH_CONNECTIONS=200 npm run bench
 */
import { spawn } from 'node:child_process';
import { setTimeout as delay } from 'node:timers/promises';
import autocannon from 'autocannon';

const PORT = Number(process.env.BENCH_PORT ?? 18199);
const DURATION = Number(process.env.BENCH_DURATION ?? 10);
const CONNECTIONS = Number(process.env.BENCH_CONNECTIONS ?? 100);
const BASE = `http://127.0.0.1:${PORT}`;

const TARGETS = [
  { name: 'static body (precomputed)', path: '/api/catalog/categories' },
  { name: 'templated body', path: '/api/catalog/search?q=widget' },
  { name: 'templated + path param', path: '/api/catalog/products/7/detail' },
  { name: 'collection list', path: '/api/catalog/products' },
  { name: 'health probe', path: '/health' },
];

async function waitForServer(signal) {
  for (let attempt = 0; attempt < 100; attempt += 1) {
    if (signal.exited) throw new Error('server exited during startup');
    try {
      const res = await fetch(`${BASE}/health`);
      if (res.ok) return;
    } catch {
      /* not up yet */
    }
    await delay(150);
  }
  throw new Error(`server did not become healthy on ${BASE} within 15s`);
}

function startServer() {
  const child = spawn('npx', ['tsx', 'src/main.ts'], {
    env: {
      ...process.env,
      CREED_MOCK_PORT: String(PORT),
      CREED_MOCK_CHAOS: 'false',
      CREED_MOCK_LOG_LEVEL: 'warn',
      CREED_MOCK_PRETTY_LOGS: 'false',
      CREED_MOCK_DOCS: 'false',
      NODE_ENV: 'production',
    },
    stdio: ['ignore', 'inherit', 'inherit'],
  });
  const signal = { exited: false };
  child.on('exit', () => {
    signal.exited = true;
  });
  return { child, signal };
}

function fmt(n, digits = 0) {
  return n.toLocaleString('en-US', { minimumFractionDigits: digits, maximumFractionDigits: digits });
}

async function main() {
  const { child, signal } = startServer();
  const results = [];

  try {
    await waitForServer(signal);
    console.log(
      `\nbenchmarking ${BASE} — ${CONNECTIONS} connections, ${DURATION}s each, chaos off\n`,
    );

    for (const target of TARGETS) {
      process.stdout.write(`  ${target.name.padEnd(28)} `);
      const result = await autocannon({
        url: `${BASE}${target.path}`,
        connections: CONNECTIONS,
        duration: DURATION,
        pipelining: 1,
      });
      results.push({ target, result });
      process.stdout.write(`${fmt(result.requests.average)} req/s\n`);
    }
  } finally {
    child.kill('SIGTERM');
  }

  const header = ['route', 'req/s avg', 'p50 ms', 'p99 ms', 'bytes/s', 'non-2xx'];
  const rows = results.map(({ target, result }) => [
    target.name,
    fmt(result.requests.average),
    fmt(result.latency.p50, 2),
    fmt(result.latency.p99, 2),
    fmt(result.throughput.average),
    String(result.non2xx),
  ]);

  const widths = header.map((cell, index) =>
    Math.max(cell.length, ...rows.map((row) => row[index].length)),
  );
  const line = (cells) => cells.map((cell, i) => cell.padEnd(widths[i])).join('  ');

  console.log(`\n${line(header)}`);
  console.log(widths.map((width) => '-'.repeat(width)).join('  '));
  for (const row of rows) console.log(line(row));
  console.log();
}

main().catch((cause) => {
  console.error(cause);
  process.exit(1);
});
