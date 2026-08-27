import { Rect as GRect } from '@antv/g';
import type { Group } from '@antv/g';
import { ExtensionCategory, Label, Rect, getExtension, register } from '@antv/g6';
import type { RectStyleProps } from '@antv/g6';

/**
 * A card node: health stripe, service name, `ip:port`, instance.
 *
 * The built-in `rect` node draws a single centred label, which cannot express two lines at two type
 * sizes plus a status stripe. Extending `Rect` and appending shapes in `render` keeps everything on
 * G6's canvas — the `html` node type would have given the same card as real DOM, but a DOM node per
 * endpoint is exactly the cost this module chose G6 to avoid.
 */
export interface EndpointNodeStyleProps extends RectStyleProps {
  /** Health colour, painted as the 4px stripe down the left edge. */
  accentFill?: string;
  /** Line one, left: the service name. */
  titleText?: string;
  titleFill?: string;
  /** Line one, right: the scheme. */
  tagText?: string;
  /** Line two, left: `ip:port`. */
  subText?: string;
  subFill?: string;
  /** Line two, right: the instance. */
  metaText?: string;
  metaFill?: string;
}

const MONO = 'ui-monospace, SFMono-Regular, Menlo, Consolas, monospace';
const SANS = '-apple-system, BlinkMacSystemFont, "Segoe UI", Roboto, "PingFang SC", sans-serif';

export class EndpointNode extends Rect {
  public render(attributes = this.parsedAttributes, container: Group = this): void {
    super.render(attributes, container);

    const style = attributes as Required<EndpointNodeStyleProps>;
    const [width, height] = this.getSize(attributes);
    // The `inactive` state dims a node by setting `opacity` on it. G6 applies that to the shapes it
    // draws itself, but shapes appended here are ours to fade — without this the card washes out
    // while its text stays crisp, and the "everything else is dimmed" cue reads as a rendering bug.
    const { opacity } = style;
    const left = -width / 2;
    const right = width / 2;
    const textLeft = left + 14;

    this.upsert(
      'accent',
      GRect,
      {
        x: left,
        y: -height / 2,
        width: 4,
        height,
        // Only the outer corners are rounded, so the stripe sits flush inside the card's radius.
        radius: [4, 0, 0, 4],
        fill: style.accentFill,
        opacity,
      },
      container,
    );

    this.upsert(
      'title',
      Label,
      {
        x: textLeft,
        y: -10,
        text: style.titleText ?? '',
        fill: style.titleFill,
        fontSize: 13,
        fontWeight: 600,
        fontFamily: SANS,
        textAlign: 'left',
        textBaseline: 'middle',
        opacity,
      },
      container,
    );

    this.upsert(
      'tag',
      Label,
      {
        x: right - 12,
        y: -10,
        text: style.tagText ?? '',
        fill: style.metaFill,
        fontSize: 9,
        fontFamily: MONO,
        textAlign: 'right',
        textBaseline: 'middle',
        opacity,
      },
      container,
    );

    this.upsert(
      'sub',
      Label,
      {
        x: textLeft,
        y: 11,
        text: style.subText ?? '',
        fill: style.subFill,
        fontSize: 10,
        fontFamily: MONO,
        textAlign: 'left',
        textBaseline: 'middle',
        opacity,
      },
      container,
    );

    this.upsert(
      'meta',
      Label,
      {
        x: right - 12,
        y: 11,
        text: style.metaText ?? '',
        fill: style.metaFill,
        fontSize: 9,
        fontFamily: MONO,
        textAlign: 'right',
        textBaseline: 'middle',
        opacity,
      },
      container,
    );
  }
}

/**
 * Registered at module load, not inside the component: `register` writes to a registry global to
 * G6, so doing it per mount would re-register the same type on StrictMode's second pass. The guard
 * covers the other repeat path — Vite replacing this module on hot reload — which G6 otherwise
 * reports as "the extension has been registered before".
 */
export const ENDPOINT_NODE_TYPE = 'env-endpoint';

if (!getExtension(ExtensionCategory.NODE, ENDPOINT_NODE_TYPE)) {
  register(ExtensionCategory.NODE, ENDPOINT_NODE_TYPE, EndpointNode);
}
