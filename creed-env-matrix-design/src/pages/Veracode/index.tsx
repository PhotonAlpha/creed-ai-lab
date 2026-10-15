import { useCallback, useEffect, useMemo, useRef, useState } from 'react';
import { PageContainer, ProCard } from '@ant-design/pro-components';
import {
  Alert,
  App,
  Button,
  Checkbox,
  Col,
  Descriptions,
  Empty,
  Input,
  Popconfirm,
  Row,
  Segmented,
  Space,
  Statistic,
  Table,
  Tag,
  Tooltip,
  Typography,
  Upload,
} from 'antd';
import type { TableColumnsType } from 'antd';
import { CloudDownloadOutlined, InboxOutlined, PlusCircleOutlined, ReloadOutlined, SearchOutlined } from '@ant-design/icons';
import { UPLOAD_ACCEPT, veracodeApi } from '../../api/veracode';
import type { Analysis, CreateResult, Finding, FindingStatus, ReportSource, VeracodeSettings } from '../../api/veracode';
import { ApiError } from '../../api/client';
import { useI18n } from '../../locales';
import type { MessageKey } from '../../locales';

const { Text, Paragraph } = Typography;

type StatusFilter = 'ALL' | FindingStatus;
type SourceMode = 'page' | 'upload';

/** Follow-up requests name the report the analysis on screen came from — the page, or the upload id. */
const sourceOf = (a: Analysis): ReportSource => (a.page.source === 'UPLOAD' ? { uploadId: a.page.id } : { pageId: a.page.id });

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
const severityRank = (s: string | null) => (s ? SEVERITY_RANK[s.trim().toLowerCase()] ?? -1 : -1);
const SEVERITY_COLOR = ['default', 'blue', 'gold', 'orange', 'volcano', 'red'];

const STATUS_COLOR: Record<FindingStatus, string> = { EXISTS: 'success', MISSING: 'error', CREATED: 'processing' };

/** Only a missing finding whose version exists in Jira can be created — the server would refuse the rest. */
const creatable = (f: Finding) => f.status === 'MISSING' && f.jiraVersion != null;

const matchesKeyword = (f: Finding, keyword: string) => {
  if (!keyword) return true;
  const k = keyword.toLowerCase();
  return [f.cve, f.title, f.affectedVersion, ...f.components, ...f.issues.map((i) => i.key)]
    .some((v) => v?.toLowerCase().includes(k));
};

