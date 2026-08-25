import type { MockRegistry } from './mock/registry.js';

declare module 'fastify' {
  interface FastifyInstance {
    registry: MockRegistry;
  }

  interface FastifyRequest {
    /** Set by mock/collection handlers so the `onResponse` hook can attribute timings. */
    mockRouteKey?: string;
    traceId?: string;
    spanId?: string;
  }
}

export {};
