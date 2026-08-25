import { AsyncLocalStorage } from 'node:async_hooks';
import type { FastifyServerOptions } from 'fastify';
import { config } from './config.js';

export interface RequestContext {
  readonly requestId: string;
  readonly traceId?: string;
  readonly spanId?: string;
}

/**
 * Entered once per request in the `onRequest` hook (see plugins/request-context.ts) so that any
 * code — including the pino `mixin` below — can reach the current trace ids without threading a
 * context object through every call site.
 */
export const requestStore = new AsyncLocalStorage<RequestContext>();

export function currentContext(): RequestContext | undefined {
  return requestStore.getStore();
}

export function buildLoggerOptions(): FastifyServerOptions['logger'] {
  return {
    level: config.logLevel,
    // Stamps every log line — including ones emitted deep inside a handler — with the active
    // trace ids. Without the ALS above this would silently return {} for all of them.
    mixin() {
      const ctx = requestStore.getStore();
      if (!ctx) return {};
      return ctx.traceId ? { traceId: ctx.traceId, spanId: ctx.spanId } : {};
    },
    serializers: {
      req(req) {
        return {
          method: req.method,
          url: req.url,
          reqId: req.id,
          remote: req.ip,
        };
      },
      res(res) {
        return { statusCode: res.statusCode };
      },
    },
    ...(config.prettyLogs
      ? {
          transport: {
            target: 'pino-pretty',
            options: {
              colorize: true,
              translateTime: 'HH:MM:ss.l',
              ignore: 'pid,hostname',
              messageFormat: '{if traceId}[{traceId}] {end}{msg}',
            },
          },
        }
      : {}),
  };
}
