import { resolve } from 'node:path';

/**
 * Every externally-varying value gets a `CREED_MOCK_*` env override with an inline fallback,
 * matching the repo-wide `${CREED_FOO:fallback}` convention on the Java side.
 */

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

const isDev = process.env.NODE_ENV !== 'production';

export interface AppConfig {
  readonly env: string;
  readonly isDev: boolean;
  readonly host: string;
  readonly port: number;
  readonly mocksDir: string;
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
  env: isDev ? 'development' : 'production',
  isDev,
  host: str('CREED_MOCK_HOST', '0.0.0.0'),
  port: int('CREED_MOCK_PORT', 3000),
  mocksDir: resolve(process.cwd(), str('CREED_MOCK_DIR', 'mocks')),
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
