import type {
  ConflictGroup,
  Endpoint,
  LinkDirection,
  ReleaseLink,
  ReleaseNode,
} from '../../api/types';
import { ANY_COUNTRY } from '../../api/types';
import {
  CLUSTER_GAP,
  CLUSTER_MAX_COLS,
  COL_GAP,
  GROUP_GAP,
  MAX_ROWS,
  NODE_H,
  NODE_W,
  ROW_GAP,
  SUB_COL_GAP,
} from './topology.config';

/**
 * The four kinds of line on the graph, and where each one comes from:
 *
 * - `dep`   — a declared `env_release_link` row, drawn combo-to-combo and edited from the config page.
 * - `colo`  — derived: two endpoints answer on the same `host`, i.e. the same box.
 * - `alias` — derived: two different hostnames resolve to the same `ip`. The clash DNS hides.
 * - `clash` — straight from `/conflicts`: two endpoints claim the same `host:port` or `ip:port`.
 */
export type EdgeKind = 'dep' | 'colo' | 'alias' | 'clash';

export const EDGE_KINDS: readonly EdgeKind[] = ['dep', 'colo', 'alias', 'clash'];

/**
 * `layered` — one column per declared layer, so the x axis is the hierarchy. The primary view.
 * `cluster` — the same participant groups packed as compact blocks, for reading one slice at a
 *             time rather than the flow between them.
 *
 * Both are positioned here rather than by a G6 layout, and the graph runs no layout at all.
 * `combo-combined`, the obvious candidate for the clustered view, lays each combo out independently
 * and then overlaps the boxes; and no generic layout knows about the declared ranking, which is
 * the entire point of the layered view.
 *
 * A circular layout was tried and dropped: the nodes here are 196px cards, not dots, so a ring of
 * one environment's forty endpoints is some 3000px across and fit-to-view shrinks the labels out of
 * legibility. Round layouts need round nodes.
 */
export type TopologyLayout = 'layered' | 'cluster';

export interface TopoNode {
  id: string;
  comboId: string;
  /** The participant this node belongs to. */
  participantId: number;
  layer: number;
  /**
   * `null` for a placeholder — a participant with no endpoint matching its slice. Drawing it anyway
   * is the point: a slice wired into the topology with nothing recorded in the matrix is exactly
   * the gap this viewer exists to surface.
   */
  endpoint: Endpoint | null;
  /** Position in the `layered` layout. */
  x: number;
  y: number;
  /** Position in the `cluster` layout. */
  clusterX: number;
  clusterY: number;
}

/** One participant, drawn as the group box its endpoints sit inside. */
export interface TopoCombo {
  id: string;
  participantId: number;
  appSystem: string;
  country: string;
  envInstance: string;
  /** `CCS · SG · SIT3`, or the participant's own label when it has one. */
  title: string;
  layer: number;
  count: number;
}

export interface TopoEdge {
  id: string;
  source: string;
  target: string;
  kind: EdgeKind;
  /** Why this line exists, shown in the detail panel — e.g. `ip:port 10.1.3.21:8443`. */
  reason: string;
  /** `dep` edges only: whether to draw one arrowhead or two. */
  direction?: LinkDirection;
}

export interface TopologyModel {
  nodes: TopoNode[];
  combos: TopoCombo[];
  edges: TopoEdge[];
  nodeById: Map<string, TopoNode>;
  counts: Record<EdgeKind, number>;
  /** Participants with endpoints but no declared link — surfaced as a hint in the UI. */
  unlinked: string[];
  /** Participants with no endpoint matching their slice — drawn as placeholders. */
  placeholders: string[];
  /** Endpoints in view that no participant claims — the release does not cover them. */
  unclaimed: number;
}

export const nodeIdOf = (endpoint: Endpoint) => `e:${endpoint.id}`;
export const comboIdOf = (participantId: number) => `p:${participantId}`;
const placeholderIdOf = (participantId: number) => `ghost:${participantId}`;

