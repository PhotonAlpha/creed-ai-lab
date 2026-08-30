import type {
  ConflictGroup,
  Endpoint,
  LinkDirection,
  ReleaseLink,
  ReleaseNode,
} from '../../api/types';
import { ANY_COUNTRY } from '../../api/types';
import {
  APP_GROUP_GAP,
  CLUSTER_BLOCKS_PER_ROW,
  CLUSTER_GAP,
  CLUSTER_MAX_COLS,
  COL_GAP,
  GROUP_GAP,
  MAX_LANE_NODES_VERTICAL,
  MAX_ROWS,
  NODE_H,
  NODE_W,
  ROW_BAND_GAP,
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

/**
 * Which way the declared hierarchy runs across the canvas.
 *
 * `LR` is the default and the one the ranking was written for: layer 0 on the left, arrows pointing
 * right. The other three are the same layering read along a different axis — the maths works in
 * (rank, cross) space and only the final mapping to x/y differs — because "upstream" is a direction
 * people argue about: a release chain reads left-to-right on a slide and top-to-bottom in a runbook.
 */
export type Orientation = 'LR' | 'RL' | 'TB' | 'BT';

export const ORIENTATIONS: readonly Orientation[] = ['LR', 'RL', 'TB', 'BT'];

/**
 * How to draw the graph. Everything that is *what the release says* comes in as data instead.
 *
 * The hand-placed hierarchy used to live here as a set of overrides read from `localStorage`; it is
 * now `env_release_node.layer` / `.sort_order`, saved with the rest of the topology. A layer pin is
 * a statement about the estate — "this slice is a step of its own" — and the one reader who worked
 * that out is exactly the person whose colleagues need to see it. What stays a browser preference is
 * only what does not change the picture's meaning: orientation and the app-system boxes.
 */
export interface TopologyOptions {
  orientation: Orientation;
  /** Draw (and pack) participants of one app system inside a shared box. */
  groupByApp: boolean;
}

export const DEFAULT_OPTIONS: TopologyOptions = {
  orientation: 'LR',
  groupByApp: true,
};

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
  /** The layer actually drawn: the pinned one when there is one, otherwise {@link derivedLayer}. */
  layer: number;
  /** What the links alone say, kept so the UI can show "derived 1 · pinned 3". */
  derivedLayer: number;
  /** Whether {@link layer} came from an override rather than from the links. */
  pinned: boolean;
  /** `env_release_node.sort_order`; `0` when nobody has reordered this release. */
  order: number;
  /** The app-system box this participant sits in, in the `layered` layout. */
  appGroupId: string;
  /** The app-system box this participant sits in, in the `cluster` layout. */
  clusterGroupId: string;
  count: number;
}

/**
 * One app-system cluster: the box drawn around every participant of the same app system.
 *
 * This is a **presentation** grouping and nothing more. A topology node is still a slice, for the
 * reason it always was — CCS can appear twice in one chain — so in the layered view the cluster is
 * per *(app system, layer)*: `SG CCS SIT3` in column 0 and `CN CCS SIT5` in column 2 are two boxes,
 * not one box stretched across the graph and over everything between them.
 */
