import { randomUUID } from 'node:crypto';

/**
 * Response-body template compiler.
 *
 * The whole point of this file is that compilation happens ONCE, at mock-load time, not per
 * request. A body is walked into a tree of `Compiled` nodes; any subtree with no `{{ }}` tokens
 * collapses back to a plain value and is marked `static`. `prepareRoute` then pre-serialises a
 * fully-static body to a JSON string so the hot path does zero rendering and zero
 * `JSON.stringify` work — it just writes bytes.
 *
 * Unknown expressions throw here, at load time, rather than producing `undefined` at 3am.
 */

export interface TemplateContext {
  params: Record<string, unknown>;
  query: Record<string, unknown>;
  headers: Record<string, unknown>;
  body: unknown;
  /** Per-route monotonic counter, starts at 1 on the first request. */
  seq: number;
  requestId: string;
}

export type Renderer = (ctx: TemplateContext) => unknown;

export interface Compiled {
  /** True when the value contains no templates and can be reused verbatim. */
  readonly isStatic: boolean;
  /** Only meaningful when `isStatic`. */
  readonly value: unknown;
  readonly render: Renderer;
}

const TOKEN_RE = /\{\{\s*([^{}]+?)\s*\}\}/g;
/** A string that is *exactly* one token keeps the expression's native type (number stays number). */
const SOLE_TOKEN_RE = /^\{\{\s*([^{}]+?)\s*\}\}$/;

function staticNode(value: unknown): Compiled {
  return { isStatic: true, value, render: () => value };
}

function dynamicNode(render: Renderer): Compiled {
  return { isStatic: false, value: undefined, render };
}

// ------------------------------------------------------------------ expressions

/** Splits an expression into words, honouring single/double quoted segments. */
function splitArgs(expr: string): string[] {
  const out: string[] = [];
  let buf = '';
  let quote: string | null = null;
  for (const ch of expr) {
    if (quote) {
      if (ch === quote) quote = null;
      else buf += ch;
      continue;
    }
    if (ch === '"' || ch === "'") {
      quote = ch;
      continue;
    }
    if (ch === ' ' || ch === '\t') {
      if (buf) {
        out.push(buf);
        buf = '';
      }
      continue;
    }
    buf += ch;
  }
  if (buf) out.push(buf);
  return out;
}

function readPath(source: unknown, path: readonly string[]): unknown {
  let cursor: unknown = source;
  for (const key of path) {
    if (cursor === null || cursor === undefined) return undefined;
    if (typeof cursor !== 'object') return undefined;
    cursor = (cursor as Record<string, unknown>)[key];
  }
  return cursor;
}

function num(raw: string | undefined, what: string, expr: string): number {
  const parsed = Number(raw);
  if (!Number.isFinite(parsed)) {
    throw new Error(`template "{{${expr}}}": ${what} must be a number, got ${JSON.stringify(raw)}`);
  }
  return parsed;
}

const SOURCES = ['params', 'query', 'headers', 'body'] as const;
type SourceName = (typeof SOURCES)[number];

function compileExpression(expr: string): Renderer {
  const args = splitArgs(expr);
  const head = args[0];
  if (!head) throw new Error('template "{{}}": empty expression');

  switch (head) {
    case 'uuid':
      return () => randomUUID();
    case 'now':
      return () => new Date().toISOString();
    case 'today':
      return () => new Date().toISOString().slice(0, 10);
    case 'timestamp':
      return () => Date.now();
    case 'seq':
      return (ctx) => ctx.seq;
    case 'requestId':
      return (ctx) => ctx.requestId;
    case 'bool':
      return () => Math.random() < 0.5;
    case 'int': {
      const min = num(args[1], 'min', expr);
      const max = num(args[2], 'max', expr);
      const span = max - min + 1;
      return () => min + Math.floor(Math.random() * span);
    }
    case 'float': {
      const min = num(args[1], 'min', expr);
      const max = num(args[2], 'max', expr);
      const dp = args[3] === undefined ? 2 : num(args[3], 'decimals', expr);
      const span = max - min;
      const factor = 10 ** dp;
      return () => Math.round((min + Math.random() * span) * factor) / factor;
    }
    case 'pick': {
      const choices = args.slice(1);
      if (choices.length === 0) {
        throw new Error(`template "{{${expr}}}": pick needs at least one choice`);
      }
      return () => choices[Math.floor(Math.random() * choices.length)];
    }
    default: {
      // `params.id`, `query.page`, `headers.x-tenant`, `body.customer.name`
      const [source, ...path] = head.split('.');
      if (!SOURCES.includes(source as SourceName)) {
        throw new Error(
          `template "{{${expr}}}": unknown expression. Expected one of ` +
            `uuid|now|today|timestamp|seq|requestId|bool|int|float|pick, or a ` +
            `${SOURCES.join('|')} lookup such as "params.id".`,
        );
      }
      if (path.length === 0) {
        throw new Error(`template "{{${expr}}}": "${source}" needs a field, e.g. "${source}.id"`);
      }
      const from = source as SourceName;
      // headers arrive lowercased by node; be forgiving about the YAML author's casing.
      const lookup = from === 'headers' ? path.map((p) => p.toLowerCase()) : path;
      return (ctx) => readPath(ctx[from], lookup);
    }
  }
}