/** `CCS · SG · SIT3`; a country-agnostic slice drops the middle segment. */
export function titleOf(participant: ReleaseNode): string {
  if (participant.label?.trim()) return participant.label;
  const parts = [participant.appSystem];
  if (participant.country !== ANY_COUNTRY) parts.push(participant.country);
  parts.push(participant.envInstance);
  return parts.join(' · ');
}

/**
 * Does this endpoint belong to that participant's slice?
 *
 * `'*'` in the participant's country means "not country-specific" and matches every country — which
 * is how a global instance claims its endpoints without naming each region.
 */
function claims(participant: ReleaseNode, endpoint: Endpoint): boolean {
  return (
    participant.appSystem === endpoint.appSystem &&
    participant.envInstance === endpoint.envInstance &&
    (participant.country === ANY_COUNTRY || participant.country === endpoint.country)
  );
}

/** Groups a list by a derived key, preserving insertion order. */
function groupBy<T>(items: T[], key: (item: T) => string): Map<string, T[]> {
  const map = new Map<string, T[]>();
  for (const item of items) {
    const k = key(item);
    const bucket = map.get(k);
    if (bucket) bucket.push(item);
    else map.set(k, [item]);
  }
  return map;
}

/**
 * Chains a group together instead of connecting every pair.
 *
 * Five endpoints on one host is one fact, not ten lines: a clique would turn a busy host into an
 * unreadable blob and scale as n². A chain says the same thing with n-1 edges.
 */
function chain(
  ids: string[],
  kind: EdgeKind,
  reason: string,
  seen: Set<string>,
  out: TopoEdge[],
): void {
  for (let i = 1; i < ids.length; i += 1) {
    const [source, target] = [ids[i - 1], ids[i]];
    const id = `${kind}:${source}>${target}`;
    if (seen.has(id)) continue;
    seen.add(id);
    out.push({ id, source, target, kind, reason });
  }
}

/**
 * Turns one filtered slice of the matrix into a graph.
 *
 * Pure on purpose: everything here is a function of the two API responses, so the layout maths and
 * the edge derivation are unit-testable without a canvas, a browser or a running backend.
 */
