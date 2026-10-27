import { useCallback, useEffect, useRef, useState } from 'react';
import { PageContainer, ProCard } from '@ant-design/pro-components';
import {
  Alert,
  App,
  Button,
  Col,
  Collapse,
  Descriptions,
  Empty,
  Form,
  Input,
  Row,
  Select,
  Space,
  Statistic,
  Switch,
  Table,
  Tabs,
  Tag,
  Tooltip,
  Typography,
  theme,
} from 'antd';
import type { TableColumnsType } from 'antd';
import { DeleteOutlined, DiffOutlined, DownloadOutlined, FolderOpenOutlined } from '@ant-design/icons';
import { RISK_CATEGORIES, diffUrl, migrationRiskApi } from '../../api/migrationRisk';
import type {
  AnalysisSummary,
  ChangedFileView,
  CodeLocation,
  CommitView,
  DetectedRefactoring,
  IgnoreRule,
  MigrationRiskAnalysis,
  MigrationRiskSettings,
  ReleaseView,
  RiskCategory,
  RiskItem,
} from '../../api/migrationRisk';
import { useI18n } from '../../locales';
import type { MessageKey } from '../../locales';
import { AnalysisForm, EMPTY_FORM } from './AnalysisForm';
import { JobProgressCard } from './JobProgressCard';
import { useAnalysisJob } from './useAnalysisJob';
import { ApiError } from '../../api/client';
import { ItemsTable } from './ItemsTable';
import { CATEGORY_LEVEL, LEVEL_COLOR, fieldErrors } from './constants';
import type { FormValues } from './constants';

const { Text, Paragraph } = Typography;

function Locations({ locations }: { locations: CodeLocation[] }) {
  return (
    <Space direction="vertical" size={0}>
      {locations.map((l) => (
        <Tooltip key={`${l.path}:${l.startLine}:${l.kind}`} title={l.element}>
          <Text code style={{ fontSize: 12 }}>{`${l.path}:${l.startLine}-${l.endLine}`}</Text>
        </Tooltip>
      ))}
    </Space>
  );
}

function Commit({ commit }: { commit: CommitView }) {
  return (
    <Tooltip title={`${commit.id} · ${commit.author} · ${new Date(commit.time).toLocaleString()}`}>
      <Space size={4}>
        <Text code>{commit.shortId}</Text>
        {commit.revision !== commit.id && <Tag>{commit.revision}</Tag>}
        <Text type="secondary" ellipsis style={{ maxWidth: 260 }}>{commit.message}</Text>
      </Space>
    </Tooltip>
  );
}

/** "2026-10-25 20:51 · 9 files · 3 synced · 3 safe / 1 attention / 5 pending" */
const runLabel = (a: AnalysisSummary, t: ReturnType<typeof useI18n>['t']) =>
  t('migrationRisk.saved.runLabel', {
    time: new Date(a.analyzedAt).toLocaleString(),
    total: a.counts.total,
    synced: a.review.synced ?? 0,
    safe: a.review.safe,
    attention: a.review.attention,
    pending: a.review.pending,
  });

/** Saves a fetched file under its server-given name. */
function download(blob: Blob, name: string) {
  const url = URL.createObjectURL(blob);
  const a = document.createElement('a');
  a.href = url;
  a.download = name;
  a.click();
  setTimeout(() => URL.revokeObjectURL(url), 1000);
}

/** Stat boxes coloured by what they ask of the reader: red = act, yellow = look, green = done. Design tokens, not CSS. */
const STAT_TONE: Record<string, 'Error' | 'Warning' | 'Success' | 'Info' | undefined> = {
  HIGH: 'Error',
  ATTENTION: 'Error',
  MEDIUM: 'Warning',
  SAFE: 'Success',
  SYNCED: 'Info',
};

