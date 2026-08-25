import { randomUUID } from 'node:crypto';
import type { FastifyInstance, FastifyRequest } from 'fastify';
import { config } from '../config.js';
import { requestStore, type RequestContext } from '../logging.js';

/** `00-<32 hex trace id>-<16 hex span id>-<2 hex flags>` */
const TRACEPARENT_RE = /^00-([0-9a-f]{32})-([0-9a-f]{16})-[0-9a-f]{2}$/;

/**
 * Reuses an inbound `x-request-id` so a correlation id set by a gateway survives the hop into
 * the mock, and only mints one when the caller did not supply it.
 */
export function generateRequestId(req: { headers: Record<string, unknown> }): string {
  const inbound = req.headers['x-request-id'];
  if (typeof inbound === 'string' && inbound.length > 0 && inbound.length <= 200) return inbound;
  return randomUUID();
}

function readTrace(req: FastifyRequest): { traceId?: string; spanId?: string } {
  const header = req.headers['traceparent'];
  if (typeof header !== 'string') return {};
  const match = TRACEPARENT_RE.exec(header);
  if (!match) return {};
  return { traceId: match[1] as string, spanId: match[2] as string };
}

export function registerRequestContext(app: FastifyInstance): void {
  app.addHook('onRequest', (req, reply, done) => {
    const { traceId, spanId } = readTrace(req);
    req.traceId = traceId;
    req.spanId = spanId;

    reply.header('x-request-id', req.id);
    if (traceId) reply.header('x-trace-id', traceId);

    const context: RequestContext = { requestId: req.id, ...(traceId ? { traceId, spanId } : {}) };
    // Calling done() *inside* run() is what makes the context visible to every later hook, the
    // route handler and the pino mixin. Returning before done() would lose it immediately.
    requestStore.run(context, done);
  });

  app.addHook('onResponse', (req, reply, done) => {
    const elapsed = reply.elapsedTime;

    if (req.mockRouteKey) {
      app.registry.recordHit(req.mockRouteKey, elapsed, reply.statusCode);
    }

    if (elapsed > config.slowRequestMs) {
      req.log.warn(
        { url: req.url, method: req.method, ms: Math.round(elapsed), status: reply.statusCode },
        'slow request',
      );
    }
    done();
  });
}