export function buildTopology(
  endpoints: Endpoint[],
  conflicts: ConflictGroup[],
  participants: ReleaseNode[],
  releaseLinks: ReleaseLink[],
): TopologyModel {
  const ranks = rankParticipants(participants, releaseLinks);

  /*
   * An endpoint is claimed by the first participant whose slice matches it.
   *
   * "First" matters when a release declares both a country-specific slice and a country-agnostic
   * one for the same app system and instance — CCS/SG/SIT3 alongside CCS/'*'/SIT3. The specific one
   * must win, otherwise the wildcard swallows every region and the specific box renders empty. So
   * the participants are sorted specific-first before the scan.
   */
  const ordered = participants
    .slice()
    .sort((a, b) =>
      Number(a.country === ANY_COUNTRY) - Number(b.country === ANY_COUNTRY) ||
      a.appSystem.localeCompare(b.appSystem) ||
      a.envInstance.localeCompare(b.envInstance),
    );

  const nodes: TopoNode[] = [];
  const claimedBy = new Map<number, ReleaseNode>();
  for (const endpoint of endpoints) {
    const owner = ordered.find((participant) => claims(participant, endpoint));
    if (!owner) continue;
    claimedBy.set(endpoint.id, owner);
    nodes.push({
      id: nodeIdOf(endpoint),
      comboId: comboIdOf(owner.id),
      participantId: owner.id,
      layer: ranks.get(owner.id) ?? 0,
      endpoint,
      x: 0,
      y: 0,
      clusterX: 0,
      clusterY: 0,
    });
  }

  const membersOf = new Map<number, TopoNode[]>();
  for (const node of nodes) {
    const bucket = membersOf.get(node.participantId);
    if (bucket) bucket.push(node);
    else membersOf.set(node.participantId, [node]);
  }

  // A participant with nothing to show still gets a box, with one dashed placeholder inside it.
  for (const participant of participants) {
    if (membersOf.has(participant.id)) continue;
    const ghost: TopoNode = {
      id: placeholderIdOf(participant.id),
      comboId: comboIdOf(participant.id),
      participantId: participant.id,
      layer: ranks.get(participant.id) ?? 0,
      endpoint: null,
      x: 0,
      y: 0,
      clusterX: 0,
      clusterY: 0,
    };
    nodes.push(ghost);
    membersOf.set(participant.id, [ghost]);
  }

  const nodeById = new Map(nodes.map((node) => [node.id, node]));

  const combos: TopoCombo[] = participants
    .map((participant) => ({
      id: comboIdOf(participant.id),
      participantId: participant.id,
      appSystem: participant.appSystem,
      country: participant.country,
      envInstance: participant.envInstance,
      title: titleOf(participant),
      layer: ranks.get(participant.id) ?? 0,
      count: (membersOf.get(participant.id) ?? []).filter((m) => m.endpoint !== null).length,
    }))
    .sort((a, b) => a.layer - b.layer || a.title.localeCompare(b.title));

  const placeholders = combos.filter((combo) => combo.count === 0).map((combo) => combo.title);
  const linked = new Set(releaseLinks.flatMap((link) => [link.sourceNodeId, link.targetNodeId]));
  const unlinked = combos
    .filter((combo) => combo.count > 0 && !linked.has(combo.participantId))
    .map((combo) => combo.title);
  const unclaimed = endpoints.length - claimedBy.size;

  // ---- positions ----
  const byCombo = new Map<string, TopoNode[]>();
  for (const node of nodes) {
    const bucket = byCombo.get(node.comboId);
    if (bucket) bucket.push(node);
    else byCombo.set(node.comboId, [node]);
  }
  const columns = new Map<number, TopoCombo[]>();
  for (const combo of combos) {
    const bucket = columns.get(combo.layer);
    if (bucket) bucket.push(combo);
    else columns.set(combo.layer, [combo]);
  }
  layOutColumns(columns, byCombo);
  layOutClusters(combos, byCombo);

  // ---- edges ----
  const edges: TopoEdge[] = [];
  const seen = new Set<string>();

  // dep: one edge per declared link, drawn between the participant boxes.
  const onGraph = new Set(participants.map((participant) => participant.id));
  const titleById = new Map(participants.map((p) => [p.id, titleOf(p)]));
  for (const link of releaseLinks) {
    if (!onGraph.has(link.sourceNodeId) || !onGraph.has(link.targetNodeId)) continue;
    const id = `dep:${link.id}`;
    if (seen.has(id)) continue;
    seen.add(id);
    edges.push({
      id,
      source: comboIdOf(link.sourceNodeId),
      target: comboIdOf(link.targetNodeId),
      kind: 'dep',
      direction: link.direction,
      reason: link.note?.trim()
        ? link.note
        : `${titleById.get(link.sourceNodeId)} ${
            link.direction === 'BIDIRECTIONAL' ? '<->' : '->'
          } ${titleById.get(link.targetNodeId)}`,
    });
  }

  // Derived edges only ever join real endpoints, so placeholders are excluded up front.
  const real = nodes.filter((node): node is TopoNode & { endpoint: Endpoint } => node.endpoint !== null);

  // colo: same host. Ordered by port so the chain is stable across reloads.
  for (const [host, members] of groupBy(real, (node) => node.endpoint.host)) {
    if (members.length < 2) continue;
    const sorted = members.slice().sort((a, b) => a.endpoint.port - b.endpoint.port);
    chain(sorted.map((n) => n.id), 'colo', `host ${host}`, seen, edges);
  }

  // alias: one ip, several hostnames. One representative endpoint per hostname — chaining every
  // endpoint would just repeat the colo edges that already cover the same box.
  for (const [ip, members] of groupBy(real, (node) => node.endpoint.ip)) {
    const perHost = new Map<string, TopoNode>();
    for (const node of members) {
      if (!perHost.has(node.endpoint.host)) perHost.set(node.endpoint.host, node);
    }
    if (perHost.size < 2) continue;
    const sorted = [...perHost.values()].sort((a, b) =>
      (a.endpoint?.host ?? '').localeCompare(b.endpoint?.host ?? ''),
    );
    chain(sorted.map((n) => n.id), 'alias', `ip ${ip}`, seen, edges);
  }

  // clash: the backend already decided what collides and within which scope — this only draws it.
  // Members the release does not claim are absent from the graph and simply drop out.
  for (const group of conflicts) {
    const members = group.endpoints
      .map((endpoint) => nodeById.get(nodeIdOf(endpoint)))
      .filter((node): node is TopoNode => node !== undefined)
      .sort((a, b) => (a.endpoint?.id ?? 0) - (b.endpoint?.id ?? 0));
    if (members.length < 2) continue;
    const kindLabel = group.kind === 'HOST_PORT' ? 'host:port' : 'ip:port';
    chain(members.map((n) => n.id), 'clash', `${kindLabel} ${group.value}`, seen, edges);
  }

  const counts = EDGE_KINDS.reduce(
    (acc, kind) => ({ ...acc, [kind]: edges.filter((e) => e.kind === kind).length }),
    {} as Record<EdgeKind, number>,
  );

  return { nodes, combos, edges, nodeById, counts, unlinked, placeholders, unclaimed };
}

