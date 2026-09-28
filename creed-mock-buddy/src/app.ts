import cors from '@fastify/cors';
import underPressure from '@fastify/under-pressure';
import { readFileSync } from 'node:fs';
import { createServer, type Server } from 'node:https';
import Fastify, { type FastifyInstance, type FastifyServerOptions } from 'fastify';
import { config } from './config.js';
import { buildLoggerOptions } from './logging.js';
import { registerCollections, registerMockRoutes } from './mock/register.js';
import { MockRegistry } from './mock/registry.js';
import { registerErrorHandler } from './plugins/error-handler.js';
import { generateRequestId, registerRequestContext } from './plugins/request-context.js';
import { registerSwagger } from './plugins/swagger.js';
import { adminRoutes } from './routes/admin.js';
import { asPlugin } from './routes/as-plugin.js';
import { commonRoutes } from './routes/common-service.js';
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
    // bodyLimit: config.bodyLimitBytes,
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

  await app.register(asPlugin(commonRoutes));
  await app.register(asPlugin(healthRoutes));
  await app.register(asPlugin(adminRoutes), { prefix: config.adminPrefix });
  await app.register(async (instance) => {
    registerMockRoutes(instance);
    registerCollections(instance);
  });

  return app;
}

/**
 * Serves the same instance over HTTPS on a second port. A second buildApp() would get its own
 * MockRegistry, so a scenario switch or a collection write on one port would be invisible on the
 * other — instead the TLS server hands every request to `app.routing`, the same router + hook chain
 * the HTTP listener uses. Call after `app.listen()`: routing is only usable once the app is ready.
 *
 * Missing TLS material skips the listener with a warning rather than failing startup — the HTTP
 * port must keep working for anyone who never ran the PKI script.
 */
export async function listenHttps(app: FastifyInstance): Promise<Server | undefined> {
  if (config.httpsPort === 0) return undefined;

  let tls: { key: Buffer; cert: Buffer };
  try {
    tls = { key: readFileSync(config.tlsKeyPath), cert: readFileSync(config.tlsCertPath) };
  } catch (cause) {
    app.log.warn(
      { err: cause, key: config.tlsKeyPath, cert: config.tlsCertPath },
      'HTTPS listener skipped: TLS material not readable (run .support/scripts/CA-Generation.sh, ' +
        'or set CREED_MOCK_HTTPS_PORT=0 to silence this)',
    );
    return undefined;
  }

  await app.ready();
  const server = createServer(tls, (req, res) => app.routing(req, res));
  await new Promise<void>((resolve, reject) => {
    server.once('error', reject);
    server.listen(config.httpsPort, config.host, () => {
      server.off('error', reject);
      resolve();
    });
  });
  return server;
}

/** close() alone waits on idle keep-alive sockets until they time out. */
export function closeServer(server: Server): Promise<void> {
  return new Promise((resolve, reject) => {
    server.close((cause) => (cause ? reject(cause) : resolve()));
    server.closeIdleConnections();
  });
}