function compileString(raw: string): Compiled {
  TOKEN_RE.lastIndex = 0;
  if (!TOKEN_RE.test(raw)) return staticNode(raw);

  const sole = SOLE_TOKEN_RE.exec(raw);
  if (sole?.[1]) {
    // Exactly one token and nothing else: preserve the native type.
    const render = compileExpression(sole[1]);
    return dynamicNode(render);
  }

  // Interpolation: alternating literal / expression parts, concatenated as a string.
  const parts: Array<string | Renderer> = [];
  let cursor = 0;
  TOKEN_RE.lastIndex = 0;
  let match: RegExpExecArray | null;
  while ((match = TOKEN_RE.exec(raw)) !== null) {
    if (match.index > cursor) parts.push(raw.slice(cursor, match.index));
    parts.push(compileExpression(match[1] as string));
    cursor = match.index + match[0].length;
  }
  if (cursor < raw.length) parts.push(raw.slice(cursor));

  return dynamicNode((ctx) => {
    let out = '';
    for (const part of parts) {
      out += typeof part === 'string' ? part : stringify(part(ctx));
    }
    return out;
  });
}

function stringify(value: unknown): string {
  if (value === null || value === undefined) return '';
  if (typeof value === 'object') return JSON.stringify(value);
  return String(value);
}

// -------------------------------------------------------------------- directives

interface RepeatDirective {
  $repeat: unknown;
  $each: unknown;
}

function isRepeat(value: object): value is RepeatDirective {
  return '$repeat' in value;
}

function compileRepeat(node: RepeatDirective): Compiled {
  if (!('$each' in node)) {
    throw new Error('template: "$repeat" requires a sibling "$each" describing one element');
  }
  const each = compile(node.$each);

  const count = node.$repeat;
  if (typeof count === 'number') {
    if (!Number.isInteger(count) || count < 0) {
      throw new Error(`template: "$repeat" must be a non-negative integer, got ${count}`);
    }
    if (each.isStatic) {
      // Fixed length and a static element: the whole array is a constant.
      return staticNode(Array.from({ length: count }, () => each.value));
    }
    return dynamicNode((ctx) => renderRepeat(count, each, ctx));
  }

  if (count && typeof count === 'object' && 'min' in count && 'max' in count) {
    const { min, max } = count as { min: number; max: number };
    if (!Number.isInteger(min) || !Number.isInteger(max) || min < 0 || max < min) {
      throw new Error(`template: "$repeat" range must satisfy 0 <= min <= max, got ${min}..${max}`);
    }
    const span = max - min + 1;
    return dynamicNode((ctx) => renderRepeat(min + Math.floor(Math.random() * span), each, ctx));
  }

  throw new Error('template: "$repeat" must be an integer or a { min, max } object');
}

function renderRepeat(count: number, each: Compiled, ctx: TemplateContext): unknown[] {
  const out: unknown[] = new Array(count);
  for (let i = 0; i < count; i += 1) {
    // Each element sees its own index as `seq` so repeated rows get distinct values.
    out[i] = each.render({ ...ctx, seq: ctx.seq + i });
  }
  return out;
}

// ---------------------------------------------------------------------- compile

export function compile(node: unknown): Compiled {
  if (typeof node === 'string') return compileString(node);

  if (Array.isArray(node)) {
    const items = node.map(compile);
    if (items.every((item) => item.isStatic)) {
      return staticNode(items.map((item) => item.value));
    }
    return dynamicNode((ctx) => items.map((item) => item.render(ctx)));
  }

  if (node !== null && typeof node === 'object') {
    if (isRepeat(node)) return compileRepeat(node);

    const entries = Object.entries(node).map(([key, value]) => [key, compile(value)] as const);
    if (entries.every(([, value]) => value.isStatic)) {
      return staticNode(Object.fromEntries(entries.map(([key, value]) => [key, value.value])));
    }
    return dynamicNode((ctx) => {
      const out: Record<string, unknown> = {};
      for (const [key, value] of entries) out[key] = value.render(ctx);
      return out;
    });
  }

  return staticNode(node);
}
