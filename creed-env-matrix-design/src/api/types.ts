/**
 * Mirrors the DTOs of creed-resource-env-matrix (`com.creed.resource.envmatrix.api.dto`).
 * Keep the two in step: the mock server in `server/index.js` serves this same shape.
 */

/** The seven dimensions that make up an endpoint's identity. */
export const DIMENSION_KEYS = [
  'appSystem',
  'tier',
  'envInstance',
  'country',
  'service',
  'instance',
  'scheme',
] as const;

export type DimensionKey = (typeof DIMENSION_KEYS)[number];

export type HealthState = 'UP' | 'DEGRADED' | 'DOWN' | 'UNKNOWN';

export type ConflictKind = 'HOST_PORT' | 'IP_PORT';

/** Where an address must be unique; the backend decides this, the UI only displays it. */
export type ConflictScope = 'TIER_ENV' | 'TIER' | 'GLOBAL';

export interface Endpoint {
  id: number;
  appSystem: string;
  tier: string;
  envInstance: string;
  country: string;
  service: string;
  instance: string;
  scheme: string;
  host: string;
  ip: string;
  port: number;
  note: string | null;
  /** Derived server-side: `scheme://host:port`. */
  url: string;
  conflict: boolean;
  /** Human-readable colliding keys, e.g. `["ip:port 10.20.0.7:8443"]`. */
  conflictKeys: string[];
  health: HealthState;
  createdAt: string;
  updatedAt: string;
  version: number;
}

export interface ConflictGroup {
  kind: ConflictKind;
  scopeKey: string;
  value: string;
  endpoints: Endpoint[];
}

export interface MatrixCell {
  service: string;
  country: string;
  endpoints: Endpoint[];
  conflict: boolean;
  conflictCount: number;
}

export interface MatrixResponse {
  services: string[];
  countries: string[];
  cells: MatrixCell[];
  conflicts: ConflictGroup[];
  total: number;
  scope: ConflictScope;
}

export type Dimensions = Record<DimensionKey, string[]>;

export interface HealthReport {
  mode: 'mock' | 'real';
  mocked: boolean;
  seed: number;
  total: number;
  summary: Partial<Record<HealthState, number>>;
  states: Record<string, HealthState>;
  checkedAt: string;
}

/**
 * Whether traffic on a declared link flows one way or both.
 *
 * Purely presentational — it decides the arrowheads. Layering always follows the stored
 * `source -> target` orientation, so a two-way link still has a defined upstream end.
 */
export type LinkDirection = 'ONE_WAY' | 'BIDIRECTIONAL';

export type ReleaseStatus = 'DRAFT' | 'ACTIVE' | 'ARCHIVED';

/**
 * A named set of environment slices and the links between them — the topology graph's scope.
 *
 * A connection cannot be keyed on app systems: the chain
 * `SG CCS SIT3 -> Global-CCS SIT2 -> CN CCS SIT5` has CCS in it twice. So a topology node is a
 * slice, and a release is what says which slices belong together. That is also what keeps the other
 * dimensions orthogonal — country, envInstance, service and instance stay plain data.
 */
export interface Release {
  id: number;
  name: string;
  /** A label, not a constraint: participants may name instances from another tier. */
  tier: string;
  status: ReleaseStatus;
  note: string | null;
  nodeCount: number;
  linkCount: number;
  createdAt: string;
  updatedAt: string;
  version: number;
}

/**
 * One participant: an environment slice. `country` is `'*'` when it is not country-specific.
 *
 * `layer` and `sortOrder` are where it is *drawn*, as opposed to what it is connected to. The graph
 * ranks participants by a longest path over the links; `layer` overrides that ranking for this
 * participant alone, and `null` — the state every participant starts in — means "derive it". They
 * live on the release rather than in the browser so that everyone opening it sees the same picture.
 */
export interface ReleaseNode {
  id: number;
  appSystem: string;
  country: string;
  envInstance: string;
  label: string | null;
  note: string | null;
  /** Pinned layer, or `null` to derive it from the links. */
  layer: number | null;
  /** Position within the layer; `0` is the default order. */
  sortOrder: number;
}

/** One connection. Both ends are participant ids within the same release. */
export interface ReleaseLink {
  id: number;
  sourceNodeId: number;
  targetNodeId: number;
  direction: LinkDirection;
  note: string | null;
}

export interface ReleaseTopology {
  release: Release;
  nodes: ReleaseNode[];
  links: ReleaseLink[];
}

export interface ReleaseRequest {
  name: string;
  tier: string;
  status: ReleaseStatus;
  note?: string | null;
}

/**
 * Points at an existing participant by `id`, or at one created in the same payload by `ref`.
 *
 * This is the one awkward corner of the contract, and it exists because the commonest edit is "add
 * a participant and connect it" — the new participant has no database id yet, so the link has to
 * name it some other way.
 */
export interface NodeRef {
  id?: number;
  ref?: string;
}

export interface ReleaseTopologyRequest {
  nodes: Array<{
    id?: number;
    ref?: string;
    appSystem: string;
    country: string;
    envInstance: string;
    label?: string | null;
    note?: string | null;
    /** `null` clears the pin and hands the participant back to the derived layering. */
    layer?: number | null;
    sortOrder?: number;
  }>;
  links: Array<{
    id?: number;
    source: NodeRef;
    target: NodeRef;
    direction: LinkDirection;
    note?: string | null;
  }>;
}

/** @param section which list `index` refers to — `nodes` or `links`. */
export interface ReleaseTopologyIssue {
  section: 'nodes' | 'links';
  index: number;
  id: number | null;
  field: string;
  message: string;
}

export interface ReleaseTopologySaveResponse {
  success: boolean;
  nodesInserted: number;
  nodesUpdated: number;
  nodesDeleted: number;
  linksInserted: number;
  linksUpdated: number;
  linksDeleted: number;
  issues: ReleaseTopologyIssue[];
}

/** `'*'` in a participant's `country` — the slice is not country-specific. */
export const ANY_COUNTRY = '*';

/** Create/update payload. `id` present ⇒ update that row, absent ⇒ insert. */
export interface EndpointRequest {
  id?: number;
  appSystem: string;
  tier: string;
  envInstance: string;
  country: string;
  service: string;
  instance: string;
  scheme: string;
  host: string;
  ip: string;
  port: number;
  note?: string | null;
}

export interface BatchSaveIssue {
  /** Zero-based index into the submitted array, so the UI can point at the offending row. */
  index: number;
  id: number | null;
  field: string;
  message: string;
}

export interface BatchSaveResponse {
  success: boolean;
  inserted: number;
  updated: number;
  deleted: number;
  issues: BatchSaveIssue[];
  conflicts: ConflictGroup[];
}

/** Filter state — a list per dimension, empty meaning "unconstrained". */
export type EndpointFilter = Partial<Record<DimensionKey, string[]>> & {
  keyword?: string;
};
