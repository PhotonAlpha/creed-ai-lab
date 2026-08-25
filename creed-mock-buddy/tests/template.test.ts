import { describe, expect, it } from 'vitest';
import { compile, type TemplateContext } from '../src/mock/template.js';

const ctx = (over: Partial<TemplateContext> = {}): TemplateContext => ({
  params: { id: '42' },
  query: { q: 'shoes', page: '2' },
  headers: { 'x-tenant': 'acme' },
  body: { a: { b: 'deep' }, amount: 12.5 },
  seq: 1,
  requestId: 'req-1',
  ...over,
});

describe('static collapsing', () => {
  it('marks template-free values static and reuses them verbatim', () => {
    const node = compile({ a: 1, b: ['x', { c: true }], d: null });
    expect(node.isStatic).toBe(true);
    expect(node.value).toEqual({ a: 1, b: ['x', { c: true }], d: null });
  });

  it('marks a subtree dynamic as soon as one leaf has a token', () => {
    const node = compile({ a: 1, b: { c: '{{seq}}' } });
    expect(node.isStatic).toBe(false);
    expect(node.render(ctx())).toEqual({ a: 1, b: { c: 1 } });
  });

  it('keeps a fixed-length $repeat of a static element static', () => {
    const node = compile({ $repeat: 2, $each: { ok: true } });
    expect(node.isStatic).toBe(true);
    expect(node.value).toEqual([{ ok: true }, { ok: true }]);
  });
});

describe('token typing', () => {
  it('preserves the native type when the string is exactly one token', () => {
    expect(compile('{{seq}}').render(ctx({ seq: 7 }))).toBe(7);
    expect(typeof compile('{{timestamp}}').render(ctx())).toBe('number');
  });

  it('produces a string when a token is embedded in text', () => {
    expect(compile('n={{seq}}!').render(ctx({ seq: 7 }))).toBe('n=7!');
  });

  it('renders an absent lookup as an empty string when interpolated', () => {
    expect(compile('[{{query.missing}}]').render(ctx())).toBe('[]');
  });

  it('renders an absent lookup as undefined when it is the sole token', () => {
    expect(compile('{{query.missing}}').render(ctx())).toBeUndefined();
  });
});

describe('lookups', () => {
  it('reads params, query, headers and nested body paths', () => {
    const node = compile({
      id: '{{params.id}}',
      q: '{{query.q}}',
      tenant: '{{headers.X-Tenant}}',
      deep: '{{body.a.b}}',
      rid: '{{requestId}}',
    });
    expect(node.render(ctx())).toEqual({
      id: '42',
      q: 'shoes',
      tenant: 'acme',
      deep: 'deep',
      rid: 'req-1',
    });
  });
});

describe('generators', () => {
  it('keeps int within the inclusive range', () => {
    const node = compile('{{int 3 5}}');
    const seen = new Set<unknown>();
    for (let i = 0; i < 200; i += 1) seen.add(node.render(ctx()));
    expect([...seen].every((v) => typeof v === 'number' && v >= 3 && v <= 5)).toBe(true);
  });

  it('rounds float to the requested decimals', () => {
    const value = compile('{{float 1 2 1}}').render(ctx()) as number;
    expect(value).toBeGreaterThanOrEqual(1);
    expect(value).toBeLessThanOrEqual(2);
    expect(Math.round(value * 10)).toBeCloseTo(value * 10, 10);
  });

  it('picks only from the listed choices, honouring quotes', () => {
    const node = compile('{{pick "in stock" backorder}}');
    for (let i = 0; i < 50; i += 1) {
      expect(['in stock', 'backorder']).toContain(node.render(ctx()));
    }
  });

  it('gives each $repeat element its own seq', () => {
    const node = compile({ $repeat: 3, $each: { n: '{{seq}}' } });
    expect(node.render(ctx({ seq: 10 }))).toEqual([{ n: 10 }, { n: 11 }, { n: 12 }]);
  });
});

describe('compile-time failures', () => {
  it('rejects an unknown expression rather than rendering undefined', () => {
    expect(() => compile('{{nope}}')).toThrow(/unknown expression/);
  });

  it('rejects a source with no field', () => {
    expect(() => compile('{{params}}')).toThrow(/needs a field/);
  });

  it('rejects non-numeric int bounds', () => {
    expect(() => compile('{{int a b}}')).toThrow(/must be a number/);
  });

  it('rejects $repeat without $each', () => {
    expect(() => compile({ $repeat: 2 })).toThrow(/requires a sibling "\$each"/);
  });

  it('rejects an inverted $repeat range', () => {
    expect(() => compile({ $repeat: { min: 5, max: 1 }, $each: 1 })).toThrow(/min <= max/);
  });
});
