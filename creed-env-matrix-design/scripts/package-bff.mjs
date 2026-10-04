/**
 * Builds a self-contained, deployable BFF directory — `npm run package:bff` (run `npm run build` first).
 *
 *   release/env-matrix-bff/
 *   ├── dist/               the built SPA
 *   ├── server/bff.js
 *   ├── server/splunk/      without *.test.js
 *   ├── package.json        runtime-only: `pg` and `mysql2`, nothing else
 *   ├── package-lock.json
 *   └── .env.server.example
 *
 * Why a separate package.json: the project's own one lists react/antd/G6 under `dependencies` (Vite
 * bundles them into dist/), so `npm ci --omit=dev` on it would install ~all of them for a process
 * whose only runtime dependencies are the two database drivers.
 *
 *   RELEASE_DIR=/opt/env-matrix-bff npm run package:bff
 */
import { execFileSync } from 'node:child_process';
import { cpSync, existsSync, mkdirSync, readFileSync, rmSync, writeFileSync } from 'node:fs';
import { dirname, join, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';

const ROOT = resolve(dirname(fileURLToPath(import.meta.url)), '..');
const OUT = resolve(process.env.RELEASE_DIR ?? join(ROOT, 'release', 'env-matrix-bff'));
const project = JSON.parse(readFileSync(join(ROOT, 'package.json'), 'utf8'));

if (!existsSync(join(ROOT, 'dist', 'index.html'))) {
  console.error('dist/index.html not found — run `npm run build` first');
  process.exit(1);
}

rmSync(OUT, { recursive: true, force: true });
mkdirSync(join(OUT, 'server'), { recursive: true });
cpSync(join(ROOT, 'dist'), join(OUT, 'dist'), { recursive: true });
cpSync(join(ROOT, 'server', 'bff.js'), join(OUT, 'server', 'bff.js'));
cpSync(join(ROOT, 'server', 'splunk'), join(OUT, 'server', 'splunk'), {
  recursive: true,
  filter: (src) => !src.endsWith('.test.js'),
});
cpSync(join(ROOT, '.env.server.example'), join(OUT, '.env.server.example'));

writeFileSync(join(OUT, 'package.json'), `${JSON.stringify({
  name: 'env-matrix-bff',
  version: project.version,
  private: true,
  // The server is ES modules in .js files; without this Node parses them as CommonJS and fails on `import`.
  type: 'module',
  engines: project.engines,
  scripts: { start: 'node --env-file-if-exists=.env.server.local server/bff.js' },
  dependencies: { mysql2: project.dependencies.mysql2, pg: project.dependencies.pg },
}, null, 2)}\n`);

// Resolves the lockfile for the trimmed dependency set; --omit=dev is a no-op here but states intent.
execFileSync('npm', ['install', '--omit=dev', '--no-audit', '--no-fund'], { cwd: OUT, stdio: 'inherit' });
console.log(`\npackaged -> ${OUT}\n  cd ${OUT} && npm start`);
