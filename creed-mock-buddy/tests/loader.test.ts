import { mkdtempSync, rmSync, writeFileSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { afterEach, describe, expect, it } from 'vitest';
import { loadModules } from '../src/mock/loader.js';
import { MockRegistry } from '../src/mock/registry.js';
import { FIXTURE_MOCKS } from './helpers.js';

const dirs: string[] = [];

afterEach(() => {
  for (const dir of dirs.splice(0)) rmSync(dir, { recursive: true, force: true });
});

function withFiles(files: Record<string, string>): string {
  const dir = mkdtempSync(join(tmpdir(), 'mock-buddy-'));
  dirs.push(dir);
  for (const [name, content] of Object.entries(files)) writeFileSync(join(dir, name), content);
  return dir;
}

describe('loading', () => {
  it('loads the fixture directory', () => {
    const modules = loadModules(FIXTURE_MOCKS);
    expect(modules).toHaveLength(1);
    expect(modules[0]?.definition.name).toBe('demo');
  });

  it('ignores empty files', () => {
    const dir = withFiles({ 'empty.yaml': '', 'ok.yaml': 'name: ok\n' });
    expect(loadModules(dir)).toHaveLength(1);
  });

  it('explains where an unknown key came from', () => {
    const dir = withFiles({ 'bad.yaml': 'name: bad\nroots: []\n' });
    expect(() => loadModules(dir)).toThrow(/bad\.yaml: invalid mock definition/);
  });

  it('rejects a path that does not start with a slash', () => {
    const dir = withFiles({ 'bad.yaml': 'name: bad\nroutes:\n  - path: nope\n    response: {}\n' });
    expect(() => loadModules(dir)).toThrow(/path must start with/);
  });

  it('rejects two modules sharing a name', () => {
    const dir = withFiles({ 'a.yaml': 'name: dup\n', 'b.yaml': 'name: dup\n' });
    expect(() => loadModules(dir)).toThrow(/duplicate module name "dup"/);
  });

  it('names the missing directory instead of throwing ENOENT', () => {
    expect(() => loadModules(join(tmpdir(), 'definitely-not-here-9f3a'))).toThrow(
      /mock directory not found/,
    );
  });
});

describe('registry validation', () => {
  const build = (files: Record<string, string>) => {
    const registry = new MockRegistry(withFiles(files), 'default');
    registry.load();
    return registry;
  };

  it('rejects two default variants of the same method and path', () => {
    expect(() =>
      build({
        'a.yaml':
          'name: a\nroutes:\n' +
          '  - path: /x\n    response: { body: 1 }\n' +
          '  - path: /x\n    response: { body: 2 }\n',
      }),
    ).toThrow(/duplicate route "GET \/x"/);
  });

  it('rejects two variants of the same scenario', () => {
    expect(() =>
      build({
        'a.yaml':
          'name: a\nroutes:\n' +
          '  - path: /x\n    scenario: s\n    response: { body: 1 }\n' +
          '  - path: /x\n    scenario: s\n    response: { body: 2 }\n',
      }),
    ).toThrow(/for scenario "s"/);
  });

  it('rejects a route that shadows a generated collection path', () => {
    expect(() =>
      build({
        'a.yaml':
          'name: a\ncollections:\n  - name: things\n    path: /things\n' +
          'routes:\n  - path: /things\n    response: { body: [] }\n',
      }),
    ).toThrow(/collides with collection/);
  });

  it('surfaces a bad template with the route it came from', () => {
    expect(() =>
      build({ 'a.yaml': 'name: a\nroutes:\n  - path: /x\n    response: { body: "{{bogus}}" }\n' }),
    ).toThrow(/route "GET \/x" has an invalid response body template/);
  });

  it('surfaces a bad template in a response header with the header name', () => {
    expect(() =>
      build({
        'a.yaml':
          'name: a\nroutes:\n  - path: /x\n    response:\n      headers: { location: "{{bogus}}" }\n      body: 1\n',
      }),
    ).toThrow(/route "GET \/x" has an invalid template in response header "location"/);
  });

  it('inherits the module delay when a route does not set one', () => {
    const registry = build({
      'a.yaml': 'name: a\ndelay: 25\nroutes:\n  - path: /x\n    response: { body: 1 }\n',
    });
    expect(registry.resolve('GET /x')?.delay).toBe(25);
  });

  it('lets a route override the module delay', () => {
    const registry = build({
      'a.yaml': 'name: a\ndelay: 25\nroutes:\n  - path: /x\n    delay: 0\n    response: { body: 1 }\n',
    });
    expect(registry.resolve('GET /x')?.delay).toBe(0);
  });

  it('strips the trailing slash a prefix + "/" join would produce', () => {
    const registry = build({
      'a.yaml': 'name: a\nprefix: /api\nroutes:\n  - path: /\n    response: { body: 1 }\n',
    });
    expect(registry.routeKeys()).toEqual(['GET /api']);
  });
});
