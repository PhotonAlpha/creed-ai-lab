import { fileURLToPath } from 'node:url';
import type { FastifyInstance } from 'fastify';
import { buildApp } from '../src/app.js';

export const FIXTURE_MOCKS = fileURLToPath(new URL('./fixtures/mocks', import.meta.url));

/** Builds an in-process app over the fixture mocks. No port is bound; drive it with inject(). */
export async function buildTestApp(scenario = 'default'): Promise<FastifyInstance> {
  const app = await buildApp({
    mocksDir: FIXTURE_MOCKS,
    scenario,
    logger: false,
    docs: false,
  });
  await app.ready();
  return app;
}
