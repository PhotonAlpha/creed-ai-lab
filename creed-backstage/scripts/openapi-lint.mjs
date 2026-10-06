#!/usr/bin/env node
/**
 * `redocly lint` over the bundles — catalog/<module>/dist/openapi.yaml — with ./redocly.yaml
 * (`minimal`: structure errors fail, style rules warn).
 *
 *   corepack yarn openapi:lint                  every module
 *   corepack yarn openapi:lint creed-payment    only the named module(s)
 *
 * Lints the bundle, not the sources, because the bundle is what Swagger UI renders. Run
 * `openapi:build` first; a missing bundle is reported as a failure.
 */
import { spawnSync } from 'node:child_process';
import { existsSync } from 'node:fs';
import { join, relative } from 'node:path';
import { CATALOG, REPO, cli } from './lib/modules.mjs';

const REDOCLY = join(REPO, 'node_modules', '.bin', process.platform === 'win32' ? 'redocly.cmd' : 'redocly');

await cli(({ modules }) => {
  const files = modules.map((m) => join(CATALOG, m, 'dist', 'openapi.yaml'));
  const missing = files.filter((f) => !existsSync(f));
  for (const f of missing) console.error(`✗ ${relative(REPO, f)} is missing — run corepack yarn openapi:build`);
  const present = files.filter((f) => existsSync(f));
  if (present.length === 0) return { stale: 0, failed: missing.length };
  const run = spawnSync(REDOCLY, ['lint', '--config', join(REPO, 'redocly.yaml'), '--format', 'summary', ...present], {
    cwd: REPO,
    stdio: 'inherit',
  });
  return { stale: 0, failed: missing.length + (run.status === 0 ? 0 : 1) };
});
