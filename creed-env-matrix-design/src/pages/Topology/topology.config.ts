/**
 * Geometry for the two hand-placed topology layouts.
 *
 * The hierarchy itself is **no longer declared here** — it lives in `env_app_link`, one row per
 * declared connection, edited from the config page and scoped to a tier. `buildGraph.ts` ranks the
 * app systems from those links (see `rankAppSystems`), so changing the picture is a data edit
 * rather than a code change.
 */

/**
 * Node box geometry, shared by the custom G6 element and the layout maths.
 *
 * Wide enough for the two-column card at its worst case: the longest service name in the seed data
 * (`TencentTeir1`) next to `https` on line one, and a full `ip:port` next to `Green2` on line two.
 * Narrower and the two halves of a line collide — canvas text does not wrap or ellipsize on its own.
 */
export const NODE_W = 196;
export const NODE_H = 52;

/** Layered-layout spacing: between columns, between rows, and between app-system groups in a column. */
export const COL_GAP = 150;
export const ROW_GAP = 16;
export const GROUP_GAP = 52;

/** Spacing between the wrapped sub-columns of one app system. */
export const SUB_COL_GAP = 22;

/**
 * How tall an app-system group may grow before it wraps into another sub-column.
 *
 * Six rows is roughly what stays legible in the 620px canvas without the fit-to-view zoom biting.
 */
export const MAX_ROWS = 6;

/** Cluster layout: widest a single app-system block gets, and the gutter between blocks. */
export const CLUSTER_MAX_COLS = 3;
export const CLUSTER_GAP = 90;