export interface TopoAppGroup {
  id: string;
  appSystem: string;
  /** The band this cluster sits in; `null` in the `cluster` layout, which has no bands. */
  layer: number | null;
  /** Participant combo ids inside the box, in drawing order. */
  comboIds: string[];
  /** Real endpoints inside the box — placeholders excluded, as in {@link TopoCombo.count}. */
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
  /** App-system boxes for the `layered` layout — one per app system *per layer*. */
  appGroups: TopoAppGroup[];
  /** App-system boxes for the `cluster` layout — one per app system. */
  clusterGroups: TopoAppGroup[];
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
/** Layered: one box per app system *and layer*; cluster: one per app system. See {@link TopoAppGroup}. */
export const appGroupIdOf = (appSystem: string, layer: number | null) =>
  layer === null ? `app:${appSystem}` : `app:${appSystem}@${layer}`;
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
  options: TopologyOptions = DEFAULT_OPTIONS,
): TopologyModel {
  const { orientation, groupByApp } = options;
  const derived = rankParticipants(participants, releaseLinks);

  /*
   * A stored `layer` replaces one participant's rank and nothing else — the participants downstream
   * of it keep the layer the links gave them.
   *
   * Re-deriving the whole graph from a pin is the obvious alternative and it is worse: moving one
   * box one column to the right would shunt half the picture with it, and whoever pinned it was
   * saying "the longest path got *this one* wrong", not "re-rank everything". `null` is the unpinned
   * state and cannot be spelt `0`, which means "column 0".
   */
  const pinnedBy = new Map(participants.map((participant) => [participant.id, participant.layer]));
  const layerOf = (participantId: number) =>
    pinnedBy.get(participantId) ?? derived.get(participantId) ?? 0;
  const orderBy = new Map(participants.map((participant) => [participant.id, participant.sortOrder ?? 0]));
  const orderOf = (participantId: number) => orderBy.get(participantId) ?? 0;
  const ranks = new Map(participants.map((participant) => [participant.id, layerOf(participant.id)]));

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

  /*
   * An app system's place along the cross axis is the *lowest* order key any of its participants
   * carries. Ordering is declared per participant — that is the row the viewer has in front of them
   * in the layer editor — but the app systems have to move as blocks, or reordering one slice
   * would tear its own cluster in half and the box around it would have to span the gap.
   */
  const appOrder = new Map<string, number>();
  for (const participant of participants) {
    const key = orderOf(participant.id);
    const current = appOrder.get(participant.appSystem);
    if (current === undefined || key < current) appOrder.set(participant.appSystem, key);
  }

  const combos: TopoCombo[] = participants
    .map((participant) => ({
      id: comboIdOf(participant.id),
      participantId: participant.id,
      appSystem: participant.appSystem,
      country: participant.country,
      envInstance: participant.envInstance,
      title: titleOf(participant),
      layer: ranks.get(participant.id) ?? 0,
      derivedLayer: derived.get(participant.id) ?? 0,
      pinned: participant.layer != null,
      order: orderOf(participant.id),
      appGroupId: appGroupIdOf(participant.appSystem, layerOf(participant.id)),
      clusterGroupId: appGroupIdOf(participant.appSystem, null),
      count: (membersOf.get(participant.id) ?? []).filter((m) => m.endpoint !== null).length,
    }))
    // This order *is* the drawing order: the layouts walk the list as it comes, so app systems have
    // to be contiguous here or their boxes would have to enclose a neighbour's participants.
    .sort(
      (a, b) =>
        a.layer - b.layer ||
        (appOrder.get(a.appSystem) ?? 0) - (appOrder.get(b.appSystem) ?? 0) ||
        a.appSystem.localeCompare(b.appSystem) ||
        a.order - b.order ||
        a.title.localeCompare(b.title),
    );

  const appGroups = collectAppGroups(combos, (combo) => combo.appGroupId, true);
  const clusterGroups = collectAppGroups(combos, (combo) => combo.clusterGroupId, false);

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
  const bands = new Map<number, TopoCombo[]>();
  for (const combo of combos) {
    const bucket = bands.get(combo.layer);
    if (bucket) bucket.push(combo);
    else bands.set(combo.layer, [combo]);
  }
  layOutLayered(bands, byCombo, orientation, groupByApp);
  layOutClusters(clusterGroups, byCombo, groupByApp);

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

  return {
    nodes,
    combos,
    appGroups,
    clusterGroups,
    edges,
    nodeById,
    counts,
    unlinked,
    placeholders,
    unclaimed,
  };
}

