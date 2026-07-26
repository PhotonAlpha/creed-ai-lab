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
