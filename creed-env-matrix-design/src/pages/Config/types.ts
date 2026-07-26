import type { Endpoint, EndpointRequest, HealthState } from '../../api/types';

/**
 * A row in the config table: the server's endpoint plus the local edit bookkeeping the page needs
 * before anything is written back.
 *
 * `id` is `null` for rows added in the browser — that is exactly what tells the backend's batch save
 * to insert rather than update.
 */
export interface ConfigRow {
  /** Stable client-side key. Needed because new rows have no id yet. */
  _key: string;
  _new: boolean;
  /** Marked for removal; deleted from the database on the next save. */
  _deleted: boolean;
  _dirty: boolean;

  id: number | null;
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

  // Derived server-side; read-only here and recomputed after every save.
  url: string;
  conflict: boolean;
  conflictKeys: string[];
  health: HealthState;
}

export function toConfigRow(endpoint: Endpoint): ConfigRow {
  return {
    _key: `db-${endpoint.id}`,
    _new: false,
    _deleted: false,
    _dirty: false,
    id: endpoint.id,
    appSystem: endpoint.appSystem,
    tier: endpoint.tier,
    envInstance: endpoint.envInstance,
    country: endpoint.country,
    service: endpoint.service,
    instance: endpoint.instance,
    scheme: endpoint.scheme,
    host: endpoint.host,
    ip: endpoint.ip,
    port: endpoint.port,
    note: endpoint.note,
    url: endpoint.url,
    conflict: endpoint.conflict,
    conflictKeys: endpoint.conflictKeys,
    health: endpoint.health,
  };
}

/** Strips the local bookkeeping so only what the API accepts goes over the wire. */
export function toRequest(row: ConfigRow): EndpointRequest {
  return {
    ...(row.id != null ? { id: row.id } : {}),
    appSystem: row.appSystem,
    tier: row.tier,
    envInstance: row.envInstance,
    country: row.country,
    service: row.service,
    instance: row.instance,
    scheme: row.scheme,
    host: row.host,
    ip: row.ip,
    port: row.port,
    note: row.note,
  };
}