/** Collapses the already-ordered combo list into app-system boxes, preserving that order. */
function collectAppGroups(
  combos: TopoCombo[],
  idOf: (combo: TopoCombo) => string,
  layered: boolean,
): TopoAppGroup[] {
  const groups = new Map<string, TopoAppGroup>();
  for (const combo of combos) {
    const id = idOf(combo);
    const group = groups.get(id);
    if (group) {
      group.comboIds.push(combo.id);
      group.count += combo.count;
    } else {
      groups.set(id, {
        id,
        appSystem: combo.appSystem,
        layer: layered ? combo.layer : null,
        comboIds: [combo.id],
        count: combo.count,
      });
    }
  }
  return [...groups.values()];
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
 * The maths runs in **(rank, cross)** space and only the last step knows about x and y: `rank` is
 * the hierarchy axis — one band per declared layer — and `cross` is the axis participants stack
 * along inside a band. `LR` maps rank to x, `TB` maps it to y, and the two reversed orientations
 * negate it. Writing the four orientations as four layouts was the alternative and it duplicates
 * every gap, wrap and centring decision four times over.
 *
 * The card is 196 × 52, so which axis a group grows along matters: a band wraps into another *lane*
 * (offset along rank) once a participant reaches {@link MAX_ROWS} cards vertically or
 * {@link MAX_LANE_NODES_VERTICAL} horizontally. Without the wrap, an environment with a dozen
 * endpoints per system is a single 1500px ribbon and fitting it into the viewport shrinks the cards
 * until nothing on them is readable — unused space on the other axis is the cheaper thing to spend.
 *
 * Participants of one app system are adjacent (the combo list is already sorted that way) and get
 * the ordinary {@link GROUP_GAP} between them; a different app system starts after the wider
 * {@link APP_GROUP_GAP}, which is what leaves room for the box drawn around the cluster.
 */
function layOutLayered(
  bands: Map<number, TopoCombo[]>,
  byCombo: Map<string, TopoNode[]>,
  orientation: Orientation,
  groupByApp: boolean,
): void {
  const horizontal = orientation === 'LR' || orientation === 'RL';
  // Along `rank` a card takes its width when the flow is horizontal, its height when it is vertical.
  const rankSize = horizontal ? NODE_W : NODE_H;
  const crossSize = horizontal ? NODE_H : NODE_W;
  const crossGap = horizontal ? ROW_GAP : SUB_COL_GAP;
  const bandGap = horizontal ? COL_GAP : ROW_BAND_GAP;
  const perLane = horizontal ? MAX_ROWS : MAX_LANE_NODES_VERTICAL;
  const clusterGap = groupByApp ? APP_GROUP_GAP : GROUP_GAP;

  let rankStart = 0;
  for (const layer of [...bands.keys()].sort((a, b) => a - b)) {
    const members = bands.get(layer) ?? [];
    const blocks = members.map((combo) => {
      const nodes = (byCombo.get(combo.id) ?? []).slice().sort(compareEndpoints);
      const lanes = Math.max(1, Math.ceil(nodes.length / perLane));
      const perLaneCount = Math.max(1, Math.ceil(nodes.length / lanes));
      return {
        combo,
        nodes,
        perLaneCount,
        rankExtent: lanes * (rankSize + SUB_COL_GAP) - SUB_COL_GAP,
        crossExtent: perLaneCount * (crossSize + crossGap) - crossGap,
      };
    });

    // Gap *before* each block after the first: same app system ⇒ tight, new one ⇒ a cluster gutter.
    const gaps = blocks
      .slice(1)
      .map((block, index) =>
        block.combo.appSystem === blocks[index].combo.appSystem ? GROUP_GAP : clusterGap,
      );
    const crossTotal =
      blocks.reduce((sum, block) => sum + block.crossExtent, 0) +
      gaps.reduce((sum, gap) => sum + gap, 0);

    let cross = -crossTotal / 2;
    blocks.forEach((block, index) => {
      if (index > 0) cross += gaps[index - 1];
      block.nodes.forEach((node, position) => {
        const lane = Math.floor(position / block.perLaneCount);
        const slot = position % block.perLaneCount;
        place(
          node,
          orientation,
          rankStart + lane * (rankSize + SUB_COL_GAP) + rankSize / 2,
          cross + slot * (crossSize + crossGap) + crossSize / 2,
        );
      });
      cross += block.crossExtent;
    });

    rankStart += Math.max(...blocks.map((block) => block.rankExtent), rankSize) + bandGap;
  }
}

/** The one place that turns (rank, cross) into a canvas position. */
function place(node: TopoNode, orientation: Orientation, rank: number, cross: number): void {
  switch (orientation) {
    case 'LR':
      node.x = rank;
      node.y = cross;
      break;
    case 'RL':
      node.x = -rank;
      node.y = cross;
      break;
    case 'TB':
      node.x = cross;
      node.y = rank;
      break;
    default:
      node.x = cross;
      node.y = -rank;
      break;
  }
}

/**
 * Places every node for the `cluster` layout: one **app system** per block, shelf-packed into a
 * roughly landscape sheet.
 *
 * Two levels, and both of them exist to keep a cluster a rectangle. Inside a cluster the
 * participants are packed in rows of {@link CLUSTER_BLOCKS_PER_ROW}; the clusters themselves are
 * then laid left to right and wrapped onto a new shelf once the row reaches the target width.
 *
 * Packing the *participants* freely — which is what this used to do — put one app system's slices
 * in two different rows with a stranger between them, and the box drawn around that cluster then
 * had to reach across the stranger and overlap it. Stacking the clusters in a single column instead
 * is just as correct and unreadable: eight one-participant systems make a 1300 × 230 ribbon, and
 * fit-to-view shrinks the cards to nothing. The shelf gives back an aspect ratio the canvas can
 * actually show.
 */
function layOutClusters(
  groups: TopoAppGroup[],
  byCombo: Map<string, TopoNode[]>,
  groupByApp: boolean,
): void {
  const gap = groupByApp ? APP_GROUP_GAP : CLUSTER_GAP;

  // Phase one: lay each cluster out around its own origin and measure it.
  const laid = groups.map((group) => {
    const blocks = group.comboIds.map((comboId) => {
      const nodes = (byCombo.get(comboId) ?? []).slice().sort(compareEndpoints);
      const columns = Math.min(CLUSTER_MAX_COLS, Math.max(1, Math.ceil(Math.sqrt(nodes.length))));
      const rows = Math.ceil(nodes.length / columns);
      return {
        nodes,
        columns,
        width: columns * (NODE_W + SUB_COL_GAP) - SUB_COL_GAP,
        height: rows * (NODE_H + ROW_GAP) - ROW_GAP,
      };
    });

    let width = 0;
    let height = 0;
    for (let start = 0; start < blocks.length; start += CLUSTER_BLOCKS_PER_ROW) {
      const row = blocks.slice(start, start + CLUSTER_BLOCKS_PER_ROW);
      let x = 0;
      for (const block of row) {
        const top = height;
        block.nodes.forEach((node, index) => {
          node.clusterX = x + (index % block.columns) * (NODE_W + SUB_COL_GAP) + NODE_W / 2;
          node.clusterY = top + Math.floor(index / block.columns) * (NODE_H + ROW_GAP) + NODE_H / 2;
        });
        x += block.width + CLUSTER_GAP;
      }
      width = Math.max(width, x - CLUSTER_GAP);
      height += Math.max(...row.map((block) => block.height)) + CLUSTER_GAP;
    }

    return { blocks, width, height: Math.max(0, height - CLUSTER_GAP) };
  });

  /*
   * A target row width rather than a fixed number of clusters per row: the clusters here differ by
   * an order of magnitude — one placeholder against a six-endpoint system — and "three per row"
   * leaves either a row of stubs or one that runs off the canvas. The square root of the total area
   * is the width a perfect square would have; the 1.6 leans it landscape, which is the shape of the
   * canvas it has to fit into.
   *
   * The gutter counts as part of a cluster's footprint. Without it a sheet of one-participant
   * systems measures as almost no area at all — they are 230 × 52 cards separated by 130px of air —
   * and the target comes out narrower than a single row of them, so every cluster lands on its own
   * shelf and the whole thing is a column again.
   */
  const area = laid.reduce(
    (sum, group) => sum + (group.width + gap) * (group.height + gap),
    0,
  );
  const target = Math.max(
    Math.max(...laid.map((group) => group.width), 0),
    Math.sqrt(Math.max(area, 1)) * 1.6,
  );

  // Phase two: shelf-pack the measured clusters, shifting each one's nodes onto its shelf.
  let x = 0;
  let y = 0;
  let shelfHeight = 0;
  for (const group of laid) {
    if (x > 0 && x + group.width > target) {
      x = 0;
      y += shelfHeight + gap;
      shelfHeight = 0;
    }
    for (const block of group.blocks) {
      for (const node of block.nodes) {
        node.clusterX += x;
        node.clusterY += y;
      }
    }
    x += group.width + gap;
    shelfHeight = Math.max(shelfHeight, group.height);
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
