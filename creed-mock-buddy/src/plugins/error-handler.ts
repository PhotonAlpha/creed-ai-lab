import type { FastifyError, FastifyInstance } from 'fastify';
import { config } from '../config.js';

interface ErrorBody {
  statusCode: number;
  error: string;
  message: string;
  requestId: string;
  stack?: string;
}

export function registerErrorHandler(app: FastifyInstance): void {
  app.setErrorHandler<FastifyError>((error, req, reply) => {
    const status = error.statusCode ?? 500;

    if (status >= 500) {
      req.log.error({ err: error, url: req.url }, 'request failed');
    } else {
      req.log.info({ err: error.message, url: req.url, status }, 'request rejected');
    }

    const body: ErrorBody = {
      statusCode: status,
      error: error.name === 'Error' ? httpName(status) : error.name,
      // Never leak an internal message to a caller in production; the log line above has it.
      message: status >= 500 && !config.isDev ? 'Internal Server Error' : error.message,
      requestId: req.id,
    };
    if (config.isDev && status >= 500 && error.stack) body.stack = error.stack;

    reply.code(status).send(body);
  });

  app.setNotFoundHandler((req, reply) => {
    reply.code(404).send({
      statusCode: 404,
      error: 'Not Found',
      message:
        `no mock is defined for ${req.method} ${req.url}. ` +
        `See ${config.adminPrefix}/routes for what is loaded.`,
      requestId: req.id,
    });
  });
}

function httpName(status: number): string {
  if (status >= 500) return 'Internal Server Error';
  if (status === 400) return 'Bad Request';
  if (status === 404) return 'Not Found';
  if (status === 409) return 'Conflict';
  if (status === 415) return 'Unsupported Media Type';
  return 'Error';
}
