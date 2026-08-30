import { useEffect, useMemo, useRef } from 'react';
import { CanvasEvent, ComboEvent, Graph, NodeEvent } from '@antv/g6';
import type { ComboData, EdgeData, GraphData, ID, IPointerEvent, NodeData } from '@antv/g6';
import { Empty, Spin, theme } from 'antd';
import { ENDPOINT_NODE_TYPE } from './EndpointNode';
import { comboColor, edgeColors, healthColors } from './palette';
import { APP_GROUP_PADDING, NODE_H, NODE_W } from './topology.config';

/**
 * Canvas height. Deep enough that a six-row app-system group — the tallest a layout will build
 * before it wraps into another sub-column — fits without the fit-to-view zoom shrinking the cards
 * below readable.
 */
const CANVAS_HEIGHT = 620;

/**
 * The "no highlight" state, applied explicitly instead of clearing to `[]`.
 *
 * G6 5.1.1 stores the empty array in its data — `setElementState` even resolves successfully — but
 * the state-stage draw never repaints the element back to its base style, so the graph stayed dimmed
 * forever once the pointer had touched a node and left. Every state below therefore sets the *same*
 * properties, and `normal` puts each of them back; nothing is left to G6 to revert on its own.
 */
const NORMAL = 'normal';

/** Base combo look, duplicated into the `normal` state so it can be restored exactly. */
const COMBO_FILL_OPACITY = 0.05;
const COMBO_STROKE_OPACITY = 0.45;
import type { EdgeKind, TopoEdge, TopologyLayout, TopologyModel } from './buildGraph';

interface TopologyGraphProps {
  model: TopologyModel;
  layout: TopologyLayout;
  /** Draw a box around every participant of one app system, nesting the participant boxes inside. */
  groupByApp: boolean;
  visibleKinds: Record<EdgeKind, boolean>;
  selectedId: string | null;
  onSelect: (id: string | null) => void;
  /** Incremented by the toolbar's "fit" button; every change re-runs fit-to-view. */
  fitSignal: number;
  loading?: boolean;
  emptyText: string;
  /** Sub-label for a placeholder node, e.g. "no endpoints recorded". */
  placeholderText: string;
}

