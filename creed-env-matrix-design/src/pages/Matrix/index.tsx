import { useCallback, useEffect, useMemo, useRef, useState } from 'react';
import { PageContainer, ProCard } from '@ant-design/pro-components';
import { Alert, App, Button, Col, Row, Space, Statistic, Switch, Tag, Typography } from 'antd';
import { ReloadOutlined } from '@ant-design/icons';
import { FilterBar } from '../../components/FilterBar';
import { useDimensions } from '../../hooks/useDimensions';
import { envMatrixApi } from '../../api/envMatrix';
import { useI18n } from '../../locales';
import type { EndpointFilter, HealthReport, MatrixResponse } from '../../api/types';
import { ConflictPanel } from './ConflictPanel';
import { MatrixTable } from './MatrixTable';

export function MatrixPage() {
  const { t } = useI18n();
  const { message } = App.useApp();
  const { dimensions, loading: dimensionsLoading, reload: reloadDimensions } = useDimensions();
  /** Guards against a slow earlier response overwriting a newer one — see `load`. */
  const requestIdRef = useRef(0);

  const [filter, setFilter] = useState<EndpointFilter>({});
  const [filterInitialised, setFilterInitialised] = useState(false);
  const [matrix, setMatrix] = useState<MatrixResponse | null>(null);
  const [health, setHealth] = useState<HealthReport | null>(null);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);
  const [conflictsOnly, setConflictsOnly] = useState(false);

  /**
   * Default to the first environment instance rather than showing everything.
   *
   * Unfiltered, every cell stacks the endpoints of all seven environments on top of each other and
   * the grid becomes unreadable. The default is applied as a visible, clearable filter value — not a
   * hidden query parameter — so it is obvious what is being shown.
   */
  useEffect(() => {
    if (!filterInitialised && dimensions.envInstance.length > 0) {
      setFilter({ envInstance: [dimensions.envInstance[0]] });
      setFilterInitialised(true);
    }
  }, [dimensions.envInstance, filterInitialised]);

  /**
   * Fetches the grid and the health roll-up for one filter.
   *
   * Responses are tagged with a request id and a stale one is dropped. Without that, an unfiltered
   * request (1200+ rows, slow) issued moments before a filtered one can resolve *after* it and
   * replace the correct grid with the old one — which is exactly what happened under StrictMode's
   * double mount.
   */
  const load = useCallback(async (current: EndpointFilter) => {
    const requestId = (requestIdRef.current += 1);
    setLoading(true);
    try {
      const [matrixResponse, healthResponse] = await Promise.all([
        envMatrixApi.matrix(current),
        envMatrixApi.health(current),
      ]);
      if (requestId !== requestIdRef.current) return;
      setMatrix(matrixResponse);
      setHealth(healthResponse);
      setError(null);
    } catch (e) {
      if (requestId !== requestIdRef.current) return;
      setError((e as Error).message);
    } finally {
      if (requestId === requestIdRef.current) {
        setLoading(false);
      }
    }
  }, []);

  useEffect(() => {
    // Wait for /dimensions to settle, then for the default filter to be applied. Firing before that
    // would request the whole unfiltered matrix only to discard it a moment later.
    if (dimensionsLoading) return;
    if (dimensions.envInstance.length > 0 && !filterInitialised) return;
    void load(filter);
  }, [filter, filterInitialised, dimensionsLoading, dimensions.envInstance.length, load]);

  const recheck = async () => {
    try {
      const result = await envMatrixApi.recheckHealth();
      await load(filter);
      message.success(`${t('health.recheck')} — ${t('health.mode')}: ${result.mode}`);
    } catch (e) {
      message.error((e as Error).message);
    }
  };

  const healthCounts = useMemo(() => health?.summary ?? {}, [health]);

  return (
    <PageContainer
      title={t('matrix.title')}
      subTitle={t('matrix.subtitle')}
      extra={[
        <Button
          key="reload"
          icon={<ReloadOutlined />}
          onClick={() => {
            void reloadDimensions();
            void load(filter);
          }}
          loading={loading}
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
          />
        </ProCard>

        <Row gutter={[12, 12]}>
          <Col xs={12} sm={8} md={4}>
            <ProCard size="small" bordered>
              <Statistic title={t('stats.total')} value={matrix?.total ?? 0} loading={loading} />
            </ProCard>
          </Col>
          <Col xs={12} sm={8} md={4}>
            <ProCard size="small" bordered>
              <Statistic
                title={t('stats.conflicts')}
                value={matrix?.conflicts.length ?? 0}
                loading={loading}
                valueStyle={matrix?.conflicts.length ? { color: '#cf1322' } : undefined}
              />
            </ProCard>
          </Col>
          <Col xs={12} sm={8} md={4}>
            <ProCard size="small" bordered>
              <Statistic
                title={t('stats.services')}
                value={matrix?.services.length ?? 0}
                loading={loading}
              />
            </ProCard>
          </Col>
          <Col xs={12} sm={8} md={4}>
            <ProCard size="small" bordered>
              <Statistic
                title={t('stats.cells')}
                value={matrix?.cells.length ?? 0}
                loading={loading}
              />
            </ProCard>
          </Col>
          <Col xs={24} md={8}>
            <ProCard size="small" bordered title={t('health.title')}>
              <Space direction="vertical" size={4} style={{ width: '100%' }}>
                <Space wrap size={4}>
                  <Tag color={health?.mocked ? 'orange' : 'green'} bordered={false}>
                    {t('health.mode')}: {health ? t(`health.${health.mode}`) : '—'}
                  </Tag>
                  <Tag color="success" bordered={false}>
                    {t('health.UP')} {healthCounts.UP ?? 0}
                  </Tag>
                  <Tag color="warning" bordered={false}>
                    {t('health.DEGRADED')} {healthCounts.DEGRADED ?? 0}
                  </Tag>
                  <Tag color="error" bordered={false}>
                    {t('health.DOWN')} {healthCounts.DOWN ?? 0}
                  </Tag>
                </Space>
                <Button size="small" onClick={() => void recheck()} loading={loading}>
                  {t('health.recheck')}
                </Button>
              </Space>
            </ProCard>
          </Col>
        </Row>

        {health && (
          <Alert
            type="info"
            showIcon
            message={health.mocked ? t('health.mockNotice') : t('health.realNotice')}
          />
        )}

        {matrix && <ConflictPanel conflicts={matrix.conflicts} scope={matrix.scope} />}

        <ProCard
          title={t('matrix.title')}
          size="small"
          bordered
          extra={
            <Space size="small">
              <Typography.Text type="secondary">{t('matrix.legendConflict')}</Typography.Text>
              <Switch
                size="small"
                checked={conflictsOnly}
                onChange={setConflictsOnly}
                disabled={!matrix?.conflicts.length}
              />
            </Space>
          }
        >
          <MatrixTable
            services={matrix?.services ?? []}
            countries={matrix?.countries ?? []}
            cells={matrix?.cells ?? []}
            loading={loading}
            conflictsOnly={conflictsOnly}
          />
        </ProCard>
      </Space>
    </PageContainer>
  );
}
