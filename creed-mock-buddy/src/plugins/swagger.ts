import swagger from '@fastify/swagger';
import swaggerUi from '@fastify/swagger-ui';
import type { FastifyInstance } from 'fastify';
import { config } from '../config.js';
import { pkg } from '../version.js';

/**
 * Must be registered before any route: @fastify/swagger collects specs through an `onRoute`
 * hook, and onRoute only fires for routes added after the hook exists.
 */
export async function registerSwagger(app: FastifyInstance): Promise<void> {
  const tags = [
    { name: 'system', description: 'Health and readiness probes' },
    { name: 'admin', description: 'Runtime control: scenarios, reload, stats, state' },
    ...app.registry
      .loadedModules()
      .map((module) => ({
        name: module.definition.name,
        description: module.definition.description ?? `Mocks from ${module.source}`,
      })),
  ];

  const origins = [`http://localhost:${config.port}`];
  if (config.httpsPort !== 0) origins.push(`https://localhost:${config.httpsPort}`);
  // Relative first: whichever port served /docs is the default target, so the common case never
  // crosses origins. The absolute entries let you point the UI at the other listener.
  const servers = [
    ...origins.map((url) => ({ url, description: url.startsWith('https') ? 'HTTPS' : 'HTTP' })),
  ];

  await app.register(swagger, {
    openapi: {
      openapi: '3.1.0',
      info: {
        title: pkg.name,
        version: pkg.version,
        description:
          `${pkg.description}\n\n` +
          `Mock definitions are loaded from \`${config.mocksDir}\`. ` +
          `Switch behaviour at runtime with \`PUT ${config.adminPrefix}/scenario\`.`,
      },
      servers,
      tags,
    },
  });

  await app.register(swaggerUi, {
    routePrefix: config.docsPath,
    uiConfig: { docExpansion: 'list', deepLinking: true, displayRequestDuration: true },
    staticCSP: true,
    // The stock CSP has no connect-src, so it falls back to default-src 'self' and blocks "Try it
    // out" against the other listener (a different port is a different origin). Its
    // upgrade-insecure-requests would also rewrite http://localhost:<port> to https on the HTTPS
    // page, which the HTTP listener can't answer. CORS is already open (origin: true in app.ts).
    transformStaticCSP: (header) =>
      header
        .replace(/\s*upgrade-insecure-requests;?/, '')
        .concat(` connect-src 'self' ${origins.join(' ')};`),
  });
}
