import { CollectionStore } from './collection.js';
import type {
  CollectionDefinition,
  DelaySpec,
  HttpMethod,
  ModuleDefinition,
  RouteDefinition,
} from './definition.js';
import { loadModules, type LoadedModule } from './loader.js';
import { compile, type Compiled, type TemplateContext } from './template.js';

export interface PreparedFault {
  readonly rate: number;
  readonly status: number;
  readonly body: Compiled;
  readonly staticPayload?: string;
}

export interface PreparedRoute {
  readonly id: string;
  readonly key: string;
  readonly module: string;
  readonly method: HttpMethod;
  readonly path: string;
  readonly scenario?: string;
  readonly summary?: string;
  readonly description?: string;
  readonly status: number;
  /** Header values that contained no templates — applied verbatim, in one `reply.headers()` call. */
  readonly headers: Record<string, string>;
  readonly hasHeaders: boolean;
  /** Only the header values that *do* contain templates; absent when none do. */
  readonly dynamicHeaders?: ReadonlyArray<readonly [string, Compiled]>;
  readonly delay?: DelaySpec;
  readonly fault?: PreparedFault;
  readonly body?: Compiled;
  /**
   * Set when the body contains no templates: the JSON is serialised once at load time and the
   * request path writes this string straight to the socket.
   */
  readonly staticPayload?: string;
}

interface VariantGroup {
  readonly method: HttpMethod;
  readonly path: string;
  base?: PreparedRoute;
  readonly byScenario: Map<string, PreparedRoute>;
}

export interface RouteStats {
  key: string;
  id: string;
  module: string;
  hits: number;
  faults: number;
  totalMs: number;
  maxMs: number;
  lastAt?: string;
  lastStatus?: number;
}

export interface CollectionBinding {
  readonly key: string;
  readonly module: string;
  readonly name: string;
  readonly path: string;
  readonly delay?: DelaySpec;
  readonly store: CollectionStore;
}

export interface ReloadResult {
  modules: number;
  routes: number;
  collections: number;
  /** Paths that appeared or vanished since boot. Fastify's router is frozen once listening. */
  pendingRestart: string[];
}

export function routeKey(method: string, path: string): string {
  return `${method} ${path}`;
}

function joinPath(prefix: string, path: string): string {
  const joined = `${prefix}${path}`;
  return joined.length > 1 && joined.endsWith('/') ? joined.slice(0, -1) : joined;
}

/**
 * Everything mutable about a running mock server: which variants exist, which scenario is
 * active, collection state and per-route counters.
 *
 * Route handlers hold only a `key` and consult this object per request, so `reload()` can swap
 * the whole variant table underneath a live server without touching Fastify's router.
 */
export class MockRegistry {
  private modules: LoadedModule[] = [];
  private variants = new Map<string, VariantGroup>();
  private collections = new Map<string, CollectionBinding>();
  private readonly stats = new Map<string, RouteStats>();
  private readonly seqCounters = new Map<string, number>();
  /** (method, path) pairs actually registered with Fastify — frozen after listen(). */
  private registeredKeys = new Set<string>();

  private activeScenario: string;
  private readonly baseScenario: string;

  constructor(
    private readonly dir: string,
    defaultScenario: string,
  ) {
    this.baseScenario = defaultScenario;
    this.activeScenario = defaultScenario;
  }

  // ------------------------------------------------------------------ lifecycle

  load(): void {
    this.apply(loadModules(this.dir));
  }

  reload(): ReloadResult {
    const before = new Set(this.variants.keys());
    const collectionsBefore = new Set(this.collections.keys());
    this.apply(loadModules(this.dir));

    const pendingRestart: string[] = [];
    for (const key of this.variants.keys()) {
      if (!this.registeredKeys.has(key)) pendingRestart.push(`+ ${key}`);
    }
    for (const key of before) {
      if (!this.variants.has(key)) pendingRestart.push(`- ${key}`);
    }
    for (const key of collectionsBefore) {
      if (!this.collections.has(key)) pendingRestart.push(`- collection ${key}`);
    }

    return {
      modules: this.modules.length,
      routes: this.variants.size,
      collections: this.collections.size,
      pendingRestart,
    };
  }

