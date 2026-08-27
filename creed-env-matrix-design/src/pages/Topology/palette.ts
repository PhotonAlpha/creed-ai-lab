import type { GlobalToken } from 'antd';
import type { HealthState } from '../../api/types';
import type { EdgeKind } from './buildGraph';

/**
 * The graph's colours, derived from the antd theme rather than hard-coded.
 *
 * G6 renders to canvas and knows nothing about antd's CSS variables, so the tokens have to be read
 * with `theme.useToken()` and handed over as plain values — the same bridge `index.css` makes for
 * the matrix cells, just in the other direction. Keeping it in one module means the legend in the
 * toolbar and the detail panel cannot drift from the lines actually drawn.
 */
export function healthColors(token: GlobalToken): Record<HealthState, string> {
  return {
    UP: token.colorSuccess,
    DEGRADED: token.colorWarning,
    DOWN: token.colorError,
    UNKNOWN: token.colorTextQuaternary,
  };
}

export function edgeColors(token: GlobalToken): Record<EdgeKind, string> {
  return {
    dep: token.colorTextTertiary,
    colo: token.colorTextQuaternary,
    alias: token.colorInfo,
    clash: token.colorError,
  };
}

/**
 * Deterministic colour per app system: hashed from the name, not taken from the render order, so
 * filtering one system out of the view does not reshuffle the colours of the others.
 */
export function comboColor(token: GlobalToken, appSystem: string): string {
  const palette = [token.blue6, token.purple6, token.cyan6, token.magenta6, token.gold6, token.lime6];
  let hash = 0;
  for (let i = 0; i < appSystem.length; i += 1) {
    hash = (hash * 31 + appSystem.charCodeAt(i)) | 0;
  }
  return palette[Math.abs(hash) % palette.length];
}
