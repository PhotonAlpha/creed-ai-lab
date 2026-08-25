import { z } from 'zod';

/**
 * Schema for a mock definition file (`mocks/*.yaml`). Validation runs at load time and the
 * process refuses to start on a bad file — a mock server that silently ignores half its config
 * is worse than one that won't boot.
 */

export type JsonValue = string | number | boolean | null | JsonValue[] | { [key: string]: JsonValue };

const jsonValue: z.ZodType<JsonValue> = z.lazy(() =>
  z.union([
    z.string(),
    z.number(),
    z.boolean(),
    z.null(),
    z.array(jsonValue),
    z.record(z.string(), jsonValue),
  ]),
);

export const HTTP_METHODS = ['GET', 'POST', 'PUT', 'PATCH', 'DELETE', 'HEAD', 'OPTIONS'] as const;
export type HttpMethod = (typeof HTTP_METHODS)[number];

/** Fixed milliseconds, or a uniform range. */
export const delaySchema = z.union([
  z.number().int().nonnegative(),
  z
    .strictObject({
      min: z.number().int().nonnegative(),
      max: z.number().int().nonnegative(),
    })
    .refine((d) => d.max >= d.min, { message: 'delay.max must be >= delay.min' }),
]);
export type DelaySpec = z.infer<typeof delaySchema>;

export const faultSchema = z.strictObject({
  /** 0..1 — probability that this request fails instead of returning the normal response. */
  rate: z.number().min(0).max(1),
  status: z.number().int().min(100).max(599).default(500),
  body: jsonValue.optional(),
});
export type FaultSpec = z.infer<typeof faultSchema>;

export const responseSchema = z.strictObject({
  status: z.number().int().min(100).max(599).default(200),
  headers: z.record(z.string(), z.string()).default({}),
  body: jsonValue.optional(),
});

export const routeSchema = z.strictObject({
  /** Stable handle used in logs, stats and the admin API. Defaults to "METHOD path". */
  id: z.string().min(1).optional(),
  method: z.enum(HTTP_METHODS).default('GET'),
  path: z.string().startsWith('/', 'path must start with "/"'),
  /** When set, this variant is only served while the named scenario is active. */
  scenario: z.string().min(1).optional(),
  summary: z.string().optional(),
  description: z.string().optional(),
  delay: delaySchema.optional(),
  fault: faultSchema.optional(),
  response: responseSchema,
});
export type RouteDefinition = z.infer<typeof routeSchema>;

export const collectionSchema = z.strictObject({
  name: z.string().min(1),
  path: z.string().startsWith('/', 'path must start with "/"'),
  /** Field carrying the identity used by /:id routes. */
  idField: z.string().min(1).default('id'),
  delay: delaySchema.optional(),
  seed: z.array(z.record(z.string(), jsonValue)).default([]),
});
export type CollectionDefinition = z.infer<typeof collectionSchema>;

export const moduleSchema = z.strictObject({
  name: z.string().min(1),
  description: z.string().optional(),
  prefix: z
    .string()
    .default('')
    .refine((p) => p === '' || p.startsWith('/'), { message: 'prefix must be "" or start with "/"' })
    .refine((p) => !p.endsWith('/'), { message: 'prefix must not end with "/"' }),
  /** Module-wide default delay, overridden per route. */
  delay: delaySchema.optional(),
  collections: z.array(collectionSchema).default([]),
  routes: z.array(routeSchema).default([]),
});
export type ModuleDefinition = z.infer<typeof moduleSchema>;

/** Flattens a ZodError into lines a human can act on without reading a stack trace. */
export function formatIssues(error: z.ZodError): string {
  return error.issues
    .map((issue) => {
      const path = issue.path.length ? issue.path.join('.') : '<root>';
      return `  - ${path}: ${issue.message}`;
    })
    .join('\n');
}
