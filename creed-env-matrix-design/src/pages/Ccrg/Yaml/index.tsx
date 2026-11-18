import { useEffect, useRef, useState } from 'react';
import { PageContainer, ProCard } from '@ant-design/pro-components';
import {
  Alert,
  App,
  Button,
  Descriptions,
  Empty,
  Col,
  Form,
  Row,
  Select,
  Space,
  Table,
  Tag,
  Tooltip,
  Typography,
  theme,
} from 'antd';
import {
  ArrowLeftOutlined,
  ArrowRightOutlined,
  CodeOutlined,
  CopyOutlined,
  DeleteOutlined,
  DownloadOutlined,
  ImportOutlined,
  PlusOutlined,
  SaveOutlined,
  UndoOutlined,
} from '@ant-design/icons';
import { ccrgApi, saveFile } from '../../../api/ccrg';
import type { EffectiveYamlVo, SelectorVo } from '../../../api/ccrg';
import { useI18n } from '../../../locales';
import type { MessageKey } from '../../../locales';
import { GuidelineBanner, errorText, storage, toOptions, useProgressOverlay } from '../shared';
import { REQUIRED, clearBelow, emptySource, isMissing, normalize, toRoute, walk } from '../Comparison/model';
import type { Source } from '../Comparison/model';

/**
 * Generate YAML (`/ccrg/yaml`): one or more sources — each the same object as Batch export's sources —
 * merged in list order (two per row: left to right, then top to bottom) into the configuration they would run with (`POST /prompt/generate-yaml/merge`):
 * a key set by several sources takes the LAST one's value, and every such disagreement is listed.
 *
 * Each source has Batch export's cascade, without the two-source locking.
 */

const STORAGE_KEY = 'ccrg.yaml';
const BATCH_KEY = 'ccrg.batch';
/** Same limit as EffectiveYamlService.MAX_SOURCES: each source is a full environment load. */
const MAX_SOURCES = 10;

const FIELD_LABEL: Record<string, MessageKey> = {
  projectName: 'ccrg.field.project',
  releaseName: 'ccrg.field.releaseFolder',
  countryCode: 'ccrg.field.country',
  app: 'ccrg.field.application',
  environment: 'ccrg.field.environment',
  instance: 'ccrg.field.instance',
  zone: 'ccrg.field.zone',
};

/** The key used to hold one Source; the list used to — both shapes still load. */
function loadSaved(): Source[] {
  const saved = storage.load<Partial<Source> | Partial<Source>[]>(STORAGE_KEY);
  if (!saved) return [emptySource()];
  try {
    return normalize([{ routes: Array.isArray(saved) ? saved : [saved] }])[0].routes;
  } catch {
    return [emptySource()];
  }
}

