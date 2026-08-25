import cors from '@fastify/cors';
import underPressure from '@fastify/under-pressure';
import Fastify, { type FastifyInstance, type FastifyServerOptions } from 'fastify';
import { config } from './config.js';
import { buildLoggerOptions } from './logging.js';
import { registerCollections, registerMockRoutes } from './mock/register.js';
import { MockRegistry } from './mock/registry.js';
import { registerErrorHandler } from './plugins/error-handler.js';
import { generateRequestId, registerRequestContext } from './plugins/request-context.js';
import { registerSwagger } from './plugins/swagger.js';
import { adminRoutes } from './routes/admin.js';
import { healthRoutes } from './routes/health.js';
import './types.js';

export interface BuildOptions {
  /** Overridden by tests to point at a fixture directory. */
  mocksDir?: string;
  scenario?: string;
  logger?: FastifyServerOptions['logger'];
  docs?: boolean;
}

export async function buildApp(options: BuildOptions = {}): Promise<FastifyInstance> {
  const registry = new MockRegistry(
    options.mocksDir ?? config.mocksDir,
    options.scenario ?? config.defaultScenario,
  );
  registry.load();

  const app = Fastify({
    logger: options.logger ?? buildLoggerOptions(),
    genReqId: generateRequestId,
    trustProxy: true,
    bodyLimit: config.bodyLimitBytes,
    routerOptions: {
      // A mock server exists to be forgiving about how callers spell the URL. Nested under
      // routerOptions because the top-level spelling is deprecated and goes away in fastify@6.
      ignoreTrailingSlash: true,
    },
  });

  app.decorate('registry', registry);

  registerRequestContext(app);
  registerErrorHandler(app);

  await app.register(cors, { origin: true, credentials: true });

  await app.register(underPressure, {
    maxEventLoopDelay: config.maxEventLoopDelayMs,
    maxHeapUsedBytes: config.maxHeapUsedBytes,
    // We serve /health and /ready ourselves so the payload can carry mock-specific fields.
    exposeStatusRoute: false,
    message: 'creed-mock-buddy is shedding load',
    retryAfter: 5,
  });

  // Ordering is load-bearing: @fastify/swagger hooks onRoute, which only sees routes registered
  // after it. Each `await app.register(...)` boots that plugin before the next line runs.
  if (options.docs ?? config.docsEnabled) {
    await registerSwagger(app);
  }

  await app.register(healthRoutes);
  await app.register(adminRoutes, { prefix: config.adminPrefix });
  await app.register(async (instance) => {
    registerMockRoutes(instance);
    registerCollections(instance);
  });

  return app;
}
