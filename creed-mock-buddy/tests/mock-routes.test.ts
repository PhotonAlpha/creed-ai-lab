import type { FastifyInstance } from 'fastify';
import { afterAll, beforeAll, describe, expect, it } from 'vitest';
import { buildTestApp } from './helpers.js';

let app: FastifyInstance;

beforeAll(async () => {
  app = await buildTestApp();
});
afterAll(async () => {
  await app.close();
});

describe('static responses', () => {
  it('serves a pre-serialised body with the declared headers', async () => {
    const res = await app.inject({ method: 'GET', url: '/demo/static' });
    expect(res.statusCode).toBe(200);
    expect(res.headers['x-mock-flavour']).toBe('static');
    expect(res.headers['content-type']).toBe('application/json; charset=utf-8');
    expect(res.json()).toEqual({ ok: true, items: [1, 2, 3] });
  });

  it('reports the route as precomputed', async () => {
    const res = await app.inject({ method: 'GET', url: '/__admin/routes' });
    const route = (res.json() as Array<{ key: string; variants: Array<{ precomputed: boolean }> }>) //
      .find((r) => r.key === 'GET /demo/static');
    expect(route?.variants[0]?.precomputed).toBe(true);
  });
});

describe('templated response headers', () => {
  it('renders tokens in a header value while keeping the static body precomputed', async () => {
    const res = await app.inject({ method: 'GET', url: '/demo/headers/42' });
    expect(res.statusCode).toBe(200);
    expect(res.headers['x-mock-flavour']).toBe('static');
    expect(res.headers['x-entity-id']).toBe('42');
    expect(res.headers['location']).toBe('/demo/headers/42');
    expect(res.json()).toEqual({ ok: true });
  });

  it('omits a header whose lookup resolved to nothing', async () => {
    const res = await app.inject({ method: 'GET', url: '/demo/headers/42' });
    expect(res.headers['x-missing']).toBeUndefined();
  });

  it('renders the same seq into the header and the body of one request', async () => {
    const first = await app.inject({ method: 'GET', url: '/demo/seq-header' });
    const second = await app.inject({ method: 'GET', url: '/demo/seq-header' });
    expect(first.headers['x-seq']).toBe(String((first.json() as { n: number }).n));
    expect(second.headers['x-seq']).toBe(String((second.json() as { n: number }).n));
    expect((second.json() as { n: number }).n).toBe((first.json() as { n: number }).n + 1);
  });
});

describe('templated responses', () => {
  it('renders params, query and body into the response', async () => {
    const res = await app.inject({
      method: 'POST',
      url: '/demo/echo/abc?q=hello',
      payload: { a: { b: 'nested-value' } },
    });
    expect(res.statusCode).toBe(201);
    expect(res.json()).toEqual({
      id: 'abc',
      q: 'hello',
      nested: 'nested-value',
      greeting: 'hello abc',
    });
  });

  it('increments seq per route across requests', async () => {
    const first = await app.inject({ method: 'GET', url: '/demo/seq' });
    const second = await app.inject({ method: 'GET', url: '/demo/seq' });
    expect((second.json() as { n: number }).n).toBe((first.json() as { n: number }).n + 1);
  });
});

describe('fault injection', () => {
  it('always fails a rate-1 route with the declared status and body', async () => {
    for (let i = 0; i < 5; i += 1) {
      const res = await app.inject({ method: 'GET', url: '/demo/flaky' });
      expect(res.statusCode).toBe(503);
      expect(res.json()).toEqual({ error: 'down' });
    }
  });

  it('counts injected faults separately from hits', async () => {
    const res = await app.inject({ method: 'GET', url: '/__admin/stats' });
    const stats = (res.json() as { routes: Array<{ key: string; hits: number; faults: number }> }) //
      .routes.find((r) => r.key === 'GET /demo/flaky');
    expect(stats?.faults).toBeGreaterThanOrEqual(5);
  });
});

describe('scenarios', () => {
  it('serves the base variant by default', async () => {
    const res = await app.inject({ method: 'GET', url: '/demo/variant' });
    expect(res.json()).toEqual({ mode: 'default' });
  });

  it('swaps the variant when a scenario is activated, with no restart', async () => {
    await app.inject({ method: 'PUT', url: '/__admin/scenario', payload: { name: 'outage' } });

    const res = await app.inject({ method: 'GET', url: '/demo/variant' });
    expect(res.statusCode).toBe(503);
    expect(res.json()).toEqual({ mode: 'outage' });

    await app.inject({ method: 'PUT', url: '/__admin/scenario', payload: { name: 'default' } });
    const back = await app.inject({ method: 'GET', url: '/demo/variant' });
    expect(back.json()).toEqual({ mode: 'default' });
  });

  it('404s a scenario-only path while that scenario is inactive', async () => {
    const res = await app.inject({ method: 'GET', url: '/demo/only-in-outage' });
    expect(res.statusCode).toBe(404);
    expect((res.json() as { message: string }).message).toContain('scenario "default"');
  });

  it('serves the scenario-only path once the scenario is active', async () => {
    await app.inject({ method: 'PUT', url: '/__admin/scenario', payload: { name: 'outage' } });
    const res = await app.inject({ method: 'GET', url: '/demo/only-in-outage' });
    expect(res.statusCode).toBe(200);
    await app.inject({ method: 'PUT', url: '/__admin/scenario', payload: { name: 'default' } });
  });
});

describe('request context', () => {
  it('echoes an inbound correlation id instead of minting a new one', async () => {
    const res = await app.inject({
      method: 'GET',
      url: '/demo/static',
      headers: { 'x-request-id': 'caller-supplied' },
    });
    expect(res.headers['x-request-id']).toBe('caller-supplied');
  });

  it('extracts the trace id from a W3C traceparent header', async () => {
    const res = await app.inject({
      method: 'GET',
      url: '/demo/static',
      headers: { traceparent: '00-4bf92f3577b34da6a3ce929d0e0e4736-00f067aa0ba902b7-01' },
    });
    expect(res.headers['x-trace-id']).toBe('4bf92f3577b34da6a3ce929d0e0e4736');
  });

  it('ignores a malformed traceparent rather than failing the request', async () => {
    const res = await app.inject({
      method: 'GET',
      url: '/demo/static',
      headers: { traceparent: 'not-a-traceparent' },
    });
    expect(res.statusCode).toBe(200);
    expect(res.headers['x-trace-id']).toBeUndefined();
  });
});

describe('unmatched routes', () => {
  it('points at the admin route listing', async () => {
    const res = await app.inject({ method: 'GET', url: '/demo/nope' });
    expect(res.statusCode).toBe(404);
    expect((res.json() as { message: string }).message).toContain('/__admin/routes');
  });
});
