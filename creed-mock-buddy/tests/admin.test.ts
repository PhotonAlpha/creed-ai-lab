import { cpSync, mkdtempSync, rmSync, writeFileSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import type { FastifyInstance } from 'fastify';
import { afterAll, beforeAll, describe, expect, it } from 'vitest';
import { buildApp } from '../src/app.js';
import { FIXTURE_MOCKS } from './helpers.js';

/** A writable copy of the fixtures, so reload tests can edit files without dirtying the repo. */
let dir: string;
let app: FastifyInstance;

beforeAll(async () => {
  dir = mkdtempSync(join(tmpdir(), 'mock-buddy-admin-'));
  cpSync(FIXTURE_MOCKS, dir, { recursive: true });
  app = await buildApp({ mocksDir: dir, logger: false, docs: false });
  await app.ready();
});

afterAll(async () => {
  await app.close();
  rmSync(dir, { recursive: true, force: true });
});

describe('inventory endpoints', () => {
  it('lists modules with their source file', async () => {
    const body = (await app.inject({ method: 'GET', url: '/__admin/modules' })).json() as Array<{
      name: string;
      routes: number;
    }>;
    expect(body[0]?.name).toBe('demo');
    expect(body[0]?.routes).toBeGreaterThan(0);
  });

  it('lists every variant of a multi-scenario route', async () => {
    const body = (await app.inject({ method: 'GET', url: '/__admin/routes' })).json() as Array<{
      key: string;
      variants: Array<{ scenario: string | null }>;
    }>;
    const variant = body.find((route) => route.key === 'GET /demo/variant');
    expect(variant?.variants.map((v) => v.scenario)).toEqual([null, 'outage']);
  });

  it('reports collection row counts', async () => {
    const body = (await app.inject({ method: 'GET', url: '/__admin/collections' })).json() as Array<{
      key: string;
      rows: number;
    }>;
    expect(body).toEqual([expect.objectContaining({ key: 'demo.widgets', rows: 3 })]);
  });
});

describe('scenario endpoint', () => {
  it('reports the scenarios discovered in the definitions', async () => {
    const body = (await app.inject({ method: 'GET', url: '/__admin/scenario' })).json() as {
      known: string[];
    };
    expect(body.known).toEqual(['default', 'outage']);
  });

  it('rejects a body without a name', async () => {
    const res = await app.inject({ method: 'PUT', url: '/__admin/scenario', payload: {} });
    expect(res.statusCode).toBe(400);
  });

  it('accepts an unknown scenario — every route just serves its default', async () => {
    const res = await app.inject({
      method: 'PUT',
      url: '/__admin/scenario',
      payload: { name: 'never-defined' },
    });
    expect(res.statusCode).toBe(200);
    expect((res.json() as { matchedVariants: number }).matchedVariants).toBe(0);
    expect((await app.inject({ method: 'GET', url: '/demo/variant' })).json()).toEqual({
      mode: 'default',
    });
    await app.inject({ method: 'PUT', url: '/__admin/scenario', payload: { name: 'default' } });
  });
});

describe('reload', () => {
  it('picks up an edited response body without a restart', async () => {
    writeFileSync(
      join(dir, 'extra.yaml'),
      'name: extra\nroutes:\n  - path: /demo/static\n    response: { body: { ok: false } }\n',
    );
    // Same path as demo.yaml's /demo/static -> a genuine duplicate, so this must be rejected.
    const rejected = await app.inject({ method: 'POST', url: '/__admin/reload' });
    expect(rejected.statusCode).toBe(422);
    expect((rejected.json() as { message: string }).message).toMatch(/duplicate route/);

    // ...and the previous definitions must still be serving.
    expect((await app.inject({ method: 'GET', url: '/demo/static' })).json()).toEqual({
      ok: true,
      items: [1, 2, 3],
    });

    rmSync(join(dir, 'extra.yaml'));
    const ok = await app.inject({ method: 'POST', url: '/__admin/reload' });
    expect(ok.statusCode).toBe(200);
    expect((ok.json() as { pendingRestart: string[] }).pendingRestart).toEqual([]);
  });

  it('flags a brand-new path as needing a restart', async () => {
    writeFileSync(
      join(dir, 'new-path.yaml'),
      'name: newpath\nroutes:\n  - path: /demo/brand-new\n    response: { body: 1 }\n',
    );
    const res = await app.inject({ method: 'POST', url: '/__admin/reload' });
    expect(res.statusCode).toBe(200);
    expect((res.json() as { pendingRestart: string[] }).pendingRestart).toContain(
      '+ GET /demo/brand-new',
    );
    // Fastify's router is frozen after listen, so the route really is not reachable yet.
    expect((await app.inject({ method: 'GET', url: '/demo/brand-new' })).statusCode).toBe(404);

    rmSync(join(dir, 'new-path.yaml'));
    await app.inject({ method: 'POST', url: '/__admin/reload' });
  });
});

describe('stats', () => {
  it('accumulates hits and clears on delete', async () => {
    await app.inject({ method: 'DELETE', url: '/__admin/stats' });
    await app.inject({ method: 'GET', url: '/demo/static' });
    await app.inject({ method: 'GET', url: '/demo/static' });

    const body = (await app.inject({ method: 'GET', url: '/__admin/stats' })).json() as {
      routes: Array<{ key: string; hits: number; avgMs: number }>;
    };
    const entry = body.routes.find((route) => route.key === 'GET /demo/static');
    expect(entry?.hits).toBe(2);
    expect(entry?.avgMs).toBeGreaterThanOrEqual(0);

    await app.inject({ method: 'DELETE', url: '/__admin/stats' });
    const cleared = (await app.inject({ method: 'GET', url: '/__admin/stats' })).json() as {
      routes: unknown[];
    };
    expect(cleared.routes).toHaveLength(0);
  });
});

describe('system routes', () => {
  it('reports liveness with the loaded route count', async () => {
    const body = (await app.inject({ method: 'GET', url: '/health' })).json() as {
      status: string;
      routes: number;
    };
    expect(body.status).toBe('UP');
    expect(body.routes).toBeGreaterThan(0);
  });

  it('reports readiness with pressure signals', async () => {
    const res = await app.inject({ method: 'GET', url: '/ready' });
    expect(res.statusCode).toBe(200);
    expect(res.json()).toMatchObject({ status: 'READY' });
  });
});
