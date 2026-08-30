import { useCallback, useState } from 'react';
import type { Orientation, TopologyLayout } from './buildGraph';

/**
 * How the graph is drawn, remembered in `localStorage`.
 *
 * Only what does not change the picture's *meaning* lives here. Which way the hierarchy runs and
 * whether app systems get a box are reading habits, and they should survive picking another release;
 * which layer a participant belongs in is a statement about the estate, so it lives on the release
 * itself (`env_release_node.layer` / `.sort_order`) where everyone opening it sees the same thing.
 */
export interface TopologyView {
  layout: TopologyLayout;
  orientation: Orientation;
  groupByApp: boolean;
}

const DEFAULT_VIEW: TopologyView = { layout: 'layered', orientation: 'LR', groupByApp: true };

const VIEW_KEY = 'env-matrix.topology.view';

/**
 * Merges a stored value over the defaults instead of trusting it whole.
 *
 * The shape here grows — `orientation` did not exist a version ago — and a browser that stored the
 * older shape must not come back with `orientation: undefined` and drop every node at `NaN`.
 * Anything unreadable (private mode, a hand-edited value, a half-written entry) falls back silently:
 * this is a display preference, and there is nothing useful to tell the user about losing one.
 */
function read(): TopologyView {
  try {
    const raw = localStorage.getItem(VIEW_KEY);
    if (!raw) return DEFAULT_VIEW;
    const parsed: unknown = JSON.parse(raw);
    if (!parsed || typeof parsed !== 'object') return DEFAULT_VIEW;
    return { ...DEFAULT_VIEW, ...(parsed as Partial<TopologyView>) };
  } catch {
    return DEFAULT_VIEW;
  }
}

export function useTopologyView() {
  const [view, setViewState] = useState<TopologyView>(read);

  const setView = useCallback((patch: Partial<TopologyView>) => {
    setViewState((previous) => {
      const next = { ...previous, ...patch };
      try {
        localStorage.setItem(VIEW_KEY, JSON.stringify(next));
      } catch {
        /* storage disabled or full — the preference simply does not persist */
      }
      return next;
    });
  }, []);

  return { view, setView };
}
