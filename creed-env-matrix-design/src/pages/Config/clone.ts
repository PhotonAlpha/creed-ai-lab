import type { EndpointFormValues } from './EndpointFormModal';
import type { ConfigRow } from './types';

/** The dimensions a clone may move to — what differs between one environment and the next. */
export const CLONE_DIMENSIONS = ['tier', 'envInstance', 'country', 'instance'] as const;

/**
 * What the clone dialog collects. Every field is optional: blank means "keep the source row's value",
 * so ticking ten rows across two countries and only setting `envInstance` keeps both countries.
 */
export interface CloneOverrides {
  tier?: string;
  envInstance?: string;
  country?: string;
  instance?: string;
  hostFind?: string;
  hostReplace?: string;
  ipFind?: string;
  ipReplace?: string;
}

/**
 * The seven-dimension identity, joined exactly as the batch save compares it — case-sensitive and
 * including `scheme`, because the backend's unique index is.
 */
export function identityOf(row: Pick<ConfigRow, 'appSystem' | 'tier' | 'envInstance' | 'country' | 'service' | 'instance' | 'scheme'>) {
  return [row.appSystem, row.tier, row.envInstance, row.country, row.service, row.instance, row.scheme].join('/');
}

/**
 * Replaces every occurrence, ignoring case: hostnames carry the env instance in lower case
 * (`ms1-green.cn.uat1.creed.internal`) while the dimension is `UAT1`, so "UAT1 → UAT4" has to
 * match either. Plain text, not a regex — a `.` in an IP must not match any character.
 */
function replaceAllIgnoreCase(value: string, find: string | undefined, replacement: string | undefined) {
  if (!find) return value;
  const escaped = find.replace(/[.*+?^${}()|[\]\\]/g, '\\$&');
  return value.replace(new RegExp(escaped, 'gi'), () => replacement ?? '');
}

export function cloneRow(source: ConfigRow, overrides: CloneOverrides): EndpointFormValues {
  const pick = (key: (typeof CLONE_DIMENSIONS)[number]) => overrides[key]?.trim() || source[key];
  return {
    appSystem: source.appSystem,
    tier: pick('tier'),
    envInstance: pick('envInstance'),
    country: pick('country'),
    service: source.service,
    instance: pick('instance'),
    scheme: source.scheme,
    host: replaceAllIgnoreCase(source.host, overrides.hostFind?.trim(), overrides.hostReplace?.trim()),
    ip: replaceAllIgnoreCase(source.ip, overrides.ipFind?.trim(), overrides.ipReplace?.trim()),
    port: source.port,
    note: source.note,
  };
}

export interface ClonePreview {
  /** The source row's `_key` — unique per clone, since each row is cloned once. */
  sourceKey: string;
  values: EndpointFormValues;
  /** Same identity as a row already in the table, or as an earlier clone — the save would reject it. */
  duplicate: boolean;
}

/**
 * Clones plus whether each would collide. Compared against every row that will still be submitted
 * (rows marked for deletion are about to go, so their identity is free again).
 */
export function previewClones(sources: ConfigRow[], all: ConfigRow[], overrides: CloneOverrides): ClonePreview[] {
  const taken = new Set(all.filter((row) => !row._deleted).map(identityOf));
  return sources.map((source) => {
    const values = cloneRow(source, overrides);
    const identity = identityOf(values);
    const duplicate = taken.has(identity);
    taken.add(identity);
    return { sourceKey: source._key, values, duplicate };
  });
}