/**
 * Assigns each participant a column from the declared links: longest path from a participant with
 * no declared upstream.
 *
 * Only the stored `source -> target` orientation counts, whatever the direction flag says. Treating
 * a `BIDIRECTIONAL` link as an edge both ways would make every two-way pair a two-cycle with no
 * defined layering, and the operator drew the orientation for a reason.
 *
 * Cycles are still possible — nothing stops someone declaring A -> B -> C -> A — so the walk carries
 * its own path and ignores an edge that closes back onto it. That drops the *ranking* contribution
 * of one edge in the cycle; the edge itself is still drawn.
 */
function rankParticipants(participants: ReleaseNode[], links: ReleaseLink[]): Map<number, number> {
  const upstream = new Map<number, number[]>();
  for (const participant of participants) upstream.set(participant.id, []);
  for (const link of links) {
    if (!upstream.has(link.targetNodeId) || !upstream.has(link.sourceNodeId)) continue;
    upstream.get(link.targetNodeId)!.push(link.sourceNodeId);
  }

  const ranks = new Map<number, number>();
  const rankOf = (id: number, path: Set<number>): number => {
    const cached = ranks.get(id);
    if (cached !== undefined) return cached;
    if (path.has(id)) return 0;

    path.add(id);
    const sources = upstream.get(id) ?? [];
    const rank = sources.length === 0
      ? 0
      : Math.max(...sources.map((source) => rankOf(source, path) + 1));
    path.delete(id);

    ranks.set(id, rank);
    return rank;
  };

  for (const participant of participants) rankOf(participant.id, new Set());
  return ranks;
}

/**
 * Places every node for the `layered` layout.
 *
 * One column per declared layer, participants stacked inside their column. A group taller than
 * {@link MAX_ROWS} wraps into sub-columns instead of growing downwards: an environment with a dozen
 * endpoints per system is otherwise a single 1500px-tall ribbon, and fitting that into the viewport
 * shrinks the cards until nothing on them is readable. Wrapping trades unused horizontal space —
 * which a three-column graph has plenty of — for a shape that fits.
 *
 * Columns are laid out left to right by declared layer, so the x axis *is* the hierarchy.
 */
