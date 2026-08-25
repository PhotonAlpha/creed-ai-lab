import type { FastifyInstance, FastifyReply, FastifyRequest, RouteShorthandOptions } from 'fastify';
import { config } from '../config.js';
import { ConflictError, type ListQuery, type Record_ } from './collection.js';
import type { DelaySpec } from './definition.js';
import { routeKey, type CollectionBinding, type MockRegistry, type PreparedRoute } from './registry.js';
import type { Compiled, TemplateContext } from './template.js';

const JSON_CONTENT_TYPE = 'application/json; charset=utf-8';
const PARAM_RE = /:([A-Za-z0-9_]+)/g;

const sleep = (ms: number) => new Promise<void>((done) => setTimeout(done, ms));

function resolveDelay(spec: DelaySpec | undefined): number {
  if (spec === undefined) return 0;
  if (typeof spec === 'number') return spec;
  return spec.min + Math.floor(Math.random() * (spec.max - spec.min + 1));
}

function templateContext(req: FastifyRequest, seq: number): TemplateContext {
  return {
    params: req.params as Record<string, unknown>,
    query: req.query as Record<string, unknown>,
    headers: req.headers as Record<string, unknown>,
    body: req.body,
    seq,
    requestId: req.id,
  };
}

function applyDynamicHeaders(
  reply: FastifyReply,
  entries: ReadonlyArray<readonly [string, Compiled]>,
  ctx: TemplateContext,
): void {
  for (const [name, compiled] of entries) {
    const value = compiled.render(ctx);
    // A lookup that missed — `{{params.tenant}}` on a path without :tenant — would otherwise be
    // sent as the literal string "undefined". Omitting the header is the honest answer.
    if (value === undefined || value === null) continue;
    reply.header(name, typeof value === 'object' ? JSON.stringify(value) : String(value));
  }
}

/** Docs-only schema. Deliberately no `response` schema — see the note in registerMockRoutes. */
function docSchema(
  tag: string,
  path: string,
  summary: string,
  description?: string,
): RouteShorthandOptions['schema'] {
  const names = [...path.matchAll(PARAM_RE)].map((m) => m[1] as string);
  const schema: Record<string, unknown> = { tags: [tag], summary };
  if (description) schema['description'] = description;
  if (names.length) {
    schema['params'] = {
      type: 'object',
      required: names,
      properties: Object.fromEntries(names.map((name) => [name, { type: 'string' }])),
    };
  }
  return schema as RouteShorthandOptions['schema'];
}

function describeVariants(app: FastifyInstance, key: string): string {
  const variants = app.registry.variantsOf(key);
  const lines = variants.map((variant) => {
    const label = variant.scenario ? `scenario \`${variant.scenario}\`` : 'default';
    const bits = [`→ ${variant.status}`];
    if (variant.delay !== undefined) bits.push(`delay ${JSON.stringify(variant.delay)}`);
    if (variant.fault) bits.push(`fault ${variant.fault.rate * 100}% → ${variant.fault.status}`);
    return `- **${label}** ${bits.join(', ')}`;
  });
  return lines.join('\n');
}

// ------------------------------------------------------------------ mock routes

export function registerMockRoutes(app: FastifyInstance): void {
  const registry = app.registry;
  const keys = registry.routeKeys();

  // A YAML file may declare HEAD explicitly for a path that also has GET. Fastify's automatic
  // HEAD-for-GET would then collide with it (FST_ERR_DUPLICATED_ROUTE) and refuse to boot.
  const explicitHeadPaths = new Set(
    keys.filter((key) => key.startsWith('HEAD ')).map((key) => key.slice('HEAD '.length)),
  );

  for (const key of keys) {
    const sample = registry.resolve(key) ?? registry.variantsOf(key)[0];
    if (!sample) continue;

    const options: RouteShorthandOptions = {
      schema: docSchema(
        sample.module,
        sample.path,
        sample.summary ?? `${sample.method} ${sample.path}`,
        [sample.description, describeVariants(app, key)].filter(Boolean).join('\n\n'),
      ),
    };
    if (sample.method === 'GET' && explicitHeadPaths.has(sample.path)) {
      options.exposeHeadRoute = false;
    }

    // NOTE: no `schema.response` on purpose. Fastify would compile it with fast-json-stringify
    // and silently drop any field the schema does not mention — for arbitrary user-authored mock
    // bodies that is a data-loss bug, not an optimisation. The static-payload path below is where
    // the serialisation win actually comes from.
    app.route({
      method: sample.method,
      url: sample.path,
      ...options,
      handler: makeMockHandler(registry, key),
    });
  }

  registry.markRegistered(keys);
}