  private apply(modules: LoadedModule[]): void {
    const variants = new Map<string, VariantGroup>();
    const collections = new Map<string, CollectionBinding>();

    for (const loaded of modules) {
      const def = loaded.definition;
      for (const route of def.routes) {
        this.addVariant(variants, def, route, loaded.source);
      }
      for (const collection of def.collections) {
        const key = `${def.name}.${collection.name}`;
        if (collections.has(key)) {
          throw new Error(`${loaded.source}: duplicate collection "${key}"`);
        }
        collections.set(key, this.prepareCollection(def, collection, key));
      }
    }

    this.assertNoCollisions(variants, collections);

    // Collection state survives a reload only if the definition is byte-identical; otherwise a
    // changed seed would be invisible, which is the opposite of what an author editing YAML wants.
    for (const [key, binding] of collections) {
      const previous = this.collections.get(key);
      if (previous && previous.store.idField === binding.store.idField) {
        collections.set(key, { ...binding, store: previous.store });
      }
    }

    this.modules = modules;
    this.variants = variants;
    this.collections = collections;
  }

  private prepareCollection(
    module: ModuleDefinition,
    collection: CollectionDefinition,
    key: string,
  ): CollectionBinding {
    return {
      key,
      module: module.name,
      name: collection.name,
      path: joinPath(module.prefix, collection.path),
      delay: collection.delay ?? module.delay,
      store: new CollectionStore(collection),
    };
  }

  private addVariant(
    variants: Map<string, VariantGroup>,
    module: ModuleDefinition,
    route: RouteDefinition,
    source: string,
  ): void {
    const path = joinPath(module.prefix, route.path);
    const key = routeKey(route.method, path);
    const prepared = this.prepareRoute(module, route, path, key, source);

    let group = variants.get(key);
    if (!group) {
      group = { method: route.method, path, byScenario: new Map() };
      variants.set(key, group);
    }

    if (prepared.scenario) {
      if (group.byScenario.has(prepared.scenario)) {
        throw new Error(
          `${source}: duplicate route "${key}" for scenario "${prepared.scenario}" — ` +
            `each (method, path, scenario) triple may only be defined once`,
        );
      }
      group.byScenario.set(prepared.scenario, prepared);
    } else {
      if (group.base) {
        throw new Error(
          `${source}: duplicate route "${key}" — add a "scenario:" to one of them, or delete it`,
        );
      }
      group.base = prepared;
    }
  }

  private prepareRoute(
    module: ModuleDefinition,
    route: RouteDefinition,
    path: string,
    key: string,
    source: string,
  ): PreparedRoute {
    const describe = (what: string, cause: unknown) =>
      new Error(`${source}: route "${key}" ${what}: ${(cause as Error).message}`);

    let body: Compiled | undefined;
    if (route.response.body !== undefined) {
      try {
        body = compile(route.response.body);
      } catch (cause) {
        throw describe('has an invalid response body template', cause);
      }
    }

    let fault: PreparedFault | undefined;
    if (route.fault) {
      let faultBody: Compiled;
      try {
        faultBody = compile(route.fault.body ?? { error: 'injected fault', route: key });
      } catch (cause) {
        throw describe('has an invalid fault body template', cause);
      }
      fault = {
        rate: route.fault.rate,
        status: route.fault.status,
        body: faultBody,
        ...(faultBody.isStatic ? { staticPayload: JSON.stringify(faultBody.value) } : {}),
      };
    }

    // Header values go through the same compiler as bodies. Static ones collapse back into a
    // plain map so the common case stays a single reply.headers() call with no rendering.
    const staticHeaders: Record<string, string> = {};
    const dynamicHeaders: Array<readonly [string, Compiled]> = [];
    for (const [name, value] of Object.entries(route.response.headers)) {
      let compiled: Compiled;
      try {
        compiled = compile(value);
      } catch (cause) {
        throw describe(`has an invalid template in response header "${name}"`, cause);
      }
      if (compiled.isStatic) staticHeaders[name] = String(compiled.value);
      else dynamicHeaders.push([name, compiled]);
    }

    return {
      id: route.id ?? key,
      key,
      module: module.name,
      method: route.method,
      path,
      ...(route.scenario ? { scenario: route.scenario } : {}),
      ...(route.summary ? { summary: route.summary } : {}),
      ...(route.description ? { description: route.description } : {}),
      status: route.response.status,
      headers: staticHeaders,
      hasHeaders: Object.keys(staticHeaders).length > 0,
      ...(dynamicHeaders.length ? { dynamicHeaders } : {}),
      ...(route.delay !== undefined ? { delay: route.delay } : module.delay !== undefined ? { delay: module.delay } : {}),
      ...(fault ? { fault } : {}),
      ...(body ? { body } : {}),
      ...(body?.isStatic ? { staticPayload: JSON.stringify(body.value) } : {}),
    };
  }