export function YamlPage() {
  const { t } = useI18n();
  const { message } = App.useApp();
  const { token } = theme.useToken();

  const [sources, setSources] = useState<Source[]>(loadSaved);
  const [projects, setProjects] = useState<SelectorVo[]>([]);
  const [loading, setLoading] = useState(false);
  const [showErrors, setShowErrors] = useState(false);
  const [result, setResult] = useState<EffectiveYamlVo>();
  const [overlay, runWithProgress] = useProgressOverlay();
  /** A newer generate supersedes an older one still in flight. */
  const requestId = useRef(0);

  useEffect(() => {
    ccrgApi
      .projects()
      .then(setProjects)
      .catch((e) => message.error(errorText(e)));
  }, [message]);

  const mutate = (index: number, fn: (draft: Source) => void) =>
    setSources((prev) => {
      if (!prev[index]) return prev;
      const next = [...prev];
      const draft = structuredClone(prev[index]);
      fn(draft);
      next[index] = draft;
      return next;
    });

  /** Any change to a source makes a shown YAML stale. */
  const edit = (index: number, fn: (draft: Source) => void) => {
    mutate(index, fn);
    setResult(undefined);
  };

  const setList = (fn: (list: Source[]) => Source[]) => {
    setSources((prev) => fn([...prev]));
    setResult(undefined);
  };

  const fetching = async <T,>(work: () => Promise<T>): Promise<T | undefined> => {
    setLoading(true);
    try {
      return await work();
    } catch (e) {
      message.error(errorText(e));
      return undefined;
    } finally {
      setLoading(false);
    }
  };

  // --- the cascade; each handler takes the source as it was when picked, since state is async ---

  const onProject = async (i: number, projectName: string) => {
    edit(i, (d) => {
      d.projectName = projectName;
      clearBelow(d, 'project');
      d.releaseSelector = [];
    });
    if (!projectName) return;
    const data = await fetching(() => ccrgApi.pathWalk({ dictType: 'COMMON', projectName }));
    if (data) mutate(i, (d) => (d.releaseSelector = data));
  };

  const onCountry = async (i: number, from: Source, countryCode: string) => {
    edit(i, (d) => {
      d.countryCode = countryCode;
      clearBelow(d, 'country');
    });
    if (!countryCode) return;
    const data = await fetching(() => ccrgApi.pathWalk(walk({ ...from, countryCode }, 'APP', 2)));
    if (data) mutate(i, (d) => (d.appSelector = data));
  };

  const onRelease = async (i: number, from: Source, releaseName: string) => {
    edit(i, (d) => {
      d.releaseName = releaseName;
      clearBelow(d, 'release');
      d.countrySelector = [];
    });
    if (!releaseName) return;
    const data = await fetching(() => ccrgApi.pathWalk(walk({ ...from, releaseName }, 'COMMON', 1)));
    if (!data) return;
    mutate(i, (d) => (d.countrySelector = data));
    // the backend's default country, as on Batch export
    const preset = data.find((s) => s.selected)?.name;
    if (preset) await onCountry(i, { ...from, releaseName }, preset);
  };

  const onApp = async (i: number, from: Source, app: string) => {
    edit(i, (d) => {
      d.app = app;
      clearBelow(d, 'app');
    });
    if (!app) return;
    const data = await fetching(() => ccrgApi.pathWalk(walk({ ...from, app }, 'ENVIRONMENT', 3)));
    if (data) mutate(i, (d) => (d.environmentSelector = data));
  };

  const onEnvironment = async (i: number, from: Source, environment: string) => {
    edit(i, (d) => {
      d.environment = environment;
      clearBelow(d, 'environment');
    });
    if (!environment) return;
    const data = await fetching(() => ccrgApi.pathWalk(walk({ ...from, environment }, 'INSTANCE', 4)));
    if (data) mutate(i, (d) => (d.instanceSelector = data));
  };

  const onInstance = async (i: number, from: Source, instance: string) => {
    edit(i, (d) => {
      d.instance = instance;
      d.zone = [];
      d.zoneSelector = [];
    });
    if (!instance) return;
    const data = await fetching(() => ccrgApi.pathWalk(walk({ ...from, instance }, 'ZONE', 5)));
    if (data) mutate(i, (d) => (d.zoneSelector = data));
  };

  // --- list operations ---

  const move = (i: number, by: -1 | 1) =>
    setList((list) => {
      [list[i], list[i + by]] = [list[i + by], list[i]];
      return list;
    });
  const duplicate = (i: number) =>
    setList((list) => {
      list.splice(i + 1, 0, structuredClone(list[i]));
      return list;
    });
  const remove = (i: number) => setList((list) => list.filter((_, k) => k !== i));
  const add = () => setList((list) => [...list, emptySource()]);

  // --- toolbar ---

  const loadBatchSources = () => {
    let routes: Source[] = [];
    try {
      routes = (normalize(storage.load(BATCH_KEY))[0]?.routes ?? []).filter((r) => r.projectName);
    } catch {
      routes = [];
    }
    if (routes.length === 0) {
      message.warning(t('ccrg.yaml.noBatchSource'));
      return;
    }
    setSources(routes.slice(0, MAX_SOURCES));
    setResult(undefined);
    setShowErrors(false);
    message.success(t('ccrg.yaml.batchSourceLoaded', { count: Math.min(routes.length, MAX_SOURCES) }));
  };

  const save = () => {
    if (storage.save(STORAGE_KEY, sources)) message.success(t('ccrg.saved'));
    else message.error(t('ccrg.saveFailed'));
  };

  const missingOf = (s: Source) => REQUIRED.filter((f) => isMissing(s, f));
  const incomplete = sources
    .map((s, i) => ({ index: i + 1, fields: missingOf(s) }))
    .filter((x) => x.fields.length > 0);

  const generate = async () => {
    if (incomplete.length > 0) {
      setShowErrors(true);
      message.warning(t('ccrg.incomplete'));
      return;
    }
    const id = ++requestId.current;
    try {
      const answer = await runWithProgress(() => ccrgApi.generateMergedYaml(sources.map(toRoute)));
      if (id === requestId.current) setResult(answer);
    } catch (e) {
      if (id === requestId.current) message.error(t('ccrg.yaml.failed', { message: errorText(e) }));
    }
  };

  const download = () => {
    if (!result) return;
    saveFile({ blob: new Blob([result.yaml], { type: 'application/yaml;charset=utf-8' }), filename: result.fileName });
    message.success(t('ccrg.downloaded'));
  };

  // --- rendering ---

  const renderSource = (source: Source, i: number) => {
    const errorOf = (field: keyof Source) => (showErrors && isMissing(source, field) ? 'error' : undefined);
    const select = (field: keyof Source, selector: SelectorVo[], onChange: (v: string) => void) => (
      <Form.Item label={t(FIELD_LABEL[field])} required validateStatus={errorOf(field)}>
        <Select
          showSearch
          allowClear
          disabled={loading}
          value={(source[field] as string) || undefined}
          options={toOptions(selector)}
          onChange={(v?: string) => onChange(v ?? '')}
        />
      </Form.Item>
    );
    const many = sources.length > 1;
    return (
      <ProCard
        bordered
        size="small"
        style={{ height: '100%' }}
        title={
          <Space size="small">
            {t('ccrg.compare.source', { index: i + 1 })}
            {many && i === 0 && <Tag>{t('ccrg.yaml.lowest')}</Tag>}
            {many && i === sources.length - 1 && <Tag color="blue">{t('ccrg.yaml.highest')}</Tag>}
          </Space>
        }
        extra={
          <Space size={4}>
            <Tooltip title={t('ccrg.yaml.moveUp')}>
              <Button size="small" type="text" icon={<ArrowLeftOutlined />} disabled={i === 0} onClick={() => move(i, -1)} />
            </Tooltip>
            <Tooltip title={t('ccrg.yaml.moveDown')}>
              <Button
                size="small"
                type="text"
                icon={<ArrowRightOutlined />}
                disabled={i === sources.length - 1}
                onClick={() => move(i, 1)}
              />
            </Tooltip>
            <Tooltip title={t('ccrg.yaml.duplicate')}>
              <Button
                size="small"
                type="text"
                icon={<CopyOutlined />}
                disabled={sources.length >= MAX_SOURCES}
                onClick={() => duplicate(i)}
              />
            </Tooltip>
            <Tooltip title={t('ccrg.yaml.remove')}>
              <Button size="small" type="text" danger icon={<DeleteOutlined />} disabled={!many} onClick={() => remove(i)} />
            </Tooltip>
          </Space>
        }
      >
        <Form layout="horizontal" labelCol={{ flex: '120px' }} labelAlign="left">
          {select('projectName', projects, (v) => onProject(i, v))}
          {select('releaseName', source.releaseSelector, (v) => onRelease(i, source, v))}
          {select('countryCode', source.countrySelector, (v) => onCountry(i, source, v))}
          {select('app', source.appSelector, (v) => onApp(i, source, v))}
          {select('environment', source.environmentSelector, (v) => onEnvironment(i, source, v))}
          {select('instance', source.instanceSelector, (v) => onInstance(i, source, v))}
          <Form.Item label={t('ccrg.field.zone')} required validateStatus={errorOf('zone')} style={{ marginBottom: 0 }}>
            <Select
              mode="multiple"
              allowClear
              disabled={loading}
              value={source.zone}
              options={toOptions(source.zoneSelector)}
              onChange={(v: string[]) => edit(i, (d) => (d.zone = v))}
            />
          </Form.Item>
        </Form>
      </ProCard>
    );
  };

  const conflictColumns = result
    ? [
        {
          key: 'key',
          title: t('ccrg.yaml.key'),
          dataIndex: 'key',
          width: 280,
          render: (key: string) => <Typography.Text code>{key}</Typography.Text>,
        },
        ...result.sources.map((s) => ({
          key: `s${s.index}`,
          title: (
            <Tooltip title={s.label}>
              <span>{t('ccrg.compare.source', { index: s.index })}</span>
            </Tooltip>
          ),
          render: (_: unknown, row: EffectiveYamlVo['conflicts'][number]) => {
            const value = row.values[s.index - 1];
            if (value === null || value === undefined) return <Typography.Text type="secondary">{t('ccrg.yaml.notSet')}</Typography.Text>;
            const used = row.winner === s.index;
            return (
              <Space size={4} align="start">
                <Typography.Text strong={used} delete={!used} type={used ? undefined : 'secondary'} style={{ wordBreak: 'break-all' }}>
                  {value}
                </Typography.Text>
                {used && <Tag color="green">{t('ccrg.yaml.used')}</Tag>}
              </Space>
            );
          },
        })),
      ]
    : [];

  return (
    <PageContainer title={t('nav.ccrg.yaml')} subTitle={t('ccrg.yaml.hint')}>
      {overlay}
      <Space direction="vertical" size="middle" style={{ width: '100%' }}>
        <GuidelineBanner />
        <ProCard
          bordered
          title={t('ccrg.yaml.sources')}
          extra={
            <Space wrap>
              <Tooltip title={t('ccrg.yaml.useBatchSourceHint')}>
                <Button icon={<ImportOutlined />} onClick={loadBatchSources}>
                  {t('ccrg.yaml.useBatchSource')}
                </Button>
              </Tooltip>
              <Button
                icon={<UndoOutlined />}
                onClick={() => {
                  setSources([emptySource()]);
                  setResult(undefined);
                  setShowErrors(false);
                }}
              >
                {t('ccrg.reset')}
              </Button>
            </Space>
          }
        >
          <Space direction="vertical" size="middle" style={{ width: '100%' }}>
            {sources.length > 1 && <Alert type="info" showIcon message={t('ccrg.yaml.mergeOrder')} />}
            {/* two sources per row on wide screens; merge order reads left to right, then top to bottom */}
            <Row gutter={[16, 16]}>
              {sources.map((s, i) => (
                <Col key={i} xs={24} xl={12}>
                  {renderSource(s, i)}
                </Col>
              ))}
            </Row>
            <Tooltip title={sources.length >= MAX_SOURCES ? t('ccrg.yaml.maxSources', { max: MAX_SOURCES }) : undefined}>
              <Button type="dashed" block icon={<PlusOutlined />} disabled={sources.length >= MAX_SOURCES} onClick={add}>
                {t('ccrg.yaml.addSource')}
              </Button>
            </Tooltip>
            <Space wrap>
              <Button icon={<SaveOutlined />} onClick={save}>
                {t('ccrg.saveConfig')}
              </Button>
              <Button type="primary" icon={<CodeOutlined />} onClick={generate}>
                {t('ccrg.yaml.generate')}
              </Button>
            </Space>
            {showErrors &&
              incomplete.map((x) => (
                <Typography.Text key={x.index} type="danger">
                  {t('ccrg.yaml.sourceMissing', {
                    index: x.index,
                    fields: x.fields.map((f) => t(FIELD_LABEL[f])).join(' / '),
                  })}
                </Typography.Text>
              ))}
          </Space>
        </ProCard>

        <ProCard
          bordered
          title={result ? result.fileName : t('ccrg.yaml.result')}
          extra={
            result && (
              <Space>
                <Typography.Text copyable={{ text: result.yaml, tooltips: [t('ccrg.yaml.copy'), t('ccrg.yaml.copied')] }} />
                <Button type="primary" icon={<DownloadOutlined />} onClick={download}>
                  {t('ccrg.yaml.download')}
                </Button>
              </Space>
            )
          }
        >
          {!result ? (
            <Empty description={t('ccrg.yaml.empty')} />
          ) : (
            <Space direction="vertical" size="middle" style={{ width: '100%' }}>
              <Descriptions
                size="small"
                column={1}
                items={[
                  ...result.sources.map((s) => ({
                    key: `s${s.index}`,
                    label: t('ccrg.compare.source', { index: s.index }),
                    children: (
                      <Space size={[8, 4]} wrap>
                        <span>{s.label}</span>
                        <Tag>{`${t('ccrg.yaml.profiles')}: ${s.activeProfiles || '—'}`}</Tag>
                        <Tag>{`${t('ccrg.yaml.count')}: ${s.propertyCount}`}</Tag>
                      </Space>
                    ),
                  })),
                  { key: 'count', label: t('ccrg.yaml.count'), children: result.propertyCount },
                  ...(result.sources.length > 1
                    ? [{ key: 'conflicts', label: t('ccrg.yaml.conflictCount'), children: result.conflicts.length }]
                    : []),
                ]}
              />
              {result.conflicts.length > 0 && (
                <Alert
                  type="warning"
                  showIcon
                  message={t('ccrg.yaml.conflicts', { count: result.conflicts.length })}
                  description={
                    <Table
                      size="small"
                      rowKey="key"
                      columns={conflictColumns}
                      dataSource={result.conflicts}
                      pagination={result.conflicts.length > 10 ? { pageSize: 10, size: 'small' } : false}
                      scroll={{ x: 'max-content' }}
                    />
                  }
                />
              )}
              {result.unresolved.length > 0 && (
                <Alert
                  type="warning"
                  showIcon
                  message={t('ccrg.yaml.unresolved', { count: result.unresolved.length })}
                  description={
                    <Space size={[4, 4]} wrap>
                      {result.unresolved.map((name) => (
                        <Tag key={name}>{name}</Tag>
                      ))}
                    </Space>
                  }
                />
              )}
              <pre
                style={{
                  margin: 0,
                  padding: token.paddingSM,
                  maxHeight: 640,
                  overflow: 'auto',
                  background: token.colorFillAlter,
                  border: `1px solid ${token.colorBorderSecondary}`,
                  borderRadius: token.borderRadius,
                  fontFamily: token.fontFamilyCode,
                  fontSize: token.fontSizeSM,
                  lineHeight: 1.6,
                }}
              >
                {result.yaml}
              </pre>
            </Space>
          )}
        </ProCard>
      </Space>
    </PageContainer>
  );
}
