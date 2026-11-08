/** Veracode's scale, highest first; anything else sorts last. Same order as the backend's `Severity`. */
const SEVERITY_RANK: Record<string, number> = {
  'very high': 5,
  critical: 5,
  high: 4,
  medium: 3,
  low: 2,
  'very low': 1,
  informational: 0,
};
export const severityRank = (s: string | null) => (s ? SEVERITY_RANK[s.trim().toLowerCase()] ?? -1 : -1);
/** Tag colour by rank (Informational 0 … Very High 5) — the workbook's severity fills use the same hues. */
export const SEVERITY_COLOR = ['default', 'blue', 'gold', 'orange', 'volcano', 'red'];
export const severityColor = (s: string | null) => SEVERITY_COLOR[Math.max(0, severityRank(s))];
