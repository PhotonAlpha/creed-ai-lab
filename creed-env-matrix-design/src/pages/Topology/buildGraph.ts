import type { AppLink, ConflictGroup, Endpoint, LinkDirection } from '../../api/types';
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
 * - `dep`   — a declared `env_app_link` row, drawn combo-to-combo and edited from the config page.
 * - `colo`  — derived: two endpoints answer on the same `host`, i.e. the same box.
 * - `alias` — derived: two different hostnames resolve to the same `ip`. The clash DNS hides.
 * - `clash` — straight from `/conflicts`: two endpoints claim the same `host:port` or `ip:port`.
 */
export type EdgeKind = 'dep' | 'colo' | 'alias' | 'clash';

export const EDGE_KINDS: readonly EdgeKind[] = ['dep', 'colo', 'alias', 'clash'];

/**
 * `layered` — one column per declared layer, so the x axis is the hierarchy. The primary view.
 * `cluster` — the same app-system groups packed as compact blocks, for reading one system at a
 *             time rather than the flow between them.
 *
 * Both are positioned here rather than by a G6 layout, and the graph runs no layout at all.
 * `combo-combined`, the obvious candidate for the clustered view, lays each combo out independently
 * and then overlaps the boxes; and no generic layout knows about the declared app-system ranking,
 * which is the entire point of the layered view.
 *
 * A circular layout was tried and dropped: the nodes here are 196px cards, not dots, so a ring of
 * one environment's forty endpoints is some 3000px across and fit-to-view shrinks the labels out of
 * legibility. Round layouts need round nodes.
 */
export type TopologyLayout = 'layered' | 'cluster';

export interface TopoNode {
  id: string;
  comboId: string;
  appSystem: string;
  layer: number;
  /**
   * `null` for a placeholder — an app system named in the declared links that has no endpoint in
   * this slice. Drawing it anyway is the point: a system wired into the topology with nothing
   * recorded in the matrix is exactly the gap this viewer exists to surface.
   */
  endpoint: Endpoint | null;
  /** Position in the `layered` layout. */
  x: number;
  y: number;
  /** Position in the `cluster` layout. */
  clusterX: number;
  clusterY: number;
}

export interface TopoCombo {
  id: string;
  appSystem: string;
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
  /** App systems with endpoints but no declared link — surfaced as a hint in the UI. */
  unlinked: string[];
  /** App systems declared in the links with no endpoint in this slice — drawn as placeholders. */
  placeholders: string[];
}

export const nodeIdOf = (endpoint: Endpoint) => `e:${endpoint.id}`;
export const comboIdOf = (appSystem: string) => `app:${appSystem}`;
const placeholderIdOf = (appSystem: string) => `ghost:${appSystem}`;

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
  links: AppLink[],
): TopologyModel {
  const nodes: TopoNode[] = endpoints.map((endpoint) => ({
    id: nodeIdOf(endpoint),
    comboId: comboIdOf(endpoint.appSystem),
    appSystem: endpoint.appSystem,
    layer: 0,
    endpoint,
    x: 0,
    y: 0,
    clusterX: 0,
    clusterY: 0,
  }));

  // Placeholders for app systems that the wiring names but the matrix does not cover yet. Without
  // them a declared link would simply vanish, which reads as "the graph is wrong" rather than
  // "these endpoints have not been recorded".
  const withEndpoints = new Set(nodes.map((node) => node.appSystem));
  const placeholders = [...new Set(links.flatMap((link) => [link.sourceApp, link.targetApp]))]
    .filter((appSystem) => !withEndpoints.has(appSystem))
    .sort((a, b) => a.localeCompare(b));
  for (const appSystem of placeholders) {
    nodes.push({
      id: placeholderIdOf(appSystem),
      comboId: comboIdOf(appSystem),
      appSystem,
      layer: 0,
      endpoint: null,
      x: 0,
      y: 0,
      clusterX: 0,
      clusterY: 0,
    });
  }

  const nodeById = new Map(nodes.map((node) => [node.id, node]));
  const byApp = groupBy(nodes, (node) => node.appSystem);
  const ranks = rankAppSystems([...byApp.keys()], links);

  for (const node of nodes) {
    node.layer = ranks.get(node.appSystem) ?? 0;
  }

  const combos: TopoCombo[] = [...byApp.entries()]
    .map(([appSystem, members]) => ({
      id: comboIdOf(appSystem),
      appSystem,
      layer: ranks.get(appSystem) ?? 0,
      count: members.filter((member) => member.endpoint !== null).length,
    }))
    .sort((a, b) => a.layer - b.layer || a.appSystem.localeCompare(b.appSystem));

  const linked = new Set(links.flatMap((link) => [link.sourceApp, link.targetApp]));
  const unlinked = combos
    .filter((combo) => combo.count > 0 && !linked.has(combo.appSystem))
    .map((combo) => combo.appSystem);

  // ---- positions ----
  const columns = new Map<number, TopoCombo[]>();
  for (const combo of combos) {
    const bucket = columns.get(combo.layer);
    if (bucket) bucket.push(combo);
    else columns.set(combo.layer, [combo]);
  }
  layOutColumns(columns, byApp);
  layOutClusters(combos, byApp);

  // ---- edges ----
  const edges: TopoEdge[] = [];
  const seen = new Set<string>();

  // dep: one edge per declared link, drawn between the app-system boxes. Links naming a system that
  // is not on the graph at all cannot be drawn and are dropped.
  const onGraph = new Set(combos.map((combo) => combo.appSystem));
  for (const link of links) {
    if (!onGraph.has(link.sourceApp) || !onGraph.has(link.targetApp)) continue;
    const id = `dep:${link.id}`;
    if (seen.has(id)) continue;
    seen.add(id);
    edges.push({
      id,
      source: comboIdOf(link.sourceApp),
      target: comboIdOf(link.targetApp),
      kind: 'dep',
      direction: link.direction,
      reason: link.note?.trim()
        ? link.note
        : `${link.sourceApp} ${link.direction === 'BIDIRECTIONAL' ? '<->' : '->'} ${link.targetApp}`,
    });
  }

  // Derived edges only ever join real endpoints, so placeholders are excluded up front.
  const real = nodes.filter((node): node is TopoNode & { endpoint: Endpoint } => node.endpoint !== null);

  // colo: same host. Ordered by port so the chain is stable across reloads.
  for (const [host, members] of groupBy(real, (node) => node.endpoint.host)) {
    if (members.length < 2) continue;
    const ordered = members.slice().sort((a, b) => a.endpoint.port - b.endpoint.port);
    chain(ordered.map((n) => n.id), 'colo', `host ${host}`, seen, edges);
  }

  // alias: one ip, several hostnames. One representative endpoint per hostname — chaining every
  // endpoint would just repeat the colo edges that already cover the same box.
  for (const [ip, members] of groupBy(real, (node) => node.endpoint.ip)) {
    const perHost = new Map<string, TopoNode>();
    for (const node of members) {
      if (!perHost.has(node.endpoint.host)) perHost.set(node.endpoint.host, node);
    }
    if (perHost.size < 2) continue;
    const ordered = [...perHost.values()].sort((a, b) =>
      (a.endpoint?.host ?? '').localeCompare(b.endpoint?.host ?? ''),
    );
    chain(ordered.map((n) => n.id), 'alias', `ip ${ip}`, seen, edges);
  }

  // clash: the backend already decided what collides and within which scope — this only draws it.
  // Members outside the current node set are dropped: `/conflicts` and `/endpoints` are asked for
  // the same filter, but a group can still name a row the endpoint request has not delivered.
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

  return { nodes, combos, edges, nodeById, counts, unlinked, placeholders };
}

