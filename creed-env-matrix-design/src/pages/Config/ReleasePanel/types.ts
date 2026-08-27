import type { LinkDirection, ReleaseLink, ReleaseNode } from '../../../api/types';

/**
 * Local edit state for one release's topology.
 *
 * Rows carry a client-side `_key` because a row added in the browser has no database id yet, and
 * the connection rows have to be able to point at it before the save assigns one. That `_key` is
 * exactly what becomes the `ref` in the payload — see {@link toTopologyRequest}.
 */
export interface ParticipantRow {
  _key: string;
  _new: boolean;
  _dirty: boolean;
  id: number | null;
  appSystem: string;
  country: string;
  envInstance: string;
  label: string | null;
  note: string | null;
}

export interface ConnectionRow {
  _key: string;
  _new: boolean;
  _dirty: boolean;
  id: number | null;
  /** Both ends are participant `_key`s, never ids — a new participant has no id to point at. */
  sourceKey: string;
  targetKey: string;
  direction: LinkDirection;
  note: string | null;
}

export const participantKey = (id: number) => `db-${id}`;

export function toParticipantRow(node: ReleaseNode): ParticipantRow {
  return {
    _key: participantKey(node.id),
    _new: false,
    _dirty: false,
    id: node.id,
    appSystem: node.appSystem,
    country: node.country,
    envInstance: node.envInstance,
    label: node.label,
    note: node.note,
  };
}

export function toConnectionRow(link: ReleaseLink): ConnectionRow {
  return {
    _key: `db-${link.id}`,
    _new: false,
    _dirty: false,
    id: link.id,
    sourceKey: participantKey(link.sourceNodeId),
    targetKey: participantKey(link.targetNodeId),
    direction: link.direction,
    note: link.note,
  };
}

/** The slice a participant stands for, e.g. `CCS · SG · SIT3`. Used in labels and duplicate checks. */
export const sliceOf = (row: Pick<ParticipantRow, 'appSystem' | 'country' | 'envInstance'>) =>
  `${row.appSystem} · ${row.country} · ${row.envInstance}`;

/**
 * Builds the save payload.
 *
 * A participant that exists in the database goes over as `{id}`; one added here goes over as
 * `{ref: _key}`, and every connection names its ends the same way. That is the whole reason the
 * contract has a `ref` at all: the commonest edit is "add a participant and connect it", and the
 * new participant has no id until this request lands.
 */
export function toTopologyRequest(participants: ParticipantRow[], connections: ConnectionRow[]) {
  const byKey = new Map(participants.map((row) => [row._key, row]));
  const endOf = (key: string) => {
    const row = byKey.get(key);
    return row?.id != null ? { id: row.id } : { ref: key };
  };

  return {
    nodes: participants.map((row) => ({
      ...(row.id != null ? { id: row.id } : { ref: row._key }),
      appSystem: row.appSystem,
      country: row.country,
      envInstance: row.envInstance,
      label: row.label,
      note: row.note,
    })),
    links: connections.map((row) => ({
      ...(row.id != null ? { id: row.id } : {}),
      source: endOf(row.sourceKey),
      target: endOf(row.targetKey),
      direction: row.direction,
      note: row.note,
    })),
  };
}
