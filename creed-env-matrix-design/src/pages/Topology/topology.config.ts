/**
 * Geometry for the two hand-placed topology layouts.
 *
 * The hierarchy itself is **not declared here** — it lives in `env_release_link`, one row per
 * declared connection, edited from the config page and scoped to a release. `buildGraph.ts` ranks
 * the participants from those links (see `rankParticipants`), so changing the picture is a data
 * edit rather than a code change. A viewer may then pin a participant to a layer of their own; that
 * override is a per-browser view preference and never touches the release.
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

/**
 * Layered-layout spacing: between columns, between rows, and between participant groups in a column.
 *
 * `GROUP_GAP` is measured between the *cards*, but what has to fit in it is the combo box around
 * them: 18px of padding at each end plus the next box's own label, which G6 draws above it. Below
 * about 50 the label of one participant lands on the border of the one before it.
 */
export const COL_GAP = 150;
export const ROW_GAP = 16;
export const GROUP_GAP = 64;

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

/**
 * Band gap for a vertical hierarchy (`TB` / `BT`).
 *
 * Smaller than {@link COL_GAP}: a band's thickness along the flow axis is `NODE_H` (52) rather than
 * `NODE_W` (196), so the same 150px gutter that reads as "one step" between columns reads as a
 * canyon between rows.
 */
export const ROW_BAND_GAP = 110;

/**
 * How many cards a participant stacks across before wrapping, when the hierarchy runs vertically.
 *
 * The wrap axis swaps with the orientation: laid out top-to-bottom, a participant's cards sit side
 * by side and each one is 196px wide, so six of them is a 1300px-wide group. Three keeps a
 * participant roughly as wide as it is tall in the horizontal orientation.
 */
export const MAX_LANE_NODES_VERTICAL = 3;

/**
 * Gutter between two app-system clusters, and the padding of the box drawn around one.
 *
 * The padding has to clear the *child* combo's own label: participant boxes place their title above
 * themselves, and that title is drawn inside the parent's padding.
 *
 * The gutter is measured card to card, so everything between two clusters has to fit inside it:
 * 18 + 26 of padding leaving the first, then the next cluster's label, its own 26 and its child's
 * 18. Under about 110 the boxes touch and each cluster's title is drawn on its neighbour's border —
 * the same mush that made G6's own `combo-combined` layout unusable here, just one level up.
 */
export const APP_GROUP_GAP = 130;
export const APP_GROUP_PADDING = 26;

/** Cluster layout: how many participant blocks an app-system band holds before it wraps. */
export const CLUSTER_BLOCKS_PER_ROW = 4;