export function TopologyGraph({
  model,
  layout,
  groupByApp,
  visibleKinds,
  selectedId,
  onSelect,
  fitSignal,
  loading,
  emptyText,
  placeholderText,
}: TopologyGraphProps) {
  const { token } = theme.useToken();
  const containerRef = useRef<HTMLDivElement>(null);
  const graphRef = useRef<Graph | null>(null);
  const hoverRef = useRef<string | null>(null);
  /**
   * The render currently in flight.
   *
   * `graph.render()` is asynchronous and StrictMode unmounts the component between the call and its
   * resolution, so destroying eagerly makes G6 log "The graph instance has been destroyed" as its
   * own pipeline lands on a corpse. Waiting on this promise before destroying keeps teardown quiet.
   */
  const renderRef = useRef<Promise<unknown>>(Promise.resolve());

  const health = useMemo(() => healthColors(token), [token]);

  /** Dash pattern per kind; the colour half lives in `palette.ts` next to the legend's. */
  const edgeStyle = useMemo<Record<EdgeKind, Record<string, unknown>>>(() => {
    const color = edgeColors(token);
    return {
      dep: { stroke: color.dep, lineWidth: 2, endArrow: true, endArrowSize: 10 },
      colo: { stroke: color.colo, lineWidth: 1.4, lineDash: [6, 4] },
      alias: { stroke: color.alias, lineWidth: 1.4, lineDash: [2, 4] },
      clash: { stroke: color.clash, lineWidth: 2, lineDash: [4, 3] },
    };
  }, [token]);

  const data = useMemo<GraphData>(() => {
    const nodes: NodeData[] = model.nodes.map((node) => {
      const { endpoint } = node;
      const position =
        layout === 'layered' ? { x: node.x, y: node.y } : { x: node.clusterX, y: node.clusterY };

      // A placeholder — an app system the wiring names but the matrix has no endpoint for here.
      // Drawn hollow and dashed so it never reads as a recorded address.
      if (!endpoint) {
        return {
          id: node.id,
          combo: node.comboId,
          style: {
            ...position,
            size: [NODE_W, NODE_H],
            radius: token.borderRadius,
            fill: token.colorFillQuaternary,
            stroke: token.colorBorder,
            lineWidth: 1,
            lineDash: [5, 4],
            accentFill: token.colorTextQuaternary,
            titleText: model.combos.find((c) => c.id === node.comboId)?.title ?? '',
            titleFill: token.colorTextSecondary,
            subText: placeholderText,
            subFill: token.colorTextTertiary,
            metaFill: token.colorTextTertiary,
          },
        };
      }

      return {
        id: node.id,
        combo: node.comboId,
        style: {
          ...position,
          size: [NODE_W, NODE_H],
          radius: token.borderRadius,
          fill: token.colorBgContainer,
          stroke: endpoint.conflict ? token.colorError : token.colorBorderSecondary,
          lineWidth: endpoint.conflict ? 1.6 : 1,
          accentFill: health[endpoint.health],
          titleText: endpoint.service,
          titleFill: token.colorText,
          tagText: endpoint.scheme,
          subText: `${endpoint.ip}:${endpoint.port}`,
          subFill: token.colorTextSecondary,
          metaText: endpoint.instance,
          metaFill: token.colorTextTertiary,
        },
      };
    });

    const edges: EdgeData[] = model.edges
      .filter((edge) => visibleKinds[edge.kind])
      .map((edge) => ({
        id: edge.id,
        source: edge.source,
        target: edge.target,
        style: {
          ...edgeStyle[edge.kind],
          // A two-way link gets a head at both ends; everything derived is undirected anyway.
          ...(edge.direction === 'BIDIRECTIONAL' ? { startArrow: true, startArrowSize: 10 } : {}),
        },
      }));

    /*
     * App-system boxes first, then the participant boxes that name them as a parent.
     *
     * Both are plain combos — G6 nests a combo by its `combo` field exactly as it nests a node — and
     * both are drawn with the *same* fill and stroke opacity even though they look different. That
     * is not cosmetic: the hover states below set `fillOpacity`/`strokeOpacity` to fixed values, so
     * an element whose base value differs can never be put back (see NORMAL). The two levels are
     * told apart by a solid vs dashed stroke and the label size instead.
     */
    const groups = layout === 'layered' ? model.appGroups : model.clusterGroups;
    const parents: ComboData[] = groupByApp
      ? groups.map((group) => {
          const color = comboColor(token, group.appSystem);
          return {
            id: group.id,
            style: {
              type: 'rect',
              padding: APP_GROUP_PADDING,
              radius: 16,
              fill: color,
              fillOpacity: COMBO_FILL_OPACITY,
              stroke: color,
              strokeOpacity: COMBO_STROKE_OPACITY,
              lineWidth: 1.5,
              labelText: group.appSystem,
              labelPlacement: 'top',
              labelFill: color,
              labelFontSize: 13,
              labelFontWeight: 700,
            },
          };
        })
      : [];

    const combos: ComboData[] = [
      ...parents,
      ...model.combos.map((combo): ComboData => {
        const color = comboColor(token, combo.appSystem);
        return {
          id: combo.id,
          combo: groupByApp
            ? layout === 'layered'
              ? combo.appGroupId
              : combo.clusterGroupId
            : undefined,
          style: {
            type: 'rect',
            padding: 18,
            radius: 12,
            fill: color,
            fillOpacity: COMBO_FILL_OPACITY,
            stroke: color,
            strokeOpacity: COMBO_STROKE_OPACITY,
            lineWidth: 1,
            lineDash: [4, 4],
            labelText: combo.title,
            labelPlacement: 'top',
            labelFill: color,
            labelFontSize: 12,
            labelFontWeight: 600,
          },
        };
      }),
    ];

    return { nodes, edges, combos };
  }, [model, layout, groupByApp, visibleKinds, token, health, edgeStyle, placeholderText]);

  /*
   * The canvas is built once and its G6 event handlers live as long as it does, so anything they
   * call has to be reached through a ref. Reading `onSelect`/`applyStates` directly would freeze the
   * closures of the very first render — when `model` is still empty — and hovering would compute
   * states for a graph with no elements. Rebuilding the canvas on every parent render is not the
   * alternative: that throws away the user's pan and zoom.
   */
  const onSelectRef = useRef(onSelect);
  onSelectRef.current = onSelect;
  const applyStatesRef = useRef<() => void>(() => undefined);

  /* ---- instance lifecycle: created once, destroyed on unmount ---- */
  useEffect(() => {
    const container = containerRef.current;
    if (!container) return undefined;

    const graph = new Graph({
      container,
      autoResize: true,
      // Off deliberately. Elements fade in on render, and `setElementState` applied while that
      // tween is still running froze nodes at whatever opacity they had reached — the graph came up
      // looking permanently dimmed. There is nothing here worth animating anyway: the two layouts
      // are static, and the highlight states want to be instant.
      animation: false,
      autoFit: 'view',
      padding: 28,
      zoomRange: [0.2, 3],
      background: 'transparent',
      // Every state map below touches exactly the same properties in every branch, and `normal`
      // restores each of them. See NORMAL for why leaning on G6 to revert does not work.
      node: {
        type: ENDPOINT_NODE_TYPE,
        state: {
          [NORMAL]: { opacity: 1, shadowBlur: 0 },
          active: { opacity: 1, shadowBlur: 0 },
          // Selection is a glow, not a thicker border: `lineWidth` and `stroke` vary per node
          // (a conflicting endpoint is drawn red and thicker) and a state cannot restore a value
          // it does not know.
          selected: { opacity: 1, shadowBlur: 14, shadowColor: token.colorPrimary },
          inactive: { opacity: 0.18, shadowBlur: 0 },
        },
      },
      edge: {
        type: 'line',
        state: {
          [NORMAL]: { opacity: 1 },
          active: { opacity: 1 },
          selected: { opacity: 1 },
          inactive: { opacity: 0.1 },
        },
      },
      combo: {
        type: 'rect',
        // Group boxes keep their shape when dimmed instead of fading out with `opacity`: the map's
        // structure is what makes the highlighted node legible, so only the fill and the label
        // recede. Spelt out per channel because the combo fill is already at 5% alpha.
        state: {
          [NORMAL]: {
            fillOpacity: COMBO_FILL_OPACITY,
            strokeOpacity: COMBO_STROKE_OPACITY,
            labelOpacity: 1,
          },
          active: {
            fillOpacity: COMBO_FILL_OPACITY,
            strokeOpacity: COMBO_STROKE_OPACITY,
            labelOpacity: 1,
          },
          selected: {
            fillOpacity: COMBO_FILL_OPACITY,
            strokeOpacity: COMBO_STROKE_OPACITY,
            labelOpacity: 1,
          },
          inactive: { fillOpacity: 0.02, strokeOpacity: 0.15, labelOpacity: 0.3 },
        },
      },
      behaviors: [
        'drag-canvas',
        // Wheel-to-zoom would swallow the page scroll: this canvas sits below a filter bar and a
        // stat row, and a user scrolling down to reach the graph would zoom it instead of moving
        // the page. Gating on Ctrl leaves the plain wheel to the document, as embedded maps do.
        { type: 'zoom-canvas', trigger: ['Control'] },
        'drag-element',
      ],
    });
    graphRef.current = graph;

    const pick = (event: IPointerEvent) => String((event.target as { id?: ID }).id ?? '');
    graph.on(NodeEvent.CLICK, (event: IPointerEvent) => onSelectRef.current(pick(event)));
    graph.on(ComboEvent.CLICK, (event: IPointerEvent) => onSelectRef.current(pick(event)));
    graph.on(CanvasEvent.CLICK, () => onSelectRef.current(null));
    graph.on(CanvasEvent.POINTER_LEAVE, () => setHover(null));
    graph.on(NodeEvent.POINTER_ENTER, (event: IPointerEvent) => setHover(pick(event)));
    graph.on(NodeEvent.POINTER_LEAVE, () => setHover(null));
    graph.on(ComboEvent.POINTER_ENTER, (event: IPointerEvent) => setHover(pick(event)));
    graph.on(ComboEvent.POINTER_LEAVE, () => setHover(null));

    return () => {
      graphRef.current = null;
      void renderRef.current.catch(() => undefined).then(() => graph.destroy());
    };
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, []);

  /* ---- data + layout ---- */
  useEffect(() => {
    const graph = graphRef.current;
    if (!graph) return undefined;

    let cancelled = false;

    /*
     * Renders are queued behind whatever is already in flight rather than fired immediately.
     *
     * `graph.render()` is asynchronous, and calling `setData` while one is still running leaves the
     * canvas holding elements from both datasets — switching the environment filter left the
     * previous environment's cards on screen beside the new ones. Chaining keeps one render in
     * flight at a time; `cancelled` then drops the ones whose effect has already been superseded.
     */
    renderRef.current = renderRef.current
      .catch(() => undefined)
      .then(() => {
        if (cancelled) return undefined;
        // The pointer never "leaves" a node that a filter removed or a layout moved, so a stale
        // hover id would keep the graph dimmed around a card that is no longer there.
        hoverRef.current = null;
        graph.setData(data);
        // The explicit fit is not redundant with `autoFit`: on first paint ProCard is still sizing
        // its body, so the automatic fit measures a container narrower than its final width and the
        // leftmost column ends up cropped. Re-fitting after the render measures the real box.
        return graph
          .render()
          .then(() => fitToView())
          .then(() => applyStatesRef.current());
      })
      .catch(() => {
        /* the graph was destroyed under us — the next mount owns the canvas */
      });

    return () => {
      cancelled = true;
    };
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [data, layout]);

  /**
   * Fits the graph to the container, one animation frame late on purpose.
   *
   * On first paint ProCard is still laying out its body, so the container the canvas measures is
   * not yet its final size and a fit computed against it leaves the graph translated half out of
   * view. Yielding a frame lets G6's own resize observer catch up before we measure.
   */
  const fitToView = async () => {
    const graph = graphRef.current;
    if (!graph) return;
    await new Promise<void>((resolve) => requestAnimationFrame(() => resolve()));
    if (graphRef.current !== graph) return;
    await graph.fitView({ when: 'always', direction: 'both' });
  };

  useEffect(() => {
    // Skips the initial mount: the data effect already fits after its first render.
    if (fitSignal === 0) return;
    void fitToView().then(applyStates);
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [fitSignal]);

  /**
   * Re-fits when the container changes size.
   *
   * `autoResize` resizes G6's canvas but leaves the viewport transform alone, so without this a
   * window resize slides the graph off-centre. It also covers the case the frame delay above cannot:
   * a container that settles to its final size several frames after the first render.
   */
  useEffect(() => {
    const container = containerRef.current;
    if (!container || typeof ResizeObserver === 'undefined') return undefined;
    let last = `${container.clientWidth}x${container.clientHeight}`;
    const observer = new ResizeObserver(() => {
      const size = `${container.clientWidth}x${container.clientHeight}`;
      if (size === last) return;
      last = size;
      void fitToView();
    });
    observer.observe(container);
    return () => observer.disconnect();
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, []);

  /** Highlights the focused element's one-degree neighbourhood and dims everything else. */
  const applyStates = () => {
    const graph = graphRef.current;
    if (!graph) return;

    /*
     * An id that is no longer on the canvas is treated as no focus at all.
     *
     * Without this, a hover id left behind by an element that vanished dims *every* node, combo and
     * edge — nothing matches the focus, so nothing is ever marked active — and the graph reads as
     * uniformly greyed out with no way to recover but a reload.
     */
    const onCanvas = (id: string | null) =>
      id && (graph.hasNode(id) || graph.hasEdge(id) || graph.hasCombo(id)) ? id : null;
    const focus = onCanvas(hoverRef.current) ?? onCanvas(selectedId);

    const groups = layout === 'layered' ? model.appGroups : model.clusterGroups;
    const states: Record<string, string[]> = {};
    const all = [
      ...model.nodes.map((n) => n.id),
      ...model.combos.map((c) => c.id),
      // Without the app-system boxes here they are never handed a state at all, so they keep full
      // opacity while everything they contain dims — the cluster ends up shouting over the node the
      // hover was meant to isolate.
      ...groups.map((g) => g.id),
      ...model.edges.map((e) => e.id),
    ];

    if (!focus) {
      for (const id of all) states[id] = [NORMAL];
    } else {
      const incident = model.edges.filter(
        (edge) => edge.source === focus || edge.target === focus,
      );
      const near = new Set<string>([focus]);
      for (const edge of incident) {
        near.add(edge.source);
        near.add(edge.target);
      }
      // Focusing a box means "show me what is in it", so its contents count as neighbours. Without
      // this the box lights up while everything inside it greys out. An app-system box reaches one
      // level further: its participants, and then their nodes.
      const focusedGroup = groups.find((group) => group.id === focus);
      const inFocus = new Set(focusedGroup ? focusedGroup.comboIds : [focus]);
      for (const comboId of inFocus) near.add(comboId);
      for (const node of model.nodes) {
        if (inFocus.has(node.comboId)) near.add(node.id);
      }
      // A declared arrow runs between participant boxes, so an app-system box has no incident edge
      // of its own; the arrows in and out of its participants are what it means to be connected.
      if (focusedGroup) {
        for (const edge of model.edges) {
          if (inFocus.has(edge.source) || inFocus.has(edge.target)) {
            incident.push(edge);
            near.add(edge.source);
            near.add(edge.target);
          }
        }
      }
      const activeEdges = new Set(incident.map((edge: TopoEdge) => edge.id));
      for (const id of all) {
        if (id === focus) states[id] = [id === selectedId ? 'selected' : 'active'];
        else if (near.has(id) || activeEdges.has(id)) states[id] = ['active'];
        else states[id] = ['inactive'];
      }
    }

    // Elements filtered out of the current view are simply absent from the canvas.
    const payload: Record<string, string[]> = {};
    for (const [id, value] of Object.entries(states)) {
      if (onCanvas(id)) payload[id] = value;
    }
    void graph.setElementState(payload, false).catch(() => undefined);
  };

  applyStatesRef.current = applyStates;

  /** Stable across renders: it only touches refs, so the mount-time handlers can keep calling it. */
  const setHover = (id: string | null) => {
    hoverRef.current = id;
    applyStatesRef.current();
  };

  useEffect(applyStates, [selectedId, model, layout]);

  const isEmpty = model.nodes.length === 0;

  return (
    <div style={{ position: 'relative', width: '100%', height: CANVAS_HEIGHT }}>
      {/* G6's own canvas pointerleave can be missed when an element moves out from under a
          stationary cursor; the DOM one always fires. */}
      <div
        ref={containerRef}
        style={{ width: '100%', height: '100%' }}
        onMouseLeave={() => setHover(null)}
      />
      {isEmpty && !loading && (
        <div
          style={{
            position: 'absolute',
            inset: 0,
            display: 'flex',
            alignItems: 'center',
            justifyContent: 'center',
          }}
        >
          <Empty description={emptyText} />
        </div>
      )}
      {loading && (
        <div
          style={{
            position: 'absolute',
            inset: 0,
            display: 'flex',
            alignItems: 'center',
            justifyContent: 'center',
            background: token.colorBgMask,
          }}
        >
          <Spin />
        </div>
      )}
    </div>
  );
}
