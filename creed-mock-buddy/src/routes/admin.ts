import type { FastifyInstance } from 'fastify';
import { config } from '../config.js';

const TAGS = ['admin'];

/**
 * Runtime control plane. Mounted under CREED_MOCK_ADMIN_PREFIX (default `/__admin`) — the double
 * underscore keeps it out of the way of realistic mock paths.
 */
export async function adminRoutes(app: FastifyInstance): Promise<void> {
  const registry = app.registry;

  app.route({
    method: 'GET',
    url: '/modules',
    schema: { tags: TAGS, summary: 'List loaded mock definition files' },
    handler: async () =>
      registry.loadedModules().map((module) => ({
        name: module.definition.name,
        source: module.source,
        prefix: module.definition.prefix,
        description: module.definition.description,
        routes: module.definition.routes.length,
        collections: module.definition.collections.length,
      })),
  });

  app.route({
    method: 'GET',
    url: '/routes',
    schema: { tags: TAGS, summary: 'List every mock route and its scenario variants' },
    handler: async () =>
      registry.routeKeys().map((key) => {
        const active = registry.resolve(key);
        return {
          key,
          active: active?.scenario ?? null,
          variants: registry.variantsOf(key).map((variant) => ({
            id: variant.id,
            module: variant.module,
            scenario: variant.scenario ?? null,
            status: variant.status,
            delay: variant.delay ?? null,
            fault: variant.fault ? { rate: variant.fault.rate, status: variant.fault.status } : null,
            /** True when the body was pre-serialised at load time (the zero-work response path). */
            precomputed: variant.staticPayload !== undefined,
          })),
        };
      }),
  });

  app.route({
    method: 'GET',
    url: '/collections',
    schema: { tags: TAGS, summary: 'List auto-generated CRUD collections' },
    handler: async () =>
      registry.collectionBindings().map((binding) => ({
        key: binding.key,
        module: binding.module,
        name: binding.name,
        path: binding.path,
        idField: binding.store.idField,
        rows: binding.store.size(),
      })),
  });

  app.route({
    method: 'GET',
    url: '/scenario',
    schema: {
      tags: TAGS,
      summary: 'Current scenario',
      response: {
        200: {
          type: 'object',
          properties: {
            active: { type: 'string' },
            default: { type: 'string' },
            known: { type: 'array', items: { type: 'string' } },
          },
        },
      },
    },
    handler: async () => ({
      active: registry.scenario,
      default: registry.defaultScenario,
      known: registry.knownScenarios(),
    }),
  });

  app.route({
    method: 'PUT',
    url: '/scenario',
    schema: {
      tags: TAGS,
      summary: 'Switch the active scenario',
      description:
        'Routes carrying a matching `scenario:` take over immediately; routes without one keep ' +
        'serving their default variant. No restart, no reconnect.',
      body: {
        type: 'object',
        required: ['name'],
        additionalProperties: false,
        properties: { name: { type: 'string', minLength: 1 } },
      },
    },
    handler: async (req) => {
      const { name } = req.body as { name: string };
      const known = registry.knownScenarios();
      const previous = registry.scenario;
      registry.setScenario(name);
      req.log.info({ from: previous, to: name }, 'scenario switched');
      return {
        active: name,
        previous,
        known,
        // Not an error: a scenario with no variants simply means every route serves its default.
        matchedVariants: registry
          .routeKeys()
          .filter((key) => registry.resolve(key)?.scenario === name).length,
      };
    },
  });

  app.route({
    method: 'POST',
    url: '/reload',
    schema: {
      tags: TAGS,
      summary: 'Re-read mock definitions from disk',
      description:
        'Bodies, statuses, delays, faults and scenario variants update in place. Adding or ' +
        'removing a *path* needs a process restart, because Fastify freezes its router on ' +
        'listen() — `npm run dev` restarts for you on any change under mocks/.',
    },
    handler: async (req, reply) => {
      try {
        const result = registry.reload();
        if (result.pendingRestart.length > 0) {
          req.log.warn({ pendingRestart: result.pendingRestart }, 'reload needs a restart to apply');
        }
        req.log.info({ routes: result.routes }, 'mock definitions reloaded');
        return result;
      } catch (cause) {
        // Keep serving the previous definitions rather than dying on a typo.
        req.log.error({ err: cause }, 'reload rejected, keeping previous definitions');
        return reply.code(422).send({
          statusCode: 422,
          error: 'Unprocessable Entity',
          message: (cause as Error).message,
          requestId: req.id,
        });
      }
    },
  });

  app.route({
    method: 'GET',
    url: '/stats',
    schema: {
      tags: TAGS,
      summary: 'Per-route hit counts and timings',
      description: 'Counters are O(1) and unbounded-safe: no per-request sample is retained.',
    },
    handler: async () => ({
      scenario: registry.scenario,
      routes: registry.allStats().map((entry) => ({
        ...entry,
        avgMs: entry.hits > 0 ? Math.round((entry.totalMs / entry.hits) * 100) / 100 : 0,
        maxMs: Math.round(entry.maxMs * 100) / 100,
        totalMs: Math.round(entry.totalMs * 100) / 100,
      })),
    }),
  });

  app.route({
    method: 'DELETE',
    url: '/stats',
    schema: { tags: TAGS, summary: 'Reset counters' },
    handler: async (_req, reply) => {
      registry.resetStats();
      return reply.code(204).send();
    },
  });

  app.route({
    method: 'POST',
    url: '/state/reset',
    schema: {
      tags: TAGS,
      summary: 'Restore every collection to its seed data',
      description: "Use between test cases so one suite cannot see another suite's writes.",
    },
    handler: async () => {
      registry.resetState();
      return {
        reset: registry.collectionBindings().map((binding) => ({
          key: binding.key,
          rows: binding.store.size(),
        })),
      };
    },
  });

  app.route({
    method: 'GET',
    url: '/config',
    schema: { tags: TAGS, summary: 'Effective runtime configuration' },
    handler: async () => ({ ...config }),
  });
}