function layOutColumns(columns: Map<number, TopoCombo[]>, byCombo: Map<string, TopoNode[]>): void {
  const ordered = [...columns.keys()].sort((a, b) => a - b);
  let x = 0;

  for (const layer of ordered) {
    const members = columns.get(layer) ?? [];
    const blocks = members.map((combo) => {
      const nodes = (byCombo.get(combo.id) ?? []).slice().sort(compareEndpoints);
      const subColumns = Math.max(1, Math.ceil(nodes.length / MAX_ROWS));
      const rows = Math.ceil(nodes.length / subColumns);
      return {
        nodes,
        subColumns,
        rows,
        width: subColumns * (NODE_W + SUB_COL_GAP) - SUB_COL_GAP,
        height: rows * (NODE_H + ROW_GAP) - ROW_GAP,
      };
    });

    const columnWidth = Math.max(...blocks.map((block) => block.width), NODE_W);
    const columnHeight =
      blocks.reduce((sum, block) => sum + block.height, 0) + (blocks.length - 1) * GROUP_GAP;

    let y = -columnHeight / 2;
    for (const block of blocks) {
      block.nodes.forEach((node, index) => {
        const subColumn = Math.floor(index / block.rows);
        const row = index % block.rows;
        node.x = x + subColumn * (NODE_W + SUB_COL_GAP) + NODE_W / 2;
        node.y = y + row * (NODE_H + ROW_GAP) + NODE_H / 2;
      });
      y += block.height + GROUP_GAP;
    }

    x += columnWidth + COL_GAP;
  }
}


/**
 * Places every node for the `cluster` layout: each participant becomes a compact block, and the
 * blocks are packed into a rough square.
 *
 * Blocks are laid out in declared-hierarchy order and packed row by row, so a participant keeps
 * roughly the same neighbours as you switch between this and the layered view. Rows are top-aligned and
 * column positions come from the widest block in that column, which keeps the combo boxes from
 * touching — the failure mode that made G6's own `combo-combined` layout unusable here.
 */
function layOutClusters(combos: TopoCombo[], byCombo: Map<string, TopoNode[]>): void {
  const blocks = combos.map((combo) => {
    const nodes = (byCombo.get(combo.id) ?? []).slice().sort(compareEndpoints);
    const columns = Math.min(CLUSTER_MAX_COLS, Math.max(1, Math.ceil(Math.sqrt(nodes.length))));
    const rows = Math.ceil(nodes.length / columns);
    return {
      nodes,
      columns,
      rows,
      width: columns * (NODE_W + SUB_COL_GAP) - SUB_COL_GAP,
      height: rows * (NODE_H + ROW_GAP) - ROW_GAP,
    };
  });
  if (blocks.length === 0) return;

  const perRow = Math.ceil(Math.sqrt(blocks.length));
  let y = 0;

  for (let start = 0; start < blocks.length; start += perRow) {
    const row = blocks.slice(start, start + perRow);
    let x = 0;
    for (const block of row) {
      block.nodes.forEach((node, index) => {
        node.clusterX = x + (index % block.columns) * (NODE_W + SUB_COL_GAP) + NODE_W / 2;
        node.clusterY = y + Math.floor(index / block.columns) * (NODE_H + ROW_GAP) + NODE_H / 2;
      });
      x += block.width + CLUSTER_GAP;
    }
    y += Math.max(...row.map((block) => block.height)) + CLUSTER_GAP;
  }
}

/** Stable in-column ordering: service, then instance, then scheme. Placeholders sort first. */
function compareEndpoints(a: TopoNode, b: TopoNode): number {
  if (!a.endpoint || !b.endpoint) return Number(Boolean(a.endpoint)) - Number(Boolean(b.endpoint));
  return (
    a.endpoint.service.localeCompare(b.endpoint.service, undefined, { numeric: true }) ||
    a.endpoint.instance.localeCompare(b.endpoint.instance) ||
    a.endpoint.scheme.localeCompare(b.endpoint.scheme)
  );
}