  /** A collection generates real routes; a hand-written route on the same path would shadow it. */
  private assertNoCollisions(
    variants: Map<string, VariantGroup>,
    collections: Map<string, CollectionBinding>,
  ): void {
    for (const binding of collections.values()) {
      for (const key of [
        routeKey('GET', binding.path),
        routeKey('POST', binding.path),
        routeKey('GET', `${binding.path}/:id`),
      ]) {
        if (variants.has(key)) {
          throw new Error(
            `route "${key}" collides with collection "${binding.key}" which generates the same ` +
              `path. Rename the collection path or drop the explicit route.`,
          );
        }
      }
    }
  }

  // -------------------------------------------------------------------- reading

  get scenario(): string {
    return this.activeScenario;
  }

  get defaultScenario(): string {
    return this.baseScenario;
  }

  setScenario(name: string): void {
    this.activeScenario = name;
  }

  knownScenarios(): string[] {
    const names = new Set<string>([this.baseScenario]);
    for (const group of this.variants.values()) {
      for (const name of group.byScenario.keys()) names.add(name);
    }
    return [...names].sort();
  }

  loadedModules(): readonly LoadedModule[] {
    return this.modules;
  }

  routeKeys(): string[] {
    return [...this.variants.keys()];
  }

  collectionBindings(): readonly CollectionBinding[] {
    return [...this.collections.values()];
  }

  collection(key: string): CollectionBinding | undefined {
    return this.collections.get(key);
  }

  /** Resolves the variant to serve right now: scenario-specific first, then the base variant. */
  resolve(key: string): PreparedRoute | undefined {
    const group = this.variants.get(key);
    if (!group) return undefined;
    return group.byScenario.get(this.activeScenario) ?? group.base;
  }

  variantsOf(key: string): PreparedRoute[] {
    const group = this.variants.get(key);
    if (!group) return [];
    return [...(group.base ? [group.base] : []), ...group.byScenario.values()];
  }

  markRegistered(keys: Iterable<string>): void {
    this.registeredKeys = new Set(keys);
  }

  // ---------------------------------------------------------------- bookkeeping

  nextSeq(key: string): number {
    const next = (this.seqCounters.get(key) ?? 0) + 1;
    this.seqCounters.set(key, next);
    return next;
  }

  recordFault(key: string): void {
    this.statsFor(key).faults += 1;
  }

  recordHit(key: string, durationMs: number, status: number): void {
    const entry = this.statsFor(key);
    entry.hits += 1;
    entry.totalMs += durationMs;
    if (durationMs > entry.maxMs) entry.maxMs = durationMs;
    entry.lastAt = new Date().toISOString();
    entry.lastStatus = status;
  }

  private statsFor(key: string): RouteStats {
    let entry = this.stats.get(key);
    if (!entry) {
      const resolved = this.resolve(key);
      entry = {
        key,
        id: resolved?.id ?? key,
        module: resolved?.module ?? '-',
        hits: 0,
        faults: 0,
        totalMs: 0,
        maxMs: 0,
      };
      this.stats.set(key, entry);
    }
    return entry;
  }

  allStats(): RouteStats[] {
    return [...this.stats.values()].sort((a, b) => b.hits - a.hits);
  }

  resetStats(): void {
    this.stats.clear();
    this.seqCounters.clear();
  }

  resetState(): void {
    for (const binding of this.collections.values()) binding.store.reset();
  }
}

export type { TemplateContext };
