import type { FastifyInstance } from 'fastify';
import { afterAll, beforeAll, beforeEach, describe, expect, it } from 'vitest';
import { buildTestApp } from './helpers.js';

let app: FastifyInstance;

beforeAll(async () => {
  app = await buildTestApp();
});
afterAll(async () => {
  await app.close();
});
beforeEach(async () => {
  // Every test starts from the seed so ordering between cases cannot matter.
  await app.inject({ method: 'POST', url: '/__admin/state/reset' });
});

interface ListBody {
  items: Array<Record<string, unknown>>;
  total: number;
  page: number;
  limit: number;
}

describe('list', () => {
  it('returns every seeded row in insertion order', async () => {
    const body = (await app.inject({ method: 'GET', url: '/demo/widgets' })).json() as ListBody;
    expect(body.total).toBe(3);
    expect(body.items.map((row) => row['name'])).toEqual(['alpha', 'beta', 'gamma']);
  });

  it('filters on any top-level field', async () => {
    const body = (await app.inject({ method: 'GET', url: '/demo/widgets?group=a' })).json() as ListBody;
    expect(body.total).toBe(2);
    expect(body.items.map((row) => row['name'])).toEqual(['alpha', 'gamma']);
  });

  it('sorts and paginates', async () => {
    const body = (
      await app.inject({ method: 'GET', url: '/demo/widgets?_sort=name&_order=desc&_limit=2&_page=1' })
    ).json() as ListBody;
    expect(body.items.map((row) => row['name'])).toEqual(['gamma', 'beta']);
    expect(body.total).toBe(3);
    expect(body.limit).toBe(2);
  });

  it('reports total across pages, not just the returned slice', async () => {
    const body = (
      await app.inject({ method: 'GET', url: '/demo/widgets?_limit=1&_page=3' })
    ).json() as ListBody;
    expect(body.items).toHaveLength(1);
    expect(body.total).toBe(3);
  });
});

describe('create', () => {
  it('mints the next numeric id when the seed used numbers', async () => {
    const res = await app.inject({ method: 'POST', url: '/demo/widgets', payload: { name: 'delta' } });
    expect(res.statusCode).toBe(201);
    expect(res.json()).toMatchObject({ id: 4, name: 'delta' });
  });

  it('honours a caller-supplied id', async () => {
    const res = await app.inject({ method: 'POST', url: '/demo/widgets', payload: { id: 99, name: 'x' } });
    expect(res.json()).toMatchObject({ id: 99 });
  });

  it('409s on a duplicate id instead of silently overwriting', async () => {
    const res = await app.inject({ method: 'POST', url: '/demo/widgets', payload: { id: 1, name: 'dup' } });
    expect(res.statusCode).toBe(409);
    expect((res.json() as { message: string }).message).toContain('already exists');
  });
});

describe('update', () => {
  it('PUT replaces the whole row but keeps the URL id', async () => {
    const res = await app.inject({ method: 'PUT', url: '/demo/widgets/1', payload: { name: 'replaced' } });
    expect(res.json()).toEqual({ id: 1, name: 'replaced' });
  });

  it('PATCH merges into the existing row', async () => {
    const res = await app.inject({ method: 'PATCH', url: '/demo/widgets/1', payload: { name: 'patched' } });
    expect(res.json()).toEqual({ id: 1, name: 'patched', group: 'a' });
  });

  it('404s an update to a missing row', async () => {
    const res = await app.inject({ method: 'PATCH', url: '/demo/widgets/404', payload: { name: 'x' } });
    expect(res.statusCode).toBe(404);
  });
});

describe('delete and reset', () => {
  it('deletes with 204 and then 404s', async () => {
    expect((await app.inject({ method: 'DELETE', url: '/demo/widgets/1' })).statusCode).toBe(204);
    expect((await app.inject({ method: 'GET', url: '/demo/widgets/1' })).statusCode).toBe(404);
  });

  it('restores the seed on state reset', async () => {
    await app.inject({ method: 'DELETE', url: '/demo/widgets/1' });
    await app.inject({ method: 'POST', url: '/__admin/state/reset' });
    const body = (await app.inject({ method: 'GET', url: '/demo/widgets' })).json() as ListBody;
    expect(body.total).toBe(3);
  });

  it('does not let a mutation leak into the seed template', async () => {
    await app.inject({ method: 'PATCH', url: '/demo/widgets/1', payload: { name: 'mutated' } });
    await app.inject({ method: 'POST', url: '/__admin/state/reset' });
    const body = (await app.inject({ method: 'GET', url: '/demo/widgets/1' })).json();
    expect(body).toMatchObject({ name: 'alpha' });
  });
});
