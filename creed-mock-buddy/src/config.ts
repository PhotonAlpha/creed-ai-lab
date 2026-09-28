import { existsSync } from 'node:fs';
import { resolve } from 'node:path';

/**
 * Every externally-varying value gets a `CREED_MOCK_*` env override with an inline fallback,
 * matching the repo-wide `${CREED_FOO:fallback}` convention on the Java side.
 */

/**
 * Picks the startup environment from `--env <name>` / `--env=<name>`, else `CREED_MOCK_ENV`.
 * nodemon swallows unknown flags, so through it the flag needs its own `--`: `nodemon -- --env ms`
 * (that is what `npm run dev:ms` runs). `CREED_MOCK_ENV=ms npm run dev` needs no such care.
 */
function envName(): string {
  const argv = process.argv;
  const index = argv.findIndex((arg) => arg === '--env' || arg.startsWith('--env='));
  if (index >= 0) {
    const arg = argv[index] as string;
    return arg.includes('=') ? arg.slice('--env='.length) : (argv[index + 1] ?? '');
  }
  return process.env.CREED_MOCK_ENV ?? '';
}

/**
 * Loads `.env.<name>` then `.env` from the cwd. process.loadEnvFile never overwrites a variable
 * that is already set, so precedence falls out of the load order: shell > .env.<name> > .env.
 * Skipped under vitest so a developer's local .env can't change what the suite sees.
 */
function loadEnvFiles(): string {
  const name = envName();
  if (process.env.VITEST) return name;
  if (name !== '' && !/^[A-Za-z0-9_-]+$/.test(name)) {
    throw new Error(`--env / CREED_MOCK_ENV must be a plain name like "ms", got ${JSON.stringify(name)}`);
  }
  const named = name ? resolve(process.cwd(), `.env.${name}`) : undefined;
  if (named && !existsSync(named)) {
    // A typo in the env name would otherwise silently start with default data.
    throw new Error(`environment "${name}" requested but ${named} does not exist`);
  }
  for (const file of [named, resolve(process.cwd(), '.env')]) {
    if (file && existsSync(file)) process.loadEnvFile(file);
  }
  return name;
}

const envFile = loadEnvFiles();

function str(name: string, fallback: string): string {
  const raw = process.env[name];
  return raw === undefined || raw === '' ? fallback : raw;
}

function int(name: string, fallback: number): number {
  const raw = process.env[name];
  if (raw === undefined || raw === '') return fallback;
  const parsed = Number(raw);
  if (!Number.isFinite(parsed)) {
    throw new Error(`${name} must be a number, got ${JSON.stringify(raw)}`);
  }
  return parsed;
}

function bool(name: string, fallback: boolean): boolean {
  const raw = process.env[name];
  if (raw === undefined || raw === '') return fallback;
  return raw === 'true' || raw === '1' || raw === 'yes';
}

/** The value becomes a directory name, so anything that could climb out of it is rejected. */
function countryCode(raw: string): string {
  if (raw !== '' && !/^[A-Za-z0-9_-]+$/.test(raw)) {
    throw new Error(`CREED_MOCK_COUNTRY must be a plain code like "ms", got ${JSON.stringify(raw)}`);
  }
  return raw.toLowerCase();
}

const isDev = process.env.NODE_ENV !== 'production';

export interface AppConfig {
  /** The `--env` / CREED_MOCK_ENV name whose .env.<name> was loaded; '' for none. */
  readonly envFile: string;
  readonly env: string;
  readonly isDev: boolean;
  readonly host: string;
  readonly port: number;
  /** 0 disables the HTTPS listener. */
  readonly httpsPort: number;
  readonly tlsKeyPath: string;
  readonly tlsCertPath: string;
  readonly mocksDir: string;
  /** Lowercased; '' means serve `default/` only. */
  readonly country: string;
  readonly jsonMockDir: string;
  readonly adminPrefix: string;
  readonly defaultScenario: string;
  readonly chaosEnabled: boolean;
  readonly docsEnabled: boolean;
  readonly docsPath: string;
  readonly logLevel: string;
  readonly prettyLogs: boolean;
  readonly slowRequestMs: number;
  readonly bodyLimitBytes: number;
  readonly maxEventLoopDelayMs: number;
  readonly maxHeapUsedBytes: number;
}

export const config: AppConfig = {
  envFile,
  env: isDev ? 'development' : 'production',
  isDev,
  host: str('CREED_MOCK_HOST', '0.0.0.0'),
  port: int('CREED_MOCK_PORT', 5173),
  httpsPort: int('CREED_MOCK_HTTPS_PORT', 4000),
  // Relative to the cwd, which is the module dir under `npm run dev` / `npm start`.
  tlsKeyPath: resolve(process.cwd(), str('CREED_MOCK_TLS_KEY', '../.support/scripts/pki/creed-gateway.key')),
  tlsCertPath: resolve(process.cwd(), str('CREED_MOCK_TLS_CERT', '../.support/scripts/pki/creed-gateway.crt')),
  mocksDir: resolve(process.cwd(), str('CREED_MOCK_DIR', 'mocks')),
  country: countryCode(str('CREED_MOCK_COUNTRY', '')),
  // Relative to the cwd (the module dir under npm scripts), not to this file: tsup bundles src/
  // into dist/main.js, so an import.meta.url-relative path would point somewhere else after build.
  jsonMockDir: resolve(process.cwd(), str('CREED_MOCK_JSON_DIR', 'src/mock')),
  adminPrefix: str('CREED_MOCK_ADMIN_PREFIX', '/__admin'),
  defaultScenario: str('CREED_MOCK_SCENARIO', 'default'),
  // Global kill switch for delay + fault injection. Benchmarks and CI want this off.
  chaosEnabled: bool('CREED_MOCK_CHAOS', true),
  docsEnabled: bool('CREED_MOCK_DOCS', true),
  docsPath: str('CREED_MOCK_DOCS_PATH', '/docs'),
  logLevel: str('CREED_MOCK_LOG_LEVEL', isDev ? 'debug' : 'info'),
  prettyLogs: bool('CREED_MOCK_PRETTY_LOGS', isDev),
  slowRequestMs: int('CREED_MOCK_SLOW_MS', 500),
  bodyLimitBytes: int('CREED_MOCK_BODY_LIMIT', 5 * 1024 * 1024),
  // under-pressure thresholds. Deliberately generous: a mock server returning 503 because a
  // benchmark saturated it is a worse failure mode than a slow response.
  maxEventLoopDelayMs: int('CREED_MOCK_MAX_EVENT_LOOP_DELAY', 2000),
  maxHeapUsedBytes: int('CREED_MOCK_MAX_HEAP', 1024 * 1024 * 1024),
};