/**
 * Assigns each app system a column from the declared links: longest path from a system with no
 * declared upstream.
 *
 * Only the stored `sourceApp -> targetApp` orientation counts, whatever the direction flag says.
 * Treating a `BIDIRECTIONAL` link as an edge both ways would make every two-way pair a two-cycle
 * with no defined layering, and the operator drew the orientation for a reason.
 *
 * Cycles are still possible — nothing stops someone declaring A -> B -> C -> A — so the walk carries
 * its own path and ignores an edge that closes back onto it. That drops the *ranking* contribution
 * of one edge in the cycle; the edge itself is still drawn.
 */
function rankAppSystems(appSystems: string[], links: AppLink[]): Map<string, number> {
  const upstream = new Map<string, string[]>();
  for (const app of appSystems) upstream.set(app, []);
  for (const link of links) {
    if (!upstream.has(link.targetApp) || !upstream.has(link.sourceApp)) continue;
    upstream.get(link.targetApp)!.push(link.sourceApp);
  }

  const ranks = new Map<string, number>();
  const rankOf = (app: string, path: Set<string>): number => {
    const cached = ranks.get(app);
    if (cached !== undefined) return cached;
    if (path.has(app)) return 0;

    path.add(app);
    const sources = upstream.get(app) ?? [];
    const rank = sources.length === 0
      ? 0
      : Math.max(...sources.map((source) => rankOf(source, path) + 1));
    path.delete(app);

    ranks.set(app, rank);
    return rank;
  };

  for (const app of appSystems) rankOf(app, new Set());
  return ranks;
}

/**
 * Places every node for the `layered` layout.
 *
 * One column per declared layer, app systems stacked inside their column. A group taller than
 * {@link MAX_ROWS} wraps into sub-columns instead of growing downwards: an environment with a dozen
 * endpoints per system is otherwise a single 1500px-tall ribbon, and fitting that into the viewport
 * shrinks the cards until nothing on them is readable. Wrapping trades unused horizontal space —
 * which a three-column graph has plenty of — for a shape that fits.
 *
 * Columns are laid out left to right by declared layer, so the x axis *is* the hierarchy.
 */
function layOutColumns(columns: Map<number, TopoCombo[]>, byApp: Map<string, TopoNode[]>): void {
  const ordered = [...columns.keys()].sort((a, b) => a - b);
  let x = 0;

  for (const layer of ordered) {
    const members = columns.get(layer) ?? [];
    const blocks = members.map((combo) => {
      const nodes = (byApp.get(combo.appSystem) ?? []).slice().sort(compareEndpoints);
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
 * Places every node for the `cluster` layout: each app system becomes a compact block, and the
 * blocks are packed into a rough square.
 *
 * Blocks are laid out in declared-hierarchy order and packed row by row, so a system keeps roughly
 * the same neighbours as you switch between this and the layered view. Rows are top-aligned and
 * column positions come from the widest block in that column, which keeps the combo boxes from
 * touching — the failure mode that made G6's own `combo-combined` layout unusable here.
 */
function layOutClusters(combos: TopoCombo[], byApp: Map<string, TopoNode[]>): void {
  const blocks = combos.map((combo) => {
    const nodes = (byApp.get(combo.appSystem) ?? []).slice().sort(compareEndpoints);
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
