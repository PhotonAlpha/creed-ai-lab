import type { FastifyInstance } from 'fastify';
import { config } from '../config.js';
import { pkg } from '../version.js';

const startedAt = Date.now();

const readyState = {
  type: 'object',
  properties: {
    status: { type: 'string' },
    eventLoopDelayMs: { type: 'number' },
    eventLoopUtilization: { type: 'number' },
    heapUsedBytes: { type: 'number' },
    rssBytes: { type: 'number' },
  },
} as const;

export async function healthRoutes(app: FastifyInstance): Promise<void> {
  app.route({
    method: 'GET',
    url: '/health',
    schema: {
      tags: ['system'],
      summary: 'Liveness probe',
      response: {
        200: {
          type: 'object',
          properties: {
            status: { type: 'string' },
            name: { type: 'string' },
            version: { type: 'string' },
            scenario: { type: 'string' },
            uptimeSeconds: { type: 'number' },
            routes: { type: 'integer' },
            collections: { type: 'integer' },
          },
        },
      },
    },
    handler: async () => ({
      status: 'UP',
      name: pkg.name,
      version: pkg.version,
      scenario: app.registry.scenario,
      uptimeSeconds: Math.round((Date.now() - startedAt) / 1000),
      routes: app.registry.routeKeys().length,
      collections: app.registry.collectionBindings().length,
    }),
  });

  app.route({
    method: 'GET',
    url: '/ready',
    schema: {
      tags: ['system'],
      summary: 'Readiness probe — reports the pressure signals used to shed load',
      response: {
        // 503 must be declared too, or Fastify's typings narrow reply.code() to 200 only.
        200: readyState,
        503: readyState,
      },
    },
    handler: async (_req, reply) => {
      const usage = app.memoryUsage();
      // under-pressure flips this the moment a threshold is breached; surfacing it here means a
      // load test can see the server going unhealthy before it starts returning 503s.
      const healthy = usage.eventLoopDelay < config.maxEventLoopDelayMs;
      return reply.code(healthy ? 200 : 503).send({
        status: healthy ? 'READY' : 'OVERLOADED',
        eventLoopDelayMs: Math.round(usage.eventLoopDelay * 100) / 100,
        eventLoopUtilization: Math.round((usage.eventLoopUtilized ?? 0) * 1000) / 1000,
        heapUsedBytes: usage.heapUsed,
        rssBytes: usage.rssBytes,
      });
    },
  });
}
