import { useCallback, useEffect, useMemo, useRef, useState } from 'react';
import { PageContainer, ProCard } from '@ant-design/pro-components';
import { App as AntdApp } from 'antd';
import {
  Alert,
  Button,
  Col,
  Row,
  Segmented,
  Space,
  Statistic,
  Switch,
  Tag,
  Tooltip,
  Typography,
  theme,
} from 'antd';
import { ApartmentOutlined, CompressOutlined, ReloadOutlined } from '@ant-design/icons';
import { Select } from 'antd';
import { FilterBar } from '../../components/FilterBar';
import { useDimensions } from '../../hooks/useDimensions';
import { envMatrixApi } from '../../api/envMatrix';
import { useI18n } from '../../locales';
import type { MessageKey } from '../../locales';
import type {
  ConflictGroup,
  Endpoint,
  EndpointFilter,
  Release,
  ReleaseLink,
  ReleaseNode,
} from '../../api/types';
import { EDGE_KINDS, ORIENTATIONS, buildTopology } from './buildGraph';
import type { EdgeKind, Orientation, TopologyLayout } from './buildGraph';
import { edgeColors } from './palette';
import { NodeDetail } from './NodeDetail';
import { TopologyGraph } from './TopologyGraph';
import { LayerEditorModal } from './LayerEditorModal';
import type { LayoutEdit } from './LayerEditorModal';
import { useTopologyView } from './useTopologyView';

const ALL_VISIBLE: Record<EdgeKind, boolean> = {
  dep: true,
  colo: true,
  alias: true,
  clash: true,
};

/**
 * The orientation picker's labels.
 *
 * An arrow *is* the label: four spelled-out directions would be the widest control in a toolbar
 * that already carries the layout switch and a four-entry legend, and the tooltip carries the words
 * for anyone who wants them.
 */
const ORIENTATION_ARROWS: Record<Orientation, string> = {
  LR: '→',
  RL: '←',
  TB: '↓',
  BT: '↑',
};