export function VeracodePage() {
  const { t } = useI18n();
  const { message, modal } = App.useApp();

  const [settings, setSettings] = useState<VeracodeSettings | null>(null);
  const [mode, setMode] = useState<SourceMode>('page');
  const [pageId, setPageId] = useState('');
  const [autoCreate, setAutoCreate] = useState(false);
  const [analysis, setAnalysis] = useState<Analysis | null>(null);
  const [loading, setLoading] = useState(false);
  const [creating, setCreating] = useState(false);
  const [exporting, setExporting] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [statusFilter, setStatusFilter] = useState<StatusFilter>('ALL');
  const [keyword, setKeyword] = useState('');
  const [selected, setSelected] = useState<string[]>([]);

  /** Guards against an older analysis resolving after a newer one (StrictMode double mount, fast clicks). */
  const requestId = useRef(0);

  const run = useCallback(async (call: () => Promise<Analysis>, auto: boolean) => {
    const current = ++requestId.current;
    setLoading(true);
    setError(null);
    try {
      const result = await call();
      if (current !== requestId.current) return;
      setAnalysis(result);
      setSelected([]);
      if (auto && result.counts.created > 0) {
        message.success(t('veracode.createdCount', { count: result.counts.created }));
      }
    } catch (e) {
      if (current !== requestId.current) return;
      setAnalysis(null);
      setError(e instanceof ApiError && e.body?.error === 'upload_expired' ? t('veracode.uploadExpired') : (e as Error).message);
    } finally {
      if (current === requestId.current) setLoading(false);
    }
  }, [message, t]);

  const analyze = useCallback(
    (source: ReportSource, auto: boolean) => run(() => veracodeApi.analyze(source, auto), auto),
    [run],
  );

  /**
   * `false` keeps antd's Upload from posting the file itself; the page sends it so the result lands in
   * the same request-id guard as a page analysis. Auto-create asks first — the Upload trigger cannot sit
   * inside a Popconfirm the way the Analyse button does.
   */
  const beforeUpload = (file: File) => {
    const send = () => run(() => veracodeApi.analyzeUpload(file, autoCreate), autoCreate);
    if (autoCreate) {
      modal.confirm({
        title: t('veracode.autoCreateConfirm', { project: settings?.projectKey ?? '' }),
        onOk: send,
      });
    } else {
      void send();
    }
    return false;
  };

  useEffect(() => {
    veracodeApi.settings()
      .then((s) => {
        setSettings(s);
        const initial = s.defaultPageId ?? '';
        setPageId(initial);
        // Never auto-create on page load: that would write to Jira without anyone asking.
        if (initial) void analyze({ pageId: initial }, false);
      })
      .catch((e: Error) => setError(e.message));
  }, [analyze]);

  const pageIdValid = /^\d{1,20}$/.test(pageId.trim());

  /**
   * Applies per-row outcomes to the rows on screen instead of re-analysing: the answer already says
   * which issue each finding now has, and a second round trip to Confluence and Jira adds nothing.
   */
  const applyResults = (results: CreateResult[]) => {
    const byId = new Map(results.map((r) => [r.findingId, r]));
    setAnalysis((prev) => {
      if (!prev) return prev;
      const findings = prev.findings.map((f): Finding => {
        const r = byId.get(f.id);
        if (r?.outcome === 'CREATED') return { ...f, status: 'CREATED', issues: r.issues };
        if (r?.outcome === 'ALREADY_EXISTS') return { ...f, status: 'EXISTS', issues: r.issues };
        return f;
      });
      const count = (s: FindingStatus) => findings.filter((f) => f.status === s).length;
      return {
        ...prev,
        findings,
        counts: { ...prev.counts, exists: count('EXISTS'), missing: count('MISSING'), created: count('CREATED') },
      };
    });
    setSelected((prev) => prev.filter((id) => byId.get(id)?.outcome !== 'CREATED'
      && byId.get(id)?.outcome !== 'ALREADY_EXISTS'));

    const created = results.filter((r) => r.outcome === 'CREATED').length;
    const others = results.filter((r) => r.outcome !== 'CREATED');
    if (created > 0) message.success(t('veracode.createdCount', { count: created }));
    if (others.length > 0) {
      modal.warning({
        title: t('veracode.notCreatedTitle', { count: others.length }),
        width: 640,
        content: (
          <ul style={{ paddingInlineStart: 18, margin: 0 }}>
            {others.map((r) => (
              <li key={r.findingId}>
                <Text code>{r.findingId}</Text> — {t(`veracode.outcome.${r.outcome}` as MessageKey)}
                {r.issues.length > 0 && <> ({r.issues.map((i) => i.key).join(', ')})</>}
                {r.message && <div><Text type="secondary">{r.message}</Text></div>}
              </li>
            ))}
          </ul>
        ),
      });
    }
  };

  const create = async (ids: string[]) => {
    if (!analysis || ids.length === 0) return;
    setCreating(true);
    try {
      const { results } = await veracodeApi.createIssues(sourceOf(analysis), ids);
      applyResults(results);
    } catch (e) {
      message.error((e as Error).message);
    } finally {
      setCreating(false);
    }
  };

  const exportXlsx = async () => {
    if (!analysis) return;
    setExporting(true);
    try {
      const { blob, filename } = await veracodeApi.exportXlsx(sourceOf(analysis));
      const url = URL.createObjectURL(blob);
      const a = document.createElement('a');
      a.href = url;
      a.download = filename;
      a.click();
      URL.revokeObjectURL(url);
    } catch (e) {
      message.error((e as Error).message);
    } finally {
      setExporting(false);
    }
  };

  const rows = useMemo(
    () => (analysis?.findings ?? [])
      .filter((f) => statusFilter === 'ALL' || f.status === statusFilter)
      .filter((f) => matchesKeyword(f, keyword.trim())),
    [analysis, statusFilter, keyword],
  );
  const missingCreatable = (analysis?.findings ?? []).filter(creatable).map((f) => f.id);

  const columns: TableColumnsType<Finding> = [
    {
      title: t('veracode.col.status'),
      dataIndex: 'status',
      width: 110,
      render: (s: FindingStatus) => <Tag color={STATUS_COLOR[s]}>{t(`veracode.status.${s}` as MessageKey)}</Tag>,
    },
    {
      title: 'CVE',
      dataIndex: 'cve',
      width: 160,
      render: (cve: string | null) => cve
        ? <a href={`https://nvd.nist.gov/vuln/detail/${cve}`} target="_blank" rel="noreferrer">{cve}</a>
        : <Text type="secondary">{t('veracode.noCve')}</Text>,
    },
    {
      title: t('veracode.col.title'),
      dataIndex: 'title',
      width: 320,
      render: (title: string, f) => (
        <Tooltip title={f.description} placement="topLeft">
          <Paragraph style={{ margin: 0 }} ellipsis={{ rows: 2 }}>{title}</Paragraph>
        </Tooltip>
      ),
    },
    {
      title: t('veracode.col.components'),
      dataIndex: 'components',
      width: 240,
      render: (components: string[]) => (
        <Space size={[0, 4]} wrap>{components.map((c) => <Tag key={c}>{c}</Tag>)}</Space>
      ),
    },
    {
      title: t('veracode.col.severity'),
      dataIndex: 'severity',
      width: 110,
      sorter: (a, b) => severityRank(a.severity) - severityRank(b.severity),
      render: (s: string | null) => s
        ? <Tag color={SEVERITY_COLOR[Math.max(0, severityRank(s))]}>{s}</Tag>
        : null,
    },
    {
      title: 'CVSS',
      dataIndex: 'cvss',
      width: 80,
      sorter: (a, b) => (a.cvss ?? -1) - (b.cvss ?? -1),
    },
    {
      title: t('veracode.col.affectedVersion'),
      dataIndex: 'affectedVersion',
      width: 150,
      render: (v: string, f) => (
        <Space size={4}>
          {v}
          {f.jiraVersion == null && (
            <Tooltip title={t('veracode.versionNotInJiraHint', { project: analysis?.projectKey ?? '' })}>
              <Tag color="warning">{t('veracode.versionNotInJira')}</Tag>
            </Tooltip>
          )}
        </Space>
      ),
    },
    { title: t('veracode.col.fixedVersion'), dataIndex: 'fixedVersion', width: 140 },
    {
      title: t('veracode.col.jira'),
      dataIndex: 'issues',
      width: 200,
      render: (_, f) => (
        <Space direction="vertical" size={0}>
          {f.issues.map((i) => (
            <Tooltip key={i.key} title={i.summary}>
              <Space size={4}>
                <a href={i.url} target="_blank" rel="noreferrer">{i.key}</a>
                {i.status && <Text type="secondary">{i.status}</Text>}
              </Space>
            </Tooltip>
          ))}
        </Space>
      ),
    },
    {
      title: t('veracode.col.actions'),
      key: 'actions',
      width: 120,
      fixed: 'right',
      render: (_, f) => creatable(f) && (
        <Popconfirm
          title={t('veracode.createOneConfirm', { project: analysis?.projectKey ?? '' })}
          onConfirm={() => create([f.id])}
        >
          <Button size="small" icon={<PlusCircleOutlined />} loading={creating}>{t('veracode.createOne')}</Button>
        </Popconfirm>
      ),
    },
  ];

  const counts = analysis?.counts;

  return (
    <PageContainer title={t('veracode.title')} subTitle={t('veracode.subtitle')}>
      <Space direction="vertical" size="middle" style={{ width: '100%' }}>
        <ProCard bordered>
          <Space wrap align="center">
            <Segmented<SourceMode>
              value={mode}
              onChange={setMode}
              options={[
                { value: 'page', label: t('veracode.source.page') },
                { value: 'upload', label: t('veracode.source.upload') },
              ]}
            />
            {mode === 'page' && (
              <Input
                  value={pageId}
                  onChange={(e) => setPageId(e.target.value)}
                  onPressEnter={() => pageIdValid && !autoCreate && analyze({ pageId: pageId.trim() }, false)}
                  status={pageId && !pageIdValid ? 'error' : undefined}
                  placeholder={t('veracode.pageId')}
                  style={{ width: 200 }}
                  allowClear
                />
            )}
            <Checkbox checked={autoCreate} onChange={(e) => setAutoCreate(e.target.checked)}>
              {t('veracode.autoCreate')}
            </Checkbox>
            {mode === 'page' && (autoCreate ? (
              <Popconfirm
                title={t('veracode.autoCreateConfirm', { project: settings?.projectKey ?? '' })}
                onConfirm={() => analyze({ pageId: pageId.trim() }, true)}
                disabled={!pageIdValid}
              >
                <Button type="primary" icon={<SearchOutlined />} loading={loading} disabled={!pageIdValid}>
                  {t('veracode.analyze')}
                </Button>
              </Popconfirm>
            ) : (
              <Button
                type="primary"
                icon={<SearchOutlined />}
                loading={loading}
                disabled={!pageIdValid}
                onClick={() => analyze({ pageId: pageId.trim() }, false)}
              >
                {t('veracode.analyze')}
              </Button>
            ))}
            {analysis?.page.source === 'UPLOAD' && mode === 'upload' && (
              <Tooltip title={t('veracode.reanalyzeHint')}>
                <Button icon={<ReloadOutlined />} loading={loading} onClick={() => analyze(sourceOf(analysis), false)}>
                  {t('veracode.reanalyze')}
                </Button>
              </Tooltip>
            )}
            {settings?.mock && (
              <Tooltip title={t('veracode.mockHint')}><Tag color="purple">{t('veracode.mock')}</Tag></Tooltip>
            )}
          </Space>
          {mode === 'upload' && (
            <Upload.Dragger
              accept={UPLOAD_ACCEPT}
              maxCount={1}
              showUploadList={false}
              beforeUpload={beforeUpload}
              disabled={loading}
              style={{ marginTop: 16 }}
            >
              <p className="ant-upload-drag-icon"><InboxOutlined /></p>
              <p className="ant-upload-text">{t('veracode.upload.title')}</p>
              <p className="ant-upload-hint">{t('veracode.upload.hint')}</p>
            </Upload.Dragger>
          )}
          {analysis && (
            <Descriptions size="small" column={{ xs: 1, md: 2, xl: 4 }} style={{ marginTop: 16 }}>
              <Descriptions.Item label={analysis.page.source === 'UPLOAD' ? t('veracode.file') : t('veracode.page')}>
                {analysis.page.url
                  ? <a href={analysis.page.url} target="_blank" rel="noreferrer">{analysis.page.title}</a>
                  : analysis.page.title}
                {analysis.page.version != null && <Text type="secondary">&nbsp;v{analysis.page.version}</Text>}
              </Descriptions.Item>
              <Descriptions.Item label={t('veracode.project')}>{analysis.projectKey}</Descriptions.Item>
              <Descriptions.Item label={t('veracode.jiraVersions')}>
                {analysis.jiraVersions.join(', ') || '—'}
              </Descriptions.Item>
              <Descriptions.Item label={t('veracode.analyzedAt')}>
                {new Date(analysis.analyzedAt).toLocaleString()}
              </Descriptions.Item>
            </Descriptions>
          )}
        </ProCard>

        {error && <Alert type="error" showIcon message={t('veracode.failed')} description={error} />}

        {analysis && analysis.warnings.length > 0 && (
          <Alert
            type="warning"
            showIcon
            message={t('veracode.warnings', { count: analysis.warnings.length })}
            description={<ul style={{ margin: 0, paddingInlineStart: 18 }}>{analysis.warnings.map((w) => <li key={w}>{w}</li>)}</ul>}
          />
        )}

        {counts && (
          <Row gutter={16}>
            {([
              ['total', counts.total],
              ['EXISTS', counts.exists],
              ['MISSING', counts.missing],
              ['CREATED', counts.created],
              ['versionNotInJira', counts.versionNotInJira],
            ] as const).map(([key, value]) => (
              <Col key={key} xs={12} md={8} xl={4}>
                <ProCard bordered>
                  <Statistic
                    title={key === 'total' ? t('veracode.stat.total')
                      : key === 'versionNotInJira' ? t('veracode.versionNotInJira')
                        : t(`veracode.status.${key}` as MessageKey)}
                    value={value}
                  />
                </ProCard>
              </Col>
            ))}
          </Row>
        )}

        <ProCard
          bordered
          title={t('veracode.findings')}
          extra={(
            <Space wrap>
              <Segmented<StatusFilter>
                value={statusFilter}
                onChange={setStatusFilter}
                options={[
                  { value: 'ALL', label: t('veracode.filter.all') },
                  { value: 'EXISTS', label: t('veracode.status.EXISTS') },
                  { value: 'MISSING', label: t('veracode.status.MISSING') },
                  { value: 'CREATED', label: t('veracode.status.CREATED') },
                ]}
              />
              <Input.Search
                allowClear
                placeholder={t('veracode.search')}
                onSearch={setKeyword}
                onChange={(e) => !e.target.value && setKeyword('')}
                style={{ width: 220 }}
              />
              <Popconfirm
                title={t('veracode.createSelectedConfirm', { count: selected.length, project: analysis?.projectKey ?? '' })}
                onConfirm={() => create(selected)}
                disabled={selected.length === 0}
              >
                <Button icon={<PlusCircleOutlined />} disabled={selected.length === 0} loading={creating}>
                  {t('veracode.createSelected', { count: selected.length })}
                </Button>
              </Popconfirm>
              <Popconfirm
                title={t('veracode.createSelectedConfirm', { count: missingCreatable.length, project: analysis?.projectKey ?? '' })}
                onConfirm={() => create(missingCreatable)}
                disabled={missingCreatable.length === 0}
              >
                <Button danger disabled={missingCreatable.length === 0} loading={creating}>
                  {t('veracode.createAllMissing', { count: missingCreatable.length })}
                </Button>
              </Popconfirm>
              <Button icon={<CloudDownloadOutlined />} onClick={exportXlsx} loading={exporting} disabled={!analysis}>
                {t('veracode.export')}
              </Button>
            </Space>
          )}
        >
          {analysis || loading ? (
            <Table<Finding>
              rowKey="id"
              size="middle"
              loading={loading}
              columns={columns}
              dataSource={rows}
              pagination={{ pageSize: 20, showSizeChanger: true }}
              // Numeric width, not 'max-content': with a fixed-right column the latter collapses the last
              // scrolling column (see the creed-env-matrix skill).
              scroll={{ x: 1650 }}
              rowSelection={{
                selectedRowKeys: selected,
                onChange: (keys) => setSelected(keys as string[]),
                getCheckboxProps: (f) => ({ disabled: !creatable(f) }),
              }}
            />
          ) : (
            <Empty description={t('veracode.empty')} />
          )}
        </ProCard>
      </Space>
    </PageContainer>
  );
}
