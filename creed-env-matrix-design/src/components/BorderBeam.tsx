import type { CSSProperties, ReactNode } from 'react';
import { theme } from 'antd';
import styles from './BorderBeam.module.css';

export type BeamStop = { color: string; percent: number };

export const BEAM_COLORS: BeamStop[] = [
  { color: '#22c55e', percent: 0 },
  { color: '#a3e635', percent: 54 },
  { color: '#facc15', percent: 100 },
];

/** The stops squeezed into the first half of the beam: the second half is the fade to transparent, as before. */
const stops = (colors: BeamStop[]) => [...colors]
  .sort((a, b) => a.percent - b.percent)
  .map(c => `${c.color} ${Math.min(Math.max(c.percent, 0), 100) / 2}%`)
  .join(', ');

/**
 * A card framed by a beam of light running along its border (Magic UI's "Border Beam"). Wraps the card instead of
 * sitting inside it: a Card's body can be hidden (a folded section) and the beam must stay. The colours default to
 * green -> lime -> yellow (asked for, 2026-11-17), fixed rather than from the theme.
 *
 * @param radius   the wrapped card's corner radius - Card uses `borderRadiusLG` (the default), ProCard `borderRadius`
 * @param delay    seconds; staggers several beams on one page so they do not run in step
 * @param duration seconds for one lap
 * @param colors   gradient stops from the beam's head (0) to its tail (100), antd Progress' `strokeColor` shape; the
 *                 beam then fades out behind the last one
 */
export function BorderBeam({ children, radius, size = 160, duration = 8, delay = 0, colors = BEAM_COLORS }: {
  children: ReactNode;
  radius?: number;
  size?: number;
  duration?: number;
  delay?: number;
  colors?: BeamStop[];
}) {
  const { token } = theme.useToken();
  const vars = {
    '--beam-radius': `${radius ?? token.borderRadiusLG}px`,
    // twice the card's border: a 1px beam all but vanishes along a page-wide card
    '--beam-width': `${token.lineWidth * 2}px`,
    '--beam-size': `${size}px`,
    '--beam-duration': `${duration}s`,
    // negative: every beam already under way at first paint, none waiting at the corner
    '--beam-delay': `${-delay}s`,
    '--beam-stops': stops(colors),
  } as CSSProperties;
  return (
    <div className={styles.frame} style={vars}>
      {children}
      <span className={styles.beam} aria-hidden />
    </div>
  );
}
