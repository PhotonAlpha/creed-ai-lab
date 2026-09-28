import type { FastifyInstance, FastifyPluginAsync, RouteOptions } from 'fastify';

export type RouteFactory = (app: FastifyInstance) => RouteOptions[];

/**
 * Wraps a route factory as a plugin so it still goes through `app.register`: that keeps the
 * swagger onRoute ordering in app.ts intact and lets the caller pass `{ prefix }`.
 */
export function asPlugin(factory: RouteFactory): FastifyPluginAsync {
  return async (instance) => {
    for (const route of factory(instance)) instance.route(route);
  };
}