export function MigrationRiskPage() {
  const { t } = useI18n();
  const { message } = App.useApp();
  const { token } = theme.useToken();
  const [form] = Form.useForm<FormValues>();

  const [settings, setSettings] = useState<MigrationRiskSettings | null>(null);
  const [releases, setReleases] = useState<ReleaseView[]>([]);
  const [openRelease, setOpenRelease] = useState<number | null>(null);
  const [runs, setRuns] = useState<AnalysisSummary[]>([]);
  const [analysis, setAnalysis] = useState<MigrationRiskAnalysis | null>(null);
  const [loading, setLoading] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [structuralOnly, setStructuralOnly] = useState(true);
  const [resolved, setResolved] = useState<RiskItem[]>([]);
  const [ignoreRules, setIgnoreRules] = useState<IgnoreRule[]>([]);
  const [newRule, setNewRule] = useState({ pattern: '', category: null as RiskCategory | null, note: '' });
  const [exporting, setExporting] = useState(false);
  const job = useAnalysisJob();

  /** Guards against an older answer resolving after a newer one (StrictMode double mount, fast clicks). */
  const requestId = useRef(0);

  const show = useCallback(async (call: () => Promise<MigrationRiskAnalysis>) => {
    const current = ++requestId.current;
    setLoading(true);
    setError(null);
    try {
      const result = await call();
      if (current !== requestId.current) return;
      setAnalysis(result);
      return result;
    } catch (e) {
      if (current !== requestId.current) return;
      if (e instanceof ApiError && e.body?.error === 'cancelled') {
        message.info(t('migrationRisk.job.cancelled'));
        return;
      }
      setAnalysis(null);
      setError((e as Error).message);
      form.setFields(fieldErrors(e));
    } finally {
      if (current === requestId.current) setLoading(false);
    }
  }, [form, message, t]);

  const loadIgnoreRules = useCallback(() => {
    migrationRiskApi.ignoreRules().then(setIgnoreRules).catch((e: Error) => message.error(e.message));
  }, [message]);

  const loadReleases = useCallback(() => {
    migrationRiskApi.releases().then(setReleases).catch((e: Error) => message.error(e.message));
  }, [message]);

  const analyze = useCallback(async (v: FormValues) => {
    const release = v.release?.trim() || undefined;
    // A background job followed over SSE: large projects take minutes, the page shows where it is meanwhile.
    const repo2bPath = v.repo2bPath?.trim() || undefined;
    const skipTests = !!v.skipTests;
    const result = await show(() => job.run(() => (v.mode === 'DIRECTORY'
      ? migrationRiskApi.startDirectoryAnalysis({
        repo1Path: v.repo1Path, repo2Path: v.repo2Path, repo2bPath, skipTests, release,
      })
      : migrationRiskApi.startAnalysis({
        repo1Path: v.repo1Path, repo1Head: v.repo1Head, repo2Path: v.repo2Path,
        repo2Head: v.repo2Head, repo2bPath, repo2bHead: repo2bPath ? v.repo2bHead : undefined, skipTests, release,
      }))));
    if (result?.analysisId != null) {
      message.success(t('migrationRisk.saved.done', { release: result.release ?? '' }));
      loadReleases();
    }
  }, [show, job, loadReleases, message, t]);

  const runDemo = useCallback((mode: FormValues['mode'], demo = settings?.demo) => {
    if (!demo) return;
    // With repo2B: the demo's templates (.ftl), tests and config/ live there.
    const values: FormValues = mode === 'DIRECTORY'
      ? {
        ...EMPTY_FORM, mode, repo1Path: demo.repo1Folder, repo2Path: demo.repo2Folder,
        repo2bPath: demo.repo2bFolder,
      }
      : {
        ...EMPTY_FORM, mode, repo1Path: demo.repo1Path, repo1Head: demo.repo1Head,
        repo2Path: demo.repo2Path, repo2Head: demo.repo2Head, repo2bPath: demo.repo2bPath,
      };
    form.resetFields();
    form.setFieldsValue(values);
    void analyze(values);
  }, [settings, form, analyze]);

  useEffect(() => {
    migrationRiskApi.settings()
      .then((s) => {
        setSettings(s);
        // Read-only and well under a second, so the page opens on a real (unsaved) result.
        if (s.demo) runDemo('GIT', s.demo);
      })
      .catch((e: Error) => setError(e.message));
    loadReleases();
    loadIgnoreRules();
    // runDemo is stable enough for a mount-time call; re-running on its identity would re-analyse.
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, []);

  /** A re-run of a release: what its previous run had and this one does not. */
  useEffect(() => {
    const id = analysis?.analysisId;
    if (id == null) {
      setResolved([]);
      return;
    }
    migrationRiskApi.resolved(id).then(setResolved).catch(() => setResolved([]));
  }, [analysis?.analysisId]);

  const exportAs = async (format: 'xlsx' | 'md') => {
    if (!analysis) return;
    setExporting(true);
    try {
      const { blob, name } = await migrationRiskApi.export(analysis, format);
      download(blob, name);
    } catch (e) {
      message.error((e as Error).message);
    } finally {
      setExporting(false);
    }
  };

  const addRule = async () => {
    try {
      await migrationRiskApi.addIgnoreRule({
        pattern: newRule.pattern.trim(), category: newRule.category, note: newRule.note.trim() || undefined,
      });
      setNewRule({ pattern: '', category: null, note: '' });
      loadIgnoreRules();
      message.success(t('migrationRisk.ignore.added'));
    } catch (e) {
      message.error((e as Error).message);
    }
  };

  const deleteRule = async (id: number) => {
    try {
      await migrationRiskApi.deleteIgnoreRule(id);
      loadIgnoreRules();
    } catch (e) {
      message.error((e as Error).message);
    }
  };

  useEffect(() => {
    if (openRelease == null) {
      setRuns([]);
      return;
    }
    migrationRiskApi.releaseAnalyses(openRelease).then(setRuns).catch((e: Error) => message.error(e.message));
  }, [openRelease, message]);

  /** A saved run puts its inputs back into the form, so "Analyse" re-runs it under the same release. */
  const openRun = async (id: number) => {
    const result = await show(() => migrationRiskApi.savedAnalysis(id));
    if (!result) return;
    const s = result.source;
    form.resetFields();
    const common = { release: result.release ?? '', repo2bPath: s.repo2bPath ?? '', skipTests: !!s.skipTests };
    form.setFieldsValue(s.mode === 'DIRECTORY'
      ? { ...EMPTY_FORM, ...common, mode: 'DIRECTORY', repo1Path: s.repo1Path, repo2Path: s.repo2Path }
      : {
        ...EMPTY_FORM, ...common, mode: 'GIT', repo1Path: s.repo1Path, repo2Path: s.repo2Path,
        repo1Head: s.repo1Head?.revision ?? 'HEAD', repo2Head: s.repo2Head?.revision ?? 'HEAD',
        repo2bHead: s.repo2bHead?.revision ?? 'HEAD',
      });
  };

  const onReviewed = (updated: RiskItem[]) => {
    const byId = new Map(updated.map((i) => [i.id, i]));
    setAnalysis((prev) => prev && { ...prev, items: prev.items.map((i) => byId.get(i.id) ?? i) });
    loadReleases();
  };

  const categoryLabel = (c: RiskCategory) => t(`migrationRisk.category.${c}` as MessageKey);

  const refactoringColumns: TableColumnsType<DetectedRefactoring> = [
    { title: t('migrationRisk.col.type'), dataIndex: 'type', width: 200, render: (type: string) => <Tag>{type}</Tag> },
    {
      title: t('migrationRisk.col.description'),
      dataIndex: 'description',
      width: 520,
      render: (d: string) => <Paragraph style={{ margin: 0 }} ellipsis={{ rows: 3, tooltip: d }}>{d}</Paragraph>,
    },
    { title: t('migrationRisk.col.before'), dataIndex: 'before', width: 300, render: (l: CodeLocation[]) => <Locations locations={l} /> },
    { title: t('migrationRisk.col.after'), dataIndex: 'after', width: 300, render: (l: CodeLocation[]) => <Locations locations={l} /> },
  ];

  const fileColumns: TableColumnsType<ChangedFileView> = [
    { title: t('migrationRisk.col.change'), dataIndex: 'change', width: 110, render: (c: string) => <Tag>{c}</Tag> },
    {
      title: t('migrationRisk.col.path'),
      key: 'path',
      render: (_, f) => (f.change === 'RENAMED'
        ? <Text code>{`${f.oldPath} → ${f.newPath}`}</Text>
        : <Text code>{f.newPath ?? f.oldPath}</Text>),
    },
    { title: t('migrationRisk.col.fileKind'), dataIndex: 'kind', width: 100, render: (k: string) => <Tag>{k}</Tag> },
    { title: t('migrationRisk.col.addedLines'), dataIndex: 'addedLines', width: 120, align: 'right' },
    {
      title: t('migrationRisk.col.diff'),
      key: 'diff',
      width: 140,
      render: (_, f) => analysis && (
        <Button size="small" icon={<DiffOutlined />} target="_blank" disabled={f.change === 'RENAMED'}
          href={diffUrl(analysis.source, f.newPath ?? f.oldPath)}>{t('migrationRisk.diff.open')}</Button>
      ),
    },
  ];

  const refactoringTable = (list: DetectedRefactoring[]) => (
    <Table<DetectedRefactoring>
      rowKey={(r) => `${r.type}|${r.description}`}
      size="small"
      columns={refactoringColumns}
      dataSource={structuralOnly ? list.filter((r) => r.structural) : list}
      pagination={{ defaultPageSize: 20, showSizeChanger: true }}
      scroll={{ x: 1320 }}
    />
  );

  const counts = analysis?.counts;
  const items = analysis?.items ?? [];
  const reviewCount = (s: string) => items.filter((i) => i.review?.status === s).length;
  const source = analysis?.source;

  return (
    <PageContainer title={t('migrationRisk.title')} subTitle={t('migrationRisk.subtitle')}>
      <Space direction="vertical" size="middle" style={{ width: '100%' }}>
        <ProCard bordered>
          <AnalysisForm form={form} settings={settings} releases={releases} loading={loading}
            onAnalyze={(v) => void analyze(v)} onDemo={(mode) => runDemo(mode)} />
        </ProCard>

        <ProCard bordered title={t('migrationRisk.saved.title')} collapsible defaultCollapsed={releases.length === 0}>
          <Space wrap>
            <Select<number>
              style={{ minWidth: 260 }}
              placeholder={t('migrationRisk.saved.release')}
              value={openRelease ?? undefined}
              onChange={setOpenRelease}
              allowClear
              onClear={() => setOpenRelease(null)}
              options={releases.map((r) => ({
                value: r.id,
                label: `${r.name} (${t('migrationRisk.saved.runs', { count: r.analysisCount })})`,
              }))}
            />
            <Select<number>
              style={{ minWidth: 420 }}
              placeholder={t('migrationRisk.saved.run')}
              disabled={runs.length === 0}
              onChange={(id) => void openRun(id)}
              options={runs.map((a) => ({ value: a.id, label: runLabel(a, t) }))}
            />
            {openRelease != null && runs.length > 0 && (
              <Button icon={<FolderOpenOutlined />} onClick={() => void openRun(runs[0].id)}>
                {t('migrationRisk.saved.openLatest')}
              </Button>
            )}
          </Space>
        </ProCard>

        {job.running && (
          <JobProgressCard progress={job.progress} startedAt={job.startedAt} onCancel={() => void job.cancel()} />
        )}

        {error && <Alert type="error" showIcon message={t('migrationRisk.failed')} description={error} />}

        {analysis && analysis.warnings.length > 0 && (
          <Alert
            type="warning"
            showIcon
            message={t('migrationRisk.warnings', { count: analysis.warnings.length })}
            description={<ul style={{ margin: 0, paddingInlineStart: 18 }}>{analysis.warnings.map((w) => <li key={w}>{w}</li>)}</ul>}
          />
        )}

        {analysis && source && (
          <ProCard bordered>
            <Descriptions size="small" column={{ xs: 1, md: 2, xl: 3 }}>
              {analysis.release ? (
                <Descriptions.Item label={t('migrationRisk.release')}>
                  <Tag color="blue">{analysis.release}</Tag><Text type="secondary">#{analysis.analysisId}</Text>
                </Descriptions.Item>
              ) : (
                <Descriptions.Item label={t('migrationRisk.release')}>
                  <Tooltip title={t('migrationRisk.saved.notSavedHint')}><Tag>{t('migrationRisk.saved.notSaved')}</Tag></Tooltip>
                </Descriptions.Item>
              )}
              {source.mode === 'GIT' && source.repo1Head && source.repo2Head ? (
                <>
                  <Descriptions.Item label={t('migrationRisk.repo1Branch')}><Commit commit={source.repo1Head} /></Descriptions.Item>
                  <Descriptions.Item label={t('migrationRisk.repo2Branch')}><Commit commit={source.repo2Head} /></Descriptions.Item>
                  {source.repo2bHead && (
                    <Descriptions.Item label={t('migrationRisk.repo2bBranch')}>
                      <Space size={4}><Tag color="cyan">repo2B</Tag><Commit commit={source.repo2bHead} /></Space>
                    </Descriptions.Item>
                  )}
                </>
              ) : (
                <>
                  <Descriptions.Item label={t('migrationRisk.folder.repo1')}><Text code>{source.repo1Path}</Text></Descriptions.Item>
                  <Descriptions.Item label={t('migrationRisk.folder.repo2')}><Text code>{source.repo2Path}</Text></Descriptions.Item>
                  {source.repo2bPath && (
                    <Descriptions.Item label={t('migrationRisk.folder.repo2b')}><Text code>{source.repo2bPath}</Text></Descriptions.Item>
                  )}
                </>
              )}
              <Descriptions.Item label={t('migrationRisk.tests')}>
                {source.skipTests
                  ? <Tag color="purple">{t('migrationRisk.tests.skipped')}</Tag>
                  : <Text type="secondary">{t('migrationRisk.tests.included')}</Text>}
              </Descriptions.Item>
              <Descriptions.Item label={t('migrationRisk.analyzedAt')}>
                {new Date(analysis.analyzedAt).toLocaleString()}
                <Text type="secondary">&nbsp;({t('migrationRisk.duration', { ms: analysis.durationMillis })})</Text>
              </Descriptions.Item>
              <Descriptions.Item label={t('migrationRisk.export.title')}>
                <Space size={4}>
                  <Button size="small" icon={<DownloadOutlined />} loading={exporting} onClick={() => void exportAs('xlsx')}>
                    Excel
                  </Button>
                  <Button size="small" icon={<DownloadOutlined />} loading={exporting} onClick={() => void exportAs('md')}>
                    Markdown
                  </Button>
                </Space>
              </Descriptions.Item>
              <Descriptions.Item label={t('migrationRisk.diff.title')}>
                <Tooltip title={t('migrationRisk.diff.hint')}>
                  <Button size="small" icon={<DiffOutlined />} href={diffUrl(source)} target="_blank">{t('migrationRisk.diff.open')}</Button>
                </Tooltip>
              </Descriptions.Item>
            </Descriptions>
          </ProCard>
        )}

        <Collapse
          items={[{
            key: 'ignore',
            label: t('migrationRisk.ignore.panel', { count: ignoreRules.length }),
            children: (
              <Space direction="vertical" size="small" style={{ width: '100%' }}>
                <Paragraph type="secondary" style={{ margin: 0 }}>{t('migrationRisk.ignore.hint')}</Paragraph>
                <Space wrap>
                  <Input value={newRule.pattern} placeholder="**/generated/**" style={{ width: 280 }}
                    onChange={(e) => setNewRule({ ...newRule, pattern: e.target.value })} />
                  <Select<RiskCategory | null>
                    value={newRule.category}
                    onChange={(v) => setNewRule({ ...newRule, category: v })}
                    style={{ width: 220 }}
                    options={[
                      { value: null, label: t('migrationRisk.ignore.wholeFile') },
                      ...RISK_CATEGORIES.map((c) => ({ value: c, label: categoryLabel(c) })),
                    ]}
                  />
                  <Input value={newRule.note} placeholder={t('migrationRisk.ignore.note')} style={{ width: 240 }}
                    maxLength={1000} onChange={(e) => setNewRule({ ...newRule, note: e.target.value })} />
                  <Button type="primary" disabled={!newRule.pattern.trim()} onClick={() => void addRule()}>
                    {t('migrationRisk.ignore.save')}
                  </Button>
                </Space>
                <Table<IgnoreRule>
                  rowKey="id"
                  size="small"
                  pagination={false}
                  dataSource={ignoreRules}
                  columns={[
                    { title: t('migrationRisk.ignore.pattern'), dataIndex: 'pattern', render: (p: string) => <Text code>{p}</Text> },
                    {
                      title: t('migrationRisk.col.category'),
                      dataIndex: 'category',
                      width: 200,
                      render: (c: RiskCategory | null) => (c ? categoryLabel(c) : t('migrationRisk.ignore.wholeFile')),
                    },
                    {
                      title: t('migrationRisk.ignore.scope'),
                      dataIndex: 'scope',
                      render: (s: string | null) => (s ? <Text code>{s}</Text> : t('migrationRisk.ignore.allProjects')),
                    },
                    { title: t('migrationRisk.ignore.note'), dataIndex: 'note' },
                    {
                      key: 'delete',
                      width: 60,
                      render: (_, r) => (
                        <Button size="small" type="text" danger icon={<DeleteOutlined />} onClick={() => void deleteRule(r.id)} />
                      ),
                    },
                  ]}
                />
              </Space>
            ),
          }, {
            key: 'rules',
            label: t('migrationRisk.rules.title'),
            children: (
              <Space direction="vertical" size="small" style={{ width: '100%' }}>
                <Paragraph type="secondary" style={{ margin: 0 }}>{t('migrationRisk.rules.method')}</Paragraph>
                <Table<RiskCategory>
                  rowKey={(c) => c}
                  size="small"
                  pagination={false}
                  dataSource={[...RISK_CATEGORIES]}
                  columns={[
                    {
                      title: t('migrationRisk.col.level'),
                      key: 'level',
                      width: 110,
                      render: (_, c) => {
                        const level = CATEGORY_LEVEL[c];
                        return <Tag color={LEVEL_COLOR[level]}>{t(`migrationRisk.level.${level}` as MessageKey)}</Tag>;
                      },
                    },
                    { title: t('migrationRisk.col.category'), key: 'category', width: 200, render: (_, c) => categoryLabel(c) },
                    { title: t('migrationRisk.rules.meaning'), key: 'meaning', render: (_, c) => t(`migrationRisk.categoryHint.${c}` as MessageKey) },
                  ]}
                />
              </Space>
            ),
          }]}
        />

        {counts && (
          <Row gutter={16}>
            {([
              ['total', counts.total, t('migrationRisk.stat.total')],
              ['HIGH', counts.high, t('migrationRisk.level.HIGH')],
              ['MEDIUM', counts.medium, t('migrationRisk.level.MEDIUM')],
              ['LOW', counts.low, t('migrationRisk.level.LOW')],
              ...(analysis?.analysisId != null ? [
                ['SYNCED', reviewCount('SYNCED'), t('migrationRisk.review.SYNCED')],
                ['PENDING', reviewCount('PENDING'), t('migrationRisk.review.PENDING')],
                ['SAFE', reviewCount('SAFE'), t('migrationRisk.review.SAFE')],
                ['ATTENTION', reviewCount('ATTENTION'), t('migrationRisk.review.ATTENTION')],
              ] : []),
            ] as [string, number, string][]).map(([key, value, title]) => {
              const tone = STAT_TONE[key];
              return (
                <Col key={key} xs={12} md={6} xl={analysis?.analysisId != null ? 3 : 6}>
                  <ProCard bordered style={tone && { background: token[`color${tone}Bg`], borderColor: token[`color${tone}Border`] }}>
                    {/* synced is the sync's progress: shown out of the total */}
                    <Statistic title={title} value={value} suffix={key === 'SYNCED' ? `/ ${counts.total}` : undefined}
                      valueStyle={tone && { color: token[`color${tone}`] }} />
                  </ProCard>
                </Col>
              );
            })}
          </Row>
        )}

        <ProCard bordered>
          {analysis || loading ? (
            <Tabs
              items={[
                {
                  key: 'items',
                  label: t('migrationRisk.tab.items', { count: items.length }),
                  children: (
                    <ItemsTable analysis={analysis} settings={settings} loading={loading} onReviewed={onReviewed}
                      onIgnoreRuleAdded={loadIgnoreRules} />
                  ),
                },
                ...(resolved.length > 0 ? [{
                  key: 'resolved',
                  label: t('migrationRisk.tab.resolved', { count: resolved.length }),
                  children: (
                    <Space direction="vertical" style={{ width: '100%' }}>
                      <Text type="secondary">{t('migrationRisk.resolved.hint')}</Text>
                      <Table<RiskItem>
                        rowKey="id"
                        size="small"
                        dataSource={resolved}
                        pagination={{ defaultPageSize: 20, showSizeChanger: true }}
                        columns={[
                          {
                            title: t('migrationRisk.col.level'),
                            dataIndex: 'level',
                            width: 90,
                            render: (l: RiskItem['level']) => <Tag color={LEVEL_COLOR[l]}>{t(`migrationRisk.level.${l}` as MessageKey)}</Tag>,
                          },
                          { title: t('migrationRisk.col.category'), dataIndex: 'category', width: 180, render: (c: RiskCategory) => categoryLabel(c) },
                          { title: t('migrationRisk.col.file'), dataIndex: 'element', render: (e: string) => <Text code>{e}</Text> },
                          {
                            title: t('migrationRisk.resolved.lastReview'),
                            key: 'review',
                            width: 220,
                            render: (_, i) => i.review && (
                              <Space size={4}>
                                <Tag>{t(`migrationRisk.review.${i.review.status}` as MessageKey)}</Tag>
                                {i.review.note && <Text type="secondary" style={{ fontSize: 12 }}>{i.review.note}</Text>}
                              </Space>
                            ),
                          },
                        ]}
                      />
                    </Space>
                  ),
                }] : []),
                {
                  key: 'refactorings',
                  label: t('migrationRisk.tab.refactorings', {
                    count: (analysis?.refactorings ?? []).filter((r) => !structuralOnly || r.structural).length,
                  }),
                  // saved baseline runs have none: their lists were upstream / migration
                  children: refactoringTable(analysis?.refactorings ?? []),
                },
                {
                  key: 'files',
                  label: t('migrationRisk.tab.files', { count: analysis?.changedFiles.length ?? 0 }),
                  children: (
                    <Table<ChangedFileView>
                      rowKey={(f) => `${f.change}|${f.oldPath}|${f.newPath}`}
                      size="small"
                      columns={fileColumns}
                      dataSource={analysis?.changedFiles ?? []}
                      pagination={{ defaultPageSize: 50, showSizeChanger: true }}
                    />
                  ),
                },
              ]}
              tabBarExtraContent={(
                <Tooltip title={t('migrationRisk.structuralOnlyHint')}>
                  <Space size={6}>
                    <Switch size="small" checked={structuralOnly} onChange={setStructuralOnly} />
                    <Text type="secondary">{t('migrationRisk.structuralOnly')}</Text>
                  </Space>
                </Tooltip>
              )}
            />
          ) : (
            <Empty description={t('migrationRisk.empty')} />
          )}
        </ProCard>
      </Space>
    </PageContainer>
  );
}
