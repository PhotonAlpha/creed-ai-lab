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
      servers: [{ url: `http://localhost:${config.port}`, description: 'local' }],
      tags,
    },
  });

  await app.register(swaggerUi, {
    routePrefix: config.docsPath,
    uiConfig: { docExpansion: 'list', deepLinking: true, displayRequestDuration: true },
    staticCSP: true,
  });
}