function makeMockHandler(registry: MockRegistry, key: string) {
  return async function mockHandler(req: FastifyRequest, reply: FastifyReply) {
    req.mockRouteKey = key;

    const route = registry.resolve(key);
    if (!route) {
      // The path exists but the active scenario removed every variant for it.
      return reply.code(404).send({
        statusCode: 404,
        error: 'Not Found',
        message: `no variant of "${key}" is defined for scenario "${registry.scenario}"`,
        requestId: req.id,
      });
    }

    // Built at most once per request, on first use. A fully static route never touches the seq
    // counter, and a route with both templated headers and a templated body sees ONE seq value
    // across the two — rendering them from separate contexts would silently double-count.
    let ctx: TemplateContext | undefined;
    const context = (): TemplateContext => (ctx ??= templateContext(req, registry.nextSeq(key)));

    if (config.chaosEnabled) {
      const delay = resolveDelay(route.delay);
      if (delay > 0) await sleep(delay);

      if (route.fault && Math.random() < route.fault.rate) {
        registry.recordFault(key);
        req.log.warn({ route: route.id, status: route.fault.status }, 'injected fault');
        reply.code(route.fault.status).type(JSON_CONTENT_TYPE);
        return reply.send(
          route.fault.staticPayload ?? JSON.stringify(route.fault.body.render(context())),
        );
      }
    }

    reply.code(route.status);
    if (route.hasHeaders) reply.headers(route.headers);
    if (route.dynamicHeaders) applyDynamicHeaders(reply, route.dynamicHeaders, context());

    if (route.staticPayload !== undefined) {
      // Fast path: pre-serialised at load time, so this is a raw byte write.
      reply.type(JSON_CONTENT_TYPE);
      return reply.send(route.staticPayload);
    }
    if (!route.body) return reply.send();

    return reply.send(route.body.render(context()));
  };
}

// ------------------------------------------------------------------ collections

export function registerCollections(app: FastifyInstance): void {
  for (const binding of app.registry.collectionBindings()) {
    registerCollection(app, binding);
  }
}

function registerCollection(app: FastifyInstance, binding: CollectionBinding): void {
  const { path, store } = binding;
  const itemPath = `${path}/:id`;
  const tag = binding.module;

  const withDelay = async () => {
    if (!config.chaosEnabled) return;
    const delay = resolveDelay(binding.delay);
    if (delay > 0) await sleep(delay);
  };

  const notFound = (req: FastifyRequest, reply: FastifyReply, id: string) =>
    reply.code(404).send({
      statusCode: 404,
      error: 'Not Found',
      message: `${binding.name} "${id}" does not exist`,
      requestId: req.id,
    });

  const mark = (req: FastifyRequest, method: string, url: string) => {
    req.mockRouteKey = routeKey(method, url);
  };

  app.route({
    method: 'GET',
    url: path,
    schema: docSchema(
      tag,
      path,
      `List ${binding.name}`,
      'Supports `_page`, `_limit`, `_sort`, `_order`; any other query param filters on that field.',
    ),
    handler: async (req) => {
      mark(req, 'GET', path);
      await withDelay();
      return store.list(parseListQuery(req.query as Record<string, string>));
    },
  });

  app.route({
    method: 'GET',
    url: itemPath,
    schema: docSchema(tag, itemPath, `Get one ${binding.name}`),
    handler: async (req, reply) => {
      mark(req, 'GET', itemPath);
      await withDelay();
      const { id } = req.params as { id: string };
      const row = store.get(id);
      return row ?? notFound(req, reply, id);
    },
  });

  app.route({
    method: 'POST',
    url: path,
    schema: docSchema(tag, path, `Create ${binding.name}`),
    handler: async (req, reply) => {
      mark(req, 'POST', path);
      await withDelay();
      try {
        const created = store.create((req.body ?? {}) as Record_);
        return reply.code(201).send(created);
      } catch (cause) {
        if (cause instanceof ConflictError) {
          return reply.code(409).send({
            statusCode: 409,
            error: 'Conflict',
            message: cause.message,
            requestId: req.id,
          });
        }
        throw cause;
      }
    },
  });

  app.route({
    method: 'PUT',
    url: itemPath,
    schema: docSchema(tag, itemPath, `Replace ${binding.name}`),
    handler: async (req, reply) => {
      mark(req, 'PUT', itemPath);
      await withDelay();
      const { id } = req.params as { id: string };
      const row = store.replace(id, (req.body ?? {}) as Record_);
      return row ?? notFound(req, reply, id);
    },
  });

  app.route({
    method: 'PATCH',
    url: itemPath,
    schema: docSchema(tag, itemPath, `Update ${binding.name}`),
    handler: async (req, reply) => {
      mark(req, 'PATCH', itemPath);
      await withDelay();
      const { id } = req.params as { id: string };
      const row = store.patch(id, (req.body ?? {}) as Record_);
      return row ?? notFound(req, reply, id);
    },
  });

  app.route({
    method: 'DELETE',
    url: itemPath,
    schema: docSchema(tag, itemPath, `Delete ${binding.name}`),
    handler: async (req, reply) => {
      mark(req, 'DELETE', itemPath);
      await withDelay();
      const { id } = req.params as { id: string };
      if (!store.remove(id)) return notFound(req, reply, id);
      return reply.code(204).send();
    },
  });
}

const RESERVED_QUERY_KEYS = new Set(['_page', '_limit', '_sort', '_order']);

function parseListQuery(query: Record<string, string>): ListQuery {
  const filters: Record<string, string> = {};
  for (const [key, value] of Object.entries(query)) {
    if (!RESERVED_QUERY_KEYS.has(key)) filters[key] = String(value);
  }
  const page = Number(query['_page']);
  const limit = Number(query['_limit']);
  const result: ListQuery = { filters };
  if (Number.isFinite(page)) result.page = page;
  if (Number.isFinite(limit)) result.limit = limit;
  if (query['_sort']) result.sort = query['_sort'];
  if (query['_order'] === 'desc' || query['_order'] === 'asc') result.order = query['_order'];
  return result;
}
