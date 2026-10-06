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

/* ---- Splunk session broker (/splunk/*) ------------------------------------------------------- */

export interface TotpInfo {
  /** false ⇒ no secret on the server; every submission answers 503. */
  configured: boolean;
  periodSeconds: number;
  digits: number;
  allowedDriftSteps: number;
  /** The server's clock when answered — the countdown follows the verifier, not the browser. */
  serverTimeMillis: number;
  /** Whether `GET /splunk/totp/current` serves the code. */
  codeVisible: boolean;
  splunkMode: 'real' | 'mock';
  /** At least one login target can log in. */
  splunkConfigured: boolean;
  targets: SplunkTarget[];
  /** The target the dropdown starts on, and the one used when a request names none. */
  defaultTarget: string;
  scriptCookieName: string;
}

/** One Splunk instance the broker can log into. The password never leaves the server. */
export interface SplunkTarget {
  id: string;
  label: string;
  loginUrl: string | null;
  username: string | null;
  passwordSet: boolean;
  /** URL, username and password all set — or Splunk is mocked, which needs none of them. */
  configured: boolean;
  /** The cookie read off Splunk's login response by default (`splunkd_<web port>`). */
  sessionCookie: string;
  /** The cookie the script sets by default. */
  scriptCookieName: string;
  scriptCookiePath: string;
  /** `host:port` of a TCP forward the login can connect through; null when the target has none. */
  tunnel: string | null;
  /** Whether a login goes through the tunnel when the request does not say. */
  tunnelDefault: boolean;
}

/** This login's overrides of the target's settings; anything omitted takes the target's. */
export interface SplunkSessionOptions {
  sessionCookie?: string;
  scriptCookieName?: string;
  viaTunnel?: boolean;
}

export interface TotpCode {
  code: string;
  step: number;
  secondsRemaining: number;
  periodSeconds: number;
  serverTimeMillis: number;
}

export interface SplunkSession {
  /** Id of the target logged into. */
  target: string;
  /** The cookie read from Splunk's login response (`splunkd_8000`). */
  sourceCookie: string;
  /** The cookie the script sets (`splunkd_8089`). */
  cookieName: string;
  cookieValue: string;
  script: string;
  /** The tunnel the login went through, or null for a direct connection. */
  tunnel: string | null;
  mode: 'real' | 'mock';
  correlationId: string;
  cookieFingerprint: string;
  issuedAt: string;
}

export type SplunkAuditEventType = 'OTP_VERIFY' | 'SPLUNK_LOGIN';

export interface SplunkAuditRow {
  id: number;
  correlationId: string;
  eventType: SplunkAuditEventType;
  outcome: 'SUCCESS' | 'FAILURE';
  reason: string | null;
  detail: string | null;
  clientIp: string | null;
  forwardedFor: string | null;
  userAgent: string | null;
  serverStep: number | null;
  matchedStep: number | null;
  splunkMode: string | null;
  httpStatus: number | null;
  cookieFingerprint: string | null;
  durationMs: number | null;
  createdAt: string;
}

/* ---- AES encryption (/aes/*) ------------------------------------------------------------------ */

/**
 * What one server's key is made from. There is no Secret Key input: it is `randomKey + host + ip`.
 * The IV is never stored; the randomkey is stored per saved row.
 */
export interface AesKeys {
  /** Exactly 16 bytes in UTF-8. */
  iv: string;
  /** PBKDF2 salt, used as UTF-8 bytes; required. Never stored, like the IV. */
  salt: string;
  /** First part of the Secret Key; may be empty. */
  randomKey: string;
  host: string;
  ip: string;
}

export interface AesCryptoResult {
  encryptedValue: string | null;
  plainValue: string | null;
  algorithm: string;
}

/** One distinct (appSystem, host, ip) from the endpoint table. */
export interface AesServer {
  appSystem: string;
  host: string;
  ip: string;
  envInstance: string;
  instance: string;
}

export interface AesRecord extends AesServer {
  id: number;
  propertyKey: string;
  encryptedValue: string;
  /** The randomkey the row was saved with; `null` when none was (or before it was recorded). */
  randomKey: string | null;
  /** `randomKey + host + ip` — derived by the server, shown to compare against the real config. */
  secretKey: string;
  /** The IV and salt it was encrypted with — stored since V9; `null` for older records. */
  iv: string | null;
  salt: string | null;
  note: string | null;
  createdAt: string;
  updatedAt: string;
  version: number;
}

/** Single save: one plain value, encrypted per server by the backend. */
export interface AesRecordSaveRequest {
  propertyKey: string;
  plainValue: string;
  iv: string;
  salt: string;
  randomKey?: string;
  note?: string;
  servers: AesServer[];
}

export interface AesRecordSaveResponse {
  inserted: number;
  updated: number;
  records: AesRecord[];
}

export interface AesRecordDecryptResult {
  id: number;
  plainValue: string | null;
  error: 'not_found' | 'invalid_key_material' | 'decrypt_failed' | null;
  message: string | null;
}

/**
 * One row of "Keys and values" — the JSON dialog's array element too. No Secret Key: it is
 * `randomKey + host + ip`, so it depends on the server the row is encrypted for.
 */
export interface AesKeyValueRow {
  iv: string;
  salt: string;
  randomKey: string;
  propertyKey: string;
  plainValue: string;
  encryptedValue: string;
}

/** A row of `/aes/encrypt/batch` or `/aes/decrypt/batch` (`value` = plaintext or ciphertext). */
export interface AesBatchCryptoItem extends AesKeys {
  value: string;
}

export interface AesBatchCryptoResult {
  /** Position in the request. */
  index: number;
  value: string | null;
  error: 'invalid_key_material' | 'decrypt_failed' | null;
  /** For `invalid_key_material`: `iv` or `salt`. */
  field: string | null;
  message: string | null;
}

/** Plain values, not ciphertexts: the backend encrypts each item once per server. */
export interface AesRecordBatchSaveRequest {
  items: { propertyKey: string; plainValue: string; iv: string; salt: string; randomKey?: string; note?: string }[];
  servers: AesServer[];
}

/** One stored record to decrypt. Its Secret Key comes from the record; only the IV is supplied. */
export interface AesRecordDecryptItem {
  id: number;
  /** Only for records without a stored IV/salt (saved before V9); otherwise the stored ones are used. */
  iv?: string;
  salt?: string;
}
