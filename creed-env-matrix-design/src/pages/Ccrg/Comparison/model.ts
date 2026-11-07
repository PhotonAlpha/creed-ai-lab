import type { BatchFormVo, SelectorVo, WalkPathInfo } from '../../../api/ccrg';

/**
 * Batch Export and Crossover share one form: a list of combinations, each comparing two "sources"
 * (project / release / country / app / environment / instance / zones) in one report.
 *
 * - **batch** — both sources are the same project: picking it on source 1 fills and locks source 2,
 *   and the release list is shared.
 * - **crossover** — each source picks its own project, so every list is per source.
 *
 * Within a combination, a country / app picked while both sources sit on the same release is copied
 * to the other source and locked there (`disableCountry` / `disableApp`): two different releases may
 * legitimately differ below the release level, the same release cannot.
 */
export type ComparisonMode = 'batch' | 'crossover';

export interface Source {
  projectName: string;
  releaseName: string;
  countryCode: string;
  app: string;
  environment: string;
  instance: string;
  node: string;
  zone: string[];
  releaseSelector: SelectorVo[];
  countrySelector: SelectorVo[];
  appSelector: SelectorVo[];
  environmentSelector: SelectorVo[];
  instanceSelector: SelectorVo[];
  zoneSelector: SelectorVo[];
}

export interface Combination {
  compareAll: boolean;
  /** Required when `compareAll` is off: the release every other one is compared against. */
  baseRelease: string;
  disableProject: boolean;
  disableCountry: boolean;
  disableApp: boolean;
  routes: Source[];
}

export const emptySource = (): Source => ({
  projectName: '',
  releaseName: '',
  countryCode: '',
  app: '',
  environment: '',
  instance: '',
  node: '',
  zone: [],
  releaseSelector: [],
  countrySelector: [],
  appSelector: [],
  environmentSelector: [],
  instanceSelector: [],
  zoneSelector: [],
});

export const emptyCombination = (): Combination => ({
  compareAll: true,
  baseRelease: '',
  disableProject: false,
  disableCountry: false,
  disableApp: false,
  routes: [emptySource(), emptySource()],
});

/** Fills what an older saved form or a hand-written template leaves out, so nothing downstream sees `undefined`. */
export function normalize(raw: unknown): Combination[] {
  const list = Array.isArray(raw) ? raw : (raw as { batch?: unknown })?.batch;
  if (!Array.isArray(list) || list.length === 0) {
    throw new Error('expected a non-empty array of combinations, or {"batch": [...]}');
  }
  return list.map((c: Partial<Combination>) => {
    const routes = Array.isArray(c.routes) && c.routes.length > 0 ? c.routes : [{}, {}];
    return {
      ...emptyCombination(),
      ...c,
      routes: routes.map((r: Partial<Source>) => {
        const source = { ...emptySource(), ...r };
        // The old form sent zones joined with commas; a template may carry them that way.
        if (!Array.isArray(source.zone)) source.zone = String(source.zone ?? '').split(',').filter(Boolean);
        return source;
      }),
    };
  });
}

/** Clears every pick below `level`, plus the lists that hang off them. */
export function clearBelow(s: Source, level: 'project' | 'release' | 'country' | 'app' | 'environment'): void {
  const order = ['project', 'release', 'country', 'app', 'environment'] as const;
  const from = order.indexOf(level);
  if (from < 1) {
    s.releaseName = '';
  }
  if (from < 2) {
    s.countryCode = '';
    s.appSelector = [];
  }
  if (from < 3) {
    s.app = '';
    s.environmentSelector = [];
  }
  if (from < 4) {
    s.environment = '';
    s.instanceSelector = [];
  }
  s.instance = '';
  s.zone = [];
  s.zoneSelector = [];
  if (from < 1) s.countrySelector = [];
}

export const walk = (s: Source, dictType: WalkPathInfo['dictType'], depth: number): WalkPathInfo => {
  const info: WalkPathInfo = { dictType, projectName: s.projectName };
  if (depth >= 1) info.releaseName = s.releaseName;
  if (depth >= 2) info.countryCode = s.countryCode;
  if (depth >= 3) info.app = s.app;
  if (depth >= 4) info.environment = s.environment;
  if (depth >= 5) info.instance = s.instance;
  return info;
};

export const sameRelease = (c: Combination) => c.routes.every((r) => r.releaseName === c.routes[0].releaseName);

/** Fields a source must have before a report can run, in display order. */
export const REQUIRED: (keyof Source)[] = ['projectName', 'releaseName', 'countryCode', 'app', 'environment', 'instance', 'zone'];

export const isMissing = (s: Source, field: keyof Source) => {
  const v = s[field];
  return Array.isArray(v) ? v.length === 0 : !v;
};

export function isComplete(batch: Combination[]): boolean {
  return batch.every(
    (c) => (c.compareAll || !!c.baseRelease) && c.routes.every((r) => REQUIRED.every((f) => !isMissing(r, f))),
  );
}

export function toRequest(batch: Combination[], connectionId?: string): BatchFormVo {
  return {
    connectionId,
    batch: batch.map((c) => ({
      compareAll: c.compareAll,
      baseRelease: c.baseRelease,
      routes: c.routes.map((r) => ({
        releaseName: r.releaseName,
        projectName: r.projectName,
        countryCode: r.countryCode,
        app: r.app,
        environment: r.environment,
        instance: r.instance,
        node: r.node,
        zone: r.zone.join(','),
      })),
    })),
  };
}
