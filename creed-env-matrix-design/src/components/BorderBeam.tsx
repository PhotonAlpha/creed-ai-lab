import type { CSSProperties, ReactNode } from 'react';
import { theme } from 'antd';
import styles from './BorderBeam.module.css';

/**
 * A card framed by a beam of light running along its border (Magic UI's "Border Beam"). Wraps the card instead of
 * sitting inside it: a Card's body can be hidden (a folded section) and the beam must stay. The colours default to
 * the theme's primary blue fading into purple, so dark mode and a changed primary colour follow.
 *
 * @param radius   the wrapped card's corner radius - Card uses `borderRadiusLG` (the default), ProCard `borderRadius`
 * @param delay    seconds; staggers several beams on one page so they do not run in step
 * @param duration seconds for one lap
 */
export function BorderBeam({ children, radius, size = 160, duration = 8, delay = 0, colorFrom, colorTo }: {
  children: ReactNode;
  radius?: number;
  size?: number;
  duration?: number;
  delay?: number;
  colorFrom?: string;
  colorTo?: string;
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
    '--beam-from': colorFrom ?? token.colorPrimary,
    '--beam-to': colorTo ?? token.purple,
  } as CSSProperties;
  return (
    <div className={styles.frame} style={vars}>
      {children}
      <span className={styles.beam} aria-hidden />
    </div>
  );
}
