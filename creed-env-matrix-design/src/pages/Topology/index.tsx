import { useCallback, useEffect, useMemo, useRef, useState } from 'react';
import { PageContainer, ProCard } from '@ant-design/pro-components';
import {
  Alert,
  Button,
  Col,
  Row,
  Segmented,
  Space,
  Statistic,
  Tag,
  Typography,
  theme,
} from 'antd';
import { CompressOutlined, ReloadOutlined } from '@ant-design/icons';
import { FilterBar } from '../../components/FilterBar';
import { useDimensions } from '../../hooks/useDimensions';
import { envMatrixApi } from '../../api/envMatrix';
import { useI18n } from '../../locales';
import type { MessageKey } from '../../locales';
import type { AppLink, ConflictGroup, Endpoint, EndpointFilter } from '../../api/types';
import { EDGE_KINDS, buildTopology } from './buildGraph';
import type { EdgeKind, TopologyLayout } from './buildGraph';
import { edgeColors } from './palette';
import { NodeDetail } from './NodeDetail';
import { TopologyGraph } from './TopologyGraph';

const ALL_VISIBLE: Record<EdgeKind, boolean> = {
  dep: true,
  colo: true,
  alias: true,
  clash: true,
};

export function TopologyPage() {
  const { t } = useI18n();
  const { token } = theme.useToken();
  const { dimensions, loading: dimensionsLoading, reload: reloadDimensions } = useDimensions();
  /** Same stale-response guard as the matrix page — see `load`. */
  const requestIdRef = useRef(0);

  const [filter, setFilter] = useState<EndpointFilter>({});
  const [filterInitialised, setFilterInitialised] = useState(false);
  const [endpoints, setEndpoints] = useState<Endpoint[]>([]);
  const [conflicts, setConflicts] = useState<ConflictGroup[]>([]);
  const [links, setLinks] = useState<AppLink[]>([]);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);

  const [layout, setLayout] = useState<TopologyLayout>('layered');
  const [visibleKinds, setVisibleKinds] = useState<Record<EdgeKind, boolean>>(ALL_VISIBLE);
  const [selectedId, setSelectedId] = useState<string | null>(null);
  const [fitSignal, setFitSignal] = useState(0);

  /**
   * Seeds the filter: a tier is **required**, an environment instance and a country are defaults.
   *
   * The tier is mandatory because the wiring is declared per tier — without one the graph would
   * overlay four environments' topologies on top of each other, which is the opposite of the point.
   * The other two are merely defaults, clearable like any filter: the matrix page can afford to show
   * a whole environment because a cell stacks its endpoints vertically, but a graph cannot, and six
   * countries of one environment is roughly 180 boxes.
   */
  useEffect(() => {
    if (filterInitialised) return;
    if (dimensions.tier.length === 0) return;
    setFilter({
      tier: [dimensions.tier[0]],
      ...(dimensions.envInstance.length > 0 ? { envInstance: [dimensions.envInstance[0]] } : {}),
      ...(dimensions.country.length > 0 ? { country: [dimensions.country[0]] } : {}),
    });
    setFilterInitialised(true);
  }, [dimensions.tier, dimensions.envInstance, dimensions.country, filterInitialised]);

  /**
   * The tier the graph is scoped to. Always present once the filter is seeded; the FilterBar's
   * `required` mode makes it impossible to clear.
   */
  const tier = filter.tier?.[0];

  const load = useCallback(async (current: EndpointFilter) => {
    const requestId = (requestIdRef.current += 1);
    setLoading(true);
    try {
      // `/links` takes the tier alone: the wiring is declared per tier, so narrowing to one country
      // must not hide a connection. It is the endpoints that get filtered, never the topology.
      const [endpointList, conflictGroups, linkRows] = await Promise.all([
        envMatrixApi.endpoints(current),
        envMatrixApi.conflicts(current),
        envMatrixApi.links(current.tier?.[0]),
      ]);
      if (requestId !== requestIdRef.current) return;
      setEndpoints(endpointList);
      setConflicts(conflictGroups);
      setLinks(linkRows);
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
    // Nothing can be drawn before a tier is chosen, and the seeding effect above is one render away.
    if (!tier) return;
    void load(filter);
  }, [filter, tier, dimensionsLoading, load]);

  const model = useMemo(
    () => buildTopology(endpoints, conflicts, links),
    [endpoints, conflicts, links],
  );
  const kindColors = useMemo(() => edgeColors(token), [token]);

  // A node that the new filter no longer contains would leave the panel showing a stale endpoint.
  useEffect(() => {
    setSelectedId((current) =>
      current && (model.nodeById.has(current) || model.combos.some((c) => c.id === current))
        ? current
        : null,
    );
  }, [model]);


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
            void load(filter);
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
              <Button size="small" onClick={() => void load(filter)}>
                {t('common.retry')}
              </Button>
            }
          />
        )}

        <ProCard title={t('filter.title')} size="small" bordered>
          <FilterBar
            dimensions={dimensions}
            value={filter}
            onChange={setFilter}
            loading={dimensionsLoading}
            required={['tier']}
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
          message={t('topology.hierarchy', { tier: tier ?? '—', count: model.counts.dep })}
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
                    onChange={setLayout}
                    options={(['layered', 'cluster'] as const).map((value) => ({
                      value,
                      label: t(`topology.layout.${value}` as MessageKey),
                    }))}
                  />
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
    </PageContainer>
  );
}