export function TopologyPage() {
  const { t } = useI18n();
  /** Themed `message`, from the `AntdApp` wrapper in `main.tsx` — the static API ignores the theme. */
  const { message } = AntdApp.useApp();
  const { token } = theme.useToken();
  const { dimensions, loading: dimensionsLoading, reload: reloadDimensions } = useDimensions();
  /** Same stale-response guard as the matrix page — see `load`. */
  const requestIdRef = useRef(0);

  const [filter, setFilter] = useState<EndpointFilter>({});
  const [filterInitialised, setFilterInitialised] = useState(false);
  const [endpoints, setEndpoints] = useState<Endpoint[]>([]);
  const [conflicts, setConflicts] = useState<ConflictGroup[]>([]);
  const [releases, setReleases] = useState<Release[]>([]);
  const [releaseId, setReleaseId] = useState<number>();
  const [participants, setParticipants] = useState<ReleaseNode[]>([]);
  const [releaseLinks, setReleaseLinks] = useState<ReleaseLink[]>([]);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);

  const [visibleKinds, setVisibleKinds] = useState<Record<EdgeKind, boolean>>(ALL_VISIBLE);
  const [selectedId, setSelectedId] = useState<string | null>(null);
  const [fitSignal, setFitSignal] = useState(0);
  const [customOpen, setCustomOpen] = useState(false);
  const [savingLayout, setSavingLayout] = useState(false);

  /**
   * How to draw the graph — orientation and the app-system boxes, remembered in `localStorage`.
   *
   * Where each participant sits is *not* here: `layer` and `sortOrder` are columns on
   * `env_release_node`, arrive with the topology, and are written back by the layer editor.
   */
  const { view, setView } = useTopologyView();
  const { layout, orientation, groupByApp } = view;

  /**
   * Releases, and the one the graph is scoped to.
   *
   * A release is required — it is what says which environment slices belong together, and without
   * one there is no graph to draw. Archived releases are still selectable but sort last.
   */
  useEffect(() => {
    void (async () => {
      try {
        const rows = await envMatrixApi.releases();
        setReleases(rows);
        setReleaseId((current) =>
          current && rows.some((r) => r.id === current)
            ? current
            : (rows.find((r) => r.status === 'ACTIVE') ?? rows[0])?.id,
        );
      } catch (e) {
        setError((e as Error).message);
      }
    })();
  }, []);

  /**
   * Seeds the endpoint filter with one environment instance and one country.
   *
   * Only defaults, clearable like any filter — and they narrow what is *inside* the participant
   * boxes, never the wiring. The matrix page can afford to show a whole environment because a cell
   * stacks its endpoints vertically; a graph cannot, and six countries of one environment is roughly
   * 180 boxes.
   */
  useEffect(() => {
    if (filterInitialised) return;
    if (dimensions.envInstance.length === 0) return;
    setFilter({
      envInstance: [dimensions.envInstance[0]],
      ...(dimensions.country.length > 0 ? { country: [dimensions.country[0]] } : {}),
    });
    setFilterInitialised(true);
  }, [dimensions.envInstance, dimensions.country, filterInitialised]);

  const load = useCallback(async (current: EndpointFilter, release: number | undefined) => {
    if (!release) return;
    const requestId = (requestIdRef.current += 1);
    setLoading(true);
    try {
      // The release topology is fetched by release id alone. The endpoint filter narrows what shows
      // up inside the participant boxes; it must never narrow the wiring, or a connection would
      // disappear because of a country filter.
      const [endpointList, conflictGroups, topology] = await Promise.all([
        envMatrixApi.endpoints(current),
        envMatrixApi.conflicts(current),
        envMatrixApi.releaseTopology(release),
      ]);
      if (requestId !== requestIdRef.current) return;
      setEndpoints(endpointList);
      setConflicts(conflictGroups);
      setParticipants(topology.nodes);
      setReleaseLinks(topology.links);
      setError(null);
    } catch (e) {
      if (requestId !== requestIdRef.current) return;
      setError((e as Error).message);
    } finally {
      if (requestId === requestIdRef.current) setLoading(false);
    }
  }, []);

  useEffect(() => {
    if (dimensionsLoading) return;
    void load(filter, releaseId);
  }, [filter, releaseId, dimensionsLoading, load]);

  const model = useMemo(
    () =>
      buildTopology(endpoints, conflicts, participants, releaseLinks, { orientation, groupByApp }),
    [endpoints, conflicts, participants, releaseLinks, orientation, groupByApp],
  );
  const kindColors = useMemo(() => edgeColors(token), [token]);

  // A node that the new filter no longer contains would leave the panel showing a stale endpoint.
  // App-system boxes belong here too: their ids carry the layer, so pinning a participant to
  // another column retires the box that was selected.
  useEffect(() => {
    setSelectedId((current) =>
      current &&
      (model.nodeById.has(current) ||
        model.combos.some((c) => c.id === current) ||
        model.appGroups.some((g) => g.id === current) ||
        model.clusterGroups.some((g) => g.id === current))
        ? current
        : null,
    );
  }, [model]);


  /**
   * Writes the edited layers back into the release.
   *
   * The only route that stores a participant is `PUT /releases/{id}/topology`, and it is
   * authoritative for the whole release — so this resends every participant and every link exactly
   * as they came back from the server, with only `layer` and `sortOrder` replaced. Sending just the
   * edited rows would delete everything else in the release.
   */
  const saveLayout = useCallback(
    async (edits: Map<number, LayoutEdit>) => {
      if (!releaseId) return false;
      setSavingLayout(true);
      try {
        const result = await envMatrixApi.saveReleaseTopology(releaseId, {
          nodes: participants.map((participant) => {
            const edit = edits.get(participant.id);
            return {
              id: participant.id,
              appSystem: participant.appSystem,
              country: participant.country,
              envInstance: participant.envInstance,
              label: participant.label,
              note: participant.note,
              layer: edit ? edit.layer : participant.layer,
              sortOrder: edit ? edit.sortOrder : participant.sortOrder,
            };
          }),
          links: releaseLinks.map((link) => ({
            id: link.id,
            source: { id: link.sourceNodeId },
            target: { id: link.targetNodeId },
            direction: link.direction,
            note: link.note,
          })),
        });
        if (!result.success) {
          message.error(t('config.rejected', { count: result.issues.length }));
          return false;
        }
        message.success(t('config.saved'));
        await load(filter, releaseId);
        return true;
      } catch (e) {
        message.error((e as Error).message);
        return false;
      } finally {
        setSavingLayout(false);
      }
    },
    [releaseId, participants, releaseLinks, message, t, load, filter],
  );

  const selectedRelease = releases.find((release) => release.id === releaseId);

  return (
    <PageContainer
      title={t('topology.title')}
      subTitle={t('topology.subtitle')}
      extra={[
        <Button
          key="reload"
          icon={<ReloadOutlined />}
          loading={loading}
          onClick={() => {
            void reloadDimensions();
            void load(filter, releaseId);
          }}
        >
          {t('common.reload')}
        </Button>,
      ]}
    >
      <Space direction="vertical" size="middle" style={{ width: '100%' }}>
        {error && (
          <Alert
            type="error"
            showIcon
            message={t('common.error')}
            description={error}
            action={
              <Button size="small" onClick={() => void load(filter, releaseId)}>
                {t('common.retry')}
              </Button>
            }
          />
        )}

        <ProCard size="small" bordered>
          <Space size="middle" wrap>
            <Typography.Text type="secondary">{t('topology.release')} *</Typography.Text>
            <Select<number>
              style={{ minWidth: 260 }}
              value={releaseId}
              onChange={setReleaseId}
              placeholder={t('topology.releasePlaceholder')}
              options={releases.map((release) => ({
                value: release.id,
                label: `${release.name} · ${release.tier} · ${release.nodeCount}/${release.linkCount}`,
              }))}
              showSearch
              optionFilterProp="label"
            />
            {selectedRelease?.note && (
              <Typography.Text type="secondary" style={{ fontSize: 12 }}>
                {selectedRelease.note}
              </Typography.Text>
            )}
          </Space>
        </ProCard>

        <ProCard title={t('filter.title')} size="small" bordered>
          <FilterBar
            dimensions={dimensions}
            value={filter}
            onChange={setFilter}
            loading={dimensionsLoading}
          />
        </ProCard>

        <Row gutter={[12, 12]}>
          <Col xs={12} sm={8} md={4}>
            <ProCard size="small" bordered>
              {/* Real endpoints only: `model.nodes` also carries the dashed placeholders, and
                  counting those here would inflate the number the matrix page reports. */}
              <Statistic
                title={t('topology.stats.nodes')}
                value={endpoints.length}
                loading={loading}
              />
            </ProCard>
          </Col>
          <Col xs={12} sm={8} md={4}>
            <ProCard size="small" bordered>
              <Statistic
                title={t('topology.stats.systems')}
                value={model.combos.length}
                loading={loading}
              />
            </ProCard>
          </Col>
          <Col xs={12} sm={8} md={4}>
            <ProCard size="small" bordered>
              <Statistic
                title={t('topology.stats.links')}
                value={model.edges.length}
                loading={loading}
              />
            </ProCard>
          </Col>
          <Col xs={12} sm={8} md={4}>
            <ProCard size="small" bordered>
              <Statistic
                title={t('stats.conflicts')}
                value={conflicts.length}
                loading={loading}
                valueStyle={conflicts.length ? { color: token.colorError } : undefined}
              />
            </ProCard>
          </Col>
        </Row>

        <Alert
          type="info"
          showIcon
          message={t('topology.hierarchy', {
            release: selectedRelease?.name ?? '—',
            count: model.counts.dep,
          })}
          description={t('topology.hierarchyNotice')}
        />

        {model.placeholders.length > 0 && (
          <Alert
            type="warning"
            showIcon
            message={t('topology.placeholders', { apps: model.placeholders.join(', ') })}
          />
        )}

        {model.unlinked.length > 0 && (
          <Alert
            type="warning"
            showIcon
            message={t('topology.unlinked', { apps: model.unlinked.join(', ') })}
          />
        )}

        {model.unclaimed > 0 && (
          <Alert
            type="warning"
            showIcon
            message={t('topology.unclaimed', { count: model.unclaimed })}
          />
        )}

        <Row gutter={[12, 12]}>
          <Col xs={24} lg={18}>
            <ProCard
              size="small"
              bordered
              bodyStyle={{ padding: 0 }}
              title={
                <Space size="middle" wrap>
                  <Segmented<TopologyLayout>
                    size="small"
                    value={layout}
                    onChange={(value) => setView({ layout: value })}
                    options={(['layered', 'cluster'] as const).map((value) => ({
                      value,
                      label: t(`topology.layout.${value}` as MessageKey),
                    }))}
                  />
                  {/* Orientation only moves the layered view: the clustered one has no flow axis to
                      point anywhere, so the control would do nothing there. */}
                  {layout === 'layered' && (
                    <Segmented<Orientation>
                      size="small"
                      value={orientation}
                      onChange={(value) => setView({ orientation: value })}
                      options={ORIENTATIONS.map((value) => ({
                        value,
                        label: (
                          <Tooltip title={t(`topology.orientation.${value}` as MessageKey)}>
                            <span style={{ padding: '0 2px' }}>{ORIENTATION_ARROWS[value]}</span>
                          </Tooltip>
                        ),
                      }))}
                    />
                  )}
                  <Space size={6}>
                    <Switch
                      size="small"
                      checked={groupByApp}
                      onChange={(checked) => setView({ groupByApp: checked })}
                    />
                    <Typography.Text type="secondary" style={{ fontSize: 12 }}>
                      {t('topology.groupByApp')}
                    </Typography.Text>
                  </Space>
                  <Button
                    size="small"
                    icon={<ApartmentOutlined />}
                    onClick={() => setCustomOpen(true)}
                    disabled={model.combos.length === 0}
                  >
                    {t('topology.custom')}
                  </Button>
                  <Button
                    size="small"
                    icon={<CompressOutlined />}
                    onClick={() => setFitSignal((n) => n + 1)}
                  >
                    {t('topology.fit')}
                  </Button>
                  <Typography.Text type="secondary" style={{ fontSize: 12 }}>
                    {t('topology.links')}
                  </Typography.Text>
                  <Space size={4} wrap>
                    {EDGE_KINDS.map((kind) => (
                      <Tag
                        key={kind}
                        bordered={false}
                        onClick={() => setVisibleKinds((prev) => ({ ...prev, [kind]: !prev[kind] }))}
                        style={{
                          cursor: 'pointer',
                          opacity: visibleKinds[kind] ? 1 : 0.4,
                          userSelect: 'none',
                          marginInlineEnd: 0,
                        }}
                      >
                        <span
                          style={{
                            display: 'inline-block',
                            width: 10,
                            height: 2,
                            marginInlineEnd: 6,
                            verticalAlign: 'middle',
                            background: kindColors[kind],
                          }}
                        />
                        {t(`topology.link.${kind}` as MessageKey)} · {model.counts[kind]}
                      </Tag>
                    ))}
                  </Space>
                </Space>
              }
            >
              <TopologyGraph
                model={model}
                layout={layout}
                groupByApp={groupByApp}
                visibleKinds={visibleKinds}
                selectedId={selectedId}
                onSelect={setSelectedId}
                fitSignal={fitSignal}
                loading={loading}
                emptyText={t('topology.empty')}
                placeholderText={t('topology.placeholderNode')}
              />
              <div style={{ padding: '8px 12px', borderTop: `1px solid ${token.colorSplit}` }}>
                <Typography.Text type="secondary" style={{ fontSize: 12 }}>
                  {t('topology.hint')}
                </Typography.Text>
              </div>
            </ProCard>
          </Col>
          <Col xs={24} lg={6}>
            <ProCard size="small" bordered title={t('topology.detail.title')}>
              <NodeDetail model={model} selectedId={selectedId} kindColors={kindColors} />
            </ProCard>
          </Col>
        </Row>
      </Space>

      <LayerEditorModal
        open={customOpen}
        onClose={() => setCustomOpen(false)}
        combos={model.combos}
        releaseName={selectedRelease?.name ?? '—'}
        saving={savingLayout}
        onSave={saveLayout}
      />
    </PageContainer>
  );
}
