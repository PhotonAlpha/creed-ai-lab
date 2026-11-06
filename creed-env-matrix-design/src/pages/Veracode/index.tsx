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
  Select,
  Space,
  Statistic,
  Table,
  Tag,
  Tooltip,
  Typography,
  Upload,
} from 'antd';
import type { TableColumnsType } from 'antd';
import { CloudDownloadOutlined, ExperimentOutlined, InboxOutlined, KeyOutlined, PlusCircleOutlined, ReloadOutlined } from '@ant-design/icons';
import { jiraAuthComplete, UPLOAD_ACCEPT, veracodeApi } from '../../api/veracode';
import type {
  Analysis, CreateField, CreateFieldValues, CreateResult, Finding, FindingStatus, IssueRef, JiraAuth, JiraConnection, VeracodeSettings,
} from '../../api/veracode';
import { JiraTokenModal } from './JiraTokenModal';
import { emptyAuth, jiraTokenStore } from './jiraToken';
import { ApiError } from '../../api/client';
import { useI18n } from '../../locales';
import type { MessageKey } from '../../locales';

const { Text, Paragraph } = Typography;

type StatusFilter = 'ALL' | FindingStatus;

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

/** Lower case, every run of non-letters/digits one space — so `CVE-2021-44228`, `cve 2021 44228` and `CVE_2021_44228` read alike. */
const fuzzy = (text: string) => text.toLowerCase().replace(/[^\p{L}\p{N}]+/gu, ' ').trim();

/**
 * Fuzzy, like the backend's title match: every word of the keyword appears somewhere in the finding or in
 * its Jira issues' titles (summaries) and keys, in any order, as part of a word.
 */
const matchesKeyword = (f: Finding, keyword: string) => {
  const words = fuzzy(keyword).split(' ').filter(Boolean);
  if (words.length === 0) return true;
  const text = ` ${fuzzy([f.cve, f.title, f.affectedVersion, ...f.components,
    ...f.issues.flatMap((i) => [i.key, i.summary])].filter(Boolean).join(' '))} `;
  return words.every((w) => text.includes(w));
};

/** Filters on the Jira issues' own attributes. Empty = not filtered; a finding passes when one issue matches all. */
interface JiraFilters {
  status: string[];
  priority: string[];
  assignee: string[];
  version: string[];
}
const NO_JIRA_FILTERS: JiraFilters = { status: [], priority: [], assignee: [], version: [] };
/** The assignee option for an unassigned issue — not a name Jira can return. */
const UNASSIGNED = '\u0000unassigned';

const issueMatches = (i: IssueRef, j: JiraFilters) =>
  (j.status.length === 0 || j.status.includes(i.status ?? ''))
  && (j.priority.length === 0 || j.priority.includes(i.priority ?? ''))
  && (j.assignee.length === 0 || j.assignee.includes(i.assignee ?? UNASSIGNED))
  && (j.version.length === 0 || i.versions.some((v) => j.version.includes(v)));

const matchesJira = (f: Finding, j: JiraFilters) =>
  (j.status.length + j.priority.length + j.assignee.length + j.version.length === 0)
  || f.issues.some((i) => issueMatches(i, j));

const distinct = (values: (string | null)[]) =>
  [...new Set(values.filter((v): v is string => !!v))].sort((a, b) => a.localeCompare(b));

export function VeracodePage() {
  const { t } = useI18n();
  const { message, modal } = App.useApp();

  const [settings, setSettings] = useState<VeracodeSettings | null>(null);
  /** Overrides the report's `version` as Jira's Affects Version; blank = the report's. */
  const [versionOverride, setVersionOverride] = useState('');
  const [autoCreate, setAutoCreate] = useState(false);
  const [analysis, setAnalysis] = useState<Analysis | null>(null);
  const [loading, setLoading] = useState(false);
  const [creating, setCreating] = useState(false);
  const [exporting, setExporting] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [statusFilter, setStatusFilter] = useState<StatusFilter>('ALL');
  const [keyword, setKeyword] = useState('');
  const [jiraFilters, setJiraFilters] = useState<JiraFilters>(NO_JIRA_FILTERS);
  const [selected, setSelected] = useState<string[]>([]);

  const [jiraToken, setJiraToken] = useState(() => jiraTokenStore.load());
  /** Nothing saved: an empty sign-in of the kind the backend suggests (Cloud for `*.atlassian.net`). */
  const suggestCloud = !!settings?.cloud;
  const auth = useMemo<JiraAuth>(
    () => jiraToken.auth ?? emptyAuth(suggestCloud ? 'cloud' : 'dataCenter'),
    [jiraToken.auth, suggestCloud],
  );
  const [connection, setConnection] = useState<JiraConnection | null>(null);
  const [tokenOpen, setTokenOpen] = useState(false);
  /** The page is shared: every Jira call goes with the user's own token, the backend has none to fall back on. */
  const hasToken = jiraAuthComplete(auth);

  /**
   * What creating needs besides the finding (Component/s, required fields Jira has no default for), read with
   * the user's token: from the analysis, else from the last connection test.
   */
  const createFields = useMemo<CreateField[]>(
    () => analysis?.createFields ?? connection?.fields ?? [],
    [analysis, connection],
  );
  const [fieldValues, setFieldValues] = useState<CreateFieldValues>({});
  /** Jira's defaults preselected; a choice the user already made for the same field is kept. */
  useEffect(() => {
    setFieldValues((prev) => Object.fromEntries(createFields.map((f) => [
      f.fieldId, prev[f.fieldId]?.length ? prev[f.fieldId] : f.defaults,
    ])));
  }, [createFields]);
  const missingFields = createFields.filter((f) => f.required && !fieldValues[f.fieldId]?.length).map((f) => f.name);
  /** Refused server-side otherwise; the buttons say why first. */
  const canCreate = hasToken && missingFields.length === 0;
  const createBlocked = !hasToken
    ? t('veracode.token.needed')
    : missingFields.length > 0 ? t('veracode.fields.missing', { names: missingFields.join(', ') }) : undefined;

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
      setJiraFilters(NO_JIRA_FILTERS);
      if (auto && result.counts.created > 0) {
        message.success(t('veracode.createdCount', { count: result.counts.created }));
      }
    } catch (e) {
      if (current !== requestId.current) return;
      setAnalysis(null);
      if (tokenMissing(e)) setTokenOpen(true);
      setError(e instanceof ApiError && e.body?.error === 'upload_expired' ? t('veracode.uploadExpired') : (e as Error).message);
    } finally {
      if (current === requestId.current) setLoading(false);
    }
  }, [message, t]);

  /** Without a token nothing can be read either: the dialog opens instead of a request that would be refused. */
  const withToken = useCallback((send: () => void) => {
    if (!hasToken) {
      setTokenOpen(true);
      return;
    }
    send();
  }, [hasToken]);

  const analyze = useCallback(
    (uploadId: string, auto: boolean) =>
      withToken(() => void run(() => veracodeApi.analyze(uploadId, auth, auto, fieldValues), auto)),
    [run, auth, withToken, fieldValues],
  );

  /** A token saved earlier is tested once on load, so the toolbar can name its user. */
  useEffect(() => {
    if (!jiraToken.auth || !jiraAuthComplete(jiraToken.auth)) return;
    veracodeApi.testConnection(jiraToken.auth).then(setConnection).catch(() => setConnection(null));
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, []);

  const saveToken = (value: JiraAuth, remember: boolean, tested: JiraConnection | null) => {
    jiraTokenStore.save(value, remember);
    setJiraToken({ auth: value, remember });
    setConnection(tested);
    setTokenOpen(false);
    if (!tested) {
      veracodeApi.testConnection(value).then(setConnection).catch(() => setConnection(null));
    }
  };

  const clearToken = () => {
    jiraTokenStore.clear();
    setJiraToken({ auth: null, remember: false });
    setConnection(null);
    setTokenOpen(false);
  };

  /** The server's "no token" answer opens the dialog instead of a bare error. */
  const tokenMissing = (e: unknown) => e instanceof ApiError && e.body?.error === 'jira_token_required';

  /** Auto-create asks first — an upload trigger cannot sit inside a Popconfirm the way a button can. */
  const confirmed = (send: () => void) => {
    if (autoCreate && canCreate) {
      modal.confirm({ title: t('veracode.autoCreateConfirm', { project: settings?.projectKey ?? '' }), onOk: send });
    } else {
      send();
    }
  };
  const options = () => ({ affectedVersion: versionOverride, autoCreate: autoCreate && canCreate, fields: fieldValues });

  /**
   * `false` keeps antd's Upload from posting the file itself; the page sends it so the result lands in
   * the same request-id guard as a re-analysis.
   */
  const beforeUpload = (file: File) => {
    withToken(() => confirmed(() => void run(() => veracodeApi.analyzeUpload(file, options(), auth), options().autoCreate)));
    return false;
  };

  const analyzeSample = () =>
    withToken(() => confirmed(() => void run(() => veracodeApi.analyzeSample(options(), auth), options().autoCreate)));

  useEffect(() => {
    veracodeApi.settings().then(setSettings).catch((e: Error) => setError(e.message));
  }, []);

  /**
   * Applies per-row outcomes to the rows on screen instead of re-analysing: the answer already says
   * which issue each finding now has, and a second round trip to Jira adds nothing.
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
      const { results } = await veracodeApi.createIssues(analysis.report.id, ids, fieldValues, auth);
      applyResults(results);
    } catch (e) {
      if (tokenMissing(e)) setTokenOpen(true);
      message.error((e as Error).message);
    } finally {
      setCreating(false);
    }
  };

  const exportXlsx = async () => {
    if (!analysis) return;
    if (!hasToken) {
      setTokenOpen(true);
      return;
    }
    setExporting(true);
    try {
      const { blob, filename } = await veracodeApi.exportXlsx(analysis.report.id, auth);
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
      .filter((f) => matchesJira(f, jiraFilters))
      .filter((f) => matchesKeyword(f, keyword.trim())),
    [analysis, statusFilter, keyword, jiraFilters],
  );
  const issues = useMemo(() => (analysis?.findings ?? []).flatMap((f) => f.issues), [analysis]);
  const filterOptions = useMemo(() => ({
    status: distinct(issues.map((i) => i.status)).map((v) => ({ value: v, label: v })),
    priority: distinct(issues.map((i) => i.priority)).map((v) => ({ value: v, label: v })),
    assignee: [
      ...distinct(issues.map((i) => i.assignee)).map((v) => ({ value: v, label: v })),
      ...(issues.some((i) => !i.assignee) ? [{ value: UNASSIGNED, label: t('veracode.jira.unassigned') }] : []),
    ],
    version: distinct(issues.flatMap((i) => i.versions)).map((v) => ({ value: v, label: v })),
  }), [issues, t]);
  const jiraFilterCount = Object.values(jiraFilters).filter((v) => v.length > 0).length;
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
      width: 260,
      render: (_, f) => (
        <Space direction="vertical" size={0}>
          {f.issues.map((i) => (
            <Tooltip key={i.key} title={i.summary}>
              <Space size={4} wrap>
                <a href={i.url} target="_blank" rel="noreferrer">{i.key}</a>
                {i.status && <Text type="secondary">{i.status}</Text>}
                {i.priority && <Tag>{i.priority}</Tag>}
                <Text type="secondary">{i.assignee ?? t('veracode.jira.unassigned')}</Text>
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
      render: (_, f) => creatable(f) && (canCreate ? (
        <Popconfirm
          title={t('veracode.createOneConfirm', { project: analysis?.projectKey ?? '' })}
          onConfirm={() => create([f.id])}
        >
          <Button size="small" icon={<PlusCircleOutlined />} loading={creating}>{t('veracode.createOne')}</Button>
        </Popconfirm>
      ) : (
        <Tooltip title={createBlocked}>
          <Button size="small" icon={hasToken ? <PlusCircleOutlined /> : <KeyOutlined />} disabled={hasToken}
                  onClick={() => setTokenOpen(true)}>
            {t('veracode.createOne')}
          </Button>
        </Tooltip>
      )),
    },
  ];

  const counts = analysis?.counts;

  return (
    <PageContainer title={t('veracode.title')} subTitle={t('veracode.subtitle')}>
      <Space direction="vertical" size="middle" style={{ width: '100%' }}>
        <ProCard bordered>
          <Space wrap align="center">
            <Tooltip title={t('veracode.versionOverrideHint')}>
              <Input
                value={versionOverride}
                onChange={(e) => setVersionOverride(e.target.value)}
                placeholder={t('veracode.versionOverride')}
                style={{ width: 220 }}
                allowClear
              />
            </Tooltip>
            <Tooltip title={createBlocked}>
              <Checkbox checked={autoCreate && canCreate} disabled={!canCreate} onChange={(e) => setAutoCreate(e.target.checked)}>
                {t('veracode.autoCreate')}
              </Checkbox>
            </Tooltip>
            {analysis && (
              <Tooltip title={t('veracode.reanalyzeHint')}>
                <Button icon={<ReloadOutlined />} loading={loading} onClick={() => analyze(analysis.report.id, false)}>
                  {t('veracode.reanalyze')}
                </Button>
              </Tooltip>
            )}
            {settings?.mock && (
              <Tooltip title={t('veracode.sampleHint')}>
                <Button icon={<ExperimentOutlined />} loading={loading} onClick={analyzeSample}>
                  {t('veracode.sample')}
                </Button>
              </Tooltip>
            )}
            <Tooltip title={connection?.message ?? t('veracode.token.buttonHint')}>
              <Button icon={<KeyOutlined />} onClick={() => setTokenOpen(true)}>
                {t('veracode.token.button')}
                {!hasToken
                  ? <Tag color="default" style={{ marginInlineEnd: 0 }}>{t('veracode.token.unset')}</Tag>
                  : connection?.ok
                    ? <Tag color="success" style={{ marginInlineEnd: 0 }}>{t('veracode.token.valid')}</Tag>
                    : connection && !connection.tokenAccepted
                      ? <Tag color="error" style={{ marginInlineEnd: 0 }}>{t('veracode.token.bad')}</Tag>
                      : connection
                        // the token is fine; the issue type or the project is not (the dialog says what)
                        ? <Tag color="warning" style={{ marginInlineEnd: 0 }}>{t('veracode.token.attention')}</Tag>
                        : <Tag color="processing" style={{ marginInlineEnd: 0 }}>{t('veracode.token.set')}</Tag>}
              </Button>
            </Tooltip>
            {settings?.mock && (
              <Tooltip title={t('veracode.mockHint')}><Tag color="purple">{t('veracode.mock')}</Tag></Tooltip>
            )}
          </Space>
          {createFields.length > 0 && (
            <JiraFieldsBar fields={createFields} values={fieldValues} onChange={setFieldValues} />
          )}
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
          {analysis && (
            <Descriptions size="small" column={{ xs: 1, md: 2, xl: 4 }} style={{ marginTop: 16 }}>
              <Descriptions.Item label={t('veracode.app')}>{analysis.report.appName ?? '—'}</Descriptions.Item>
              <Descriptions.Item label={t('veracode.affectedVersion')}>
                <Space size={4}>
                  {analysis.report.affectedVersion}
                  {analysis.report.versionOverridden && (
                    <Tooltip title={t('veracode.scanVersion', { version: analysis.report.scanVersion ?? '—' })}>
                      <Tag color="warning">{t('veracode.overridden')}</Tag>
                    </Tooltip>
                  )}
                </Space>
              </Descriptions.Item>
              <Descriptions.Item label={t('veracode.policy')}>
                <Space size={4}>
                  {analysis.report.policyName ?? '—'}
                  {analysis.report.policyCompliance && (
                    <Tag color={/^pass/i.test(analysis.report.policyCompliance) ? 'success' : 'error'}>
                      {analysis.report.policyCompliance}
                    </Tag>
                  )}
                </Space>
              </Descriptions.Item>
              <Descriptions.Item label={t('veracode.file')}>
                {analysis.report.fileName}
                {analysis.report.generatedAt && <Text type="secondary">&nbsp;({analysis.report.generatedAt})</Text>}
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
                style={{ width: 280 }}
              />
              <Tooltip title={createBlocked}>
                <span>
                  <Popconfirm
                    title={t('veracode.createSelectedConfirm', { count: selected.length, project: analysis?.projectKey ?? '' })}
                    onConfirm={() => create(selected)}
                    disabled={selected.length === 0 || !canCreate}
                  >
                    <Button icon={<PlusCircleOutlined />} disabled={selected.length === 0 || !canCreate} loading={creating}>
                      {t('veracode.createSelected', { count: selected.length })}
                    </Button>
                  </Popconfirm>
                </span>
              </Tooltip>
              <Tooltip title={createBlocked}>
                <span>
                  <Popconfirm
                    title={t('veracode.createSelectedConfirm', { count: missingCreatable.length, project: analysis?.projectKey ?? '' })}
                    onConfirm={() => create(missingCreatable)}
                    disabled={missingCreatable.length === 0 || !canCreate}
                  >
                    <Button danger disabled={missingCreatable.length === 0 || !canCreate} loading={creating}>
                      {t('veracode.createAllMissing', { count: missingCreatable.length })}
                    </Button>
                  </Popconfirm>
                </span>
              </Tooltip>
              <Button icon={<CloudDownloadOutlined />} onClick={exportXlsx} loading={exporting} disabled={!analysis}>
                {t('veracode.export')}
              </Button>
            </Space>
          )}
        >
          {analysis && issues.length > 0 && (
            <Space wrap style={{ marginBottom: 16 }}>
              {(['status', 'priority', 'assignee', 'version'] as const).map((key) => (
                <Select
                  key={key}
                  mode="multiple"
                  allowClear
                  maxTagCount="responsive"
                  placeholder={t(`veracode.jira.${key}` as MessageKey)}
                  options={filterOptions[key]}
                  value={jiraFilters[key]}
                  onChange={(value: string[]) => setJiraFilters((prev) => ({ ...prev, [key]: value }))}
                  style={{ width: 200 }}
                />
              ))}
              {jiraFilterCount > 0 && (
                <Button type="link" onClick={() => setJiraFilters(NO_JIRA_FILTERS)}>
                  {t('veracode.jira.reset', { count: jiraFilterCount })}
                </Button>
              )}
              <Text type="secondary">{t('veracode.jira.filterHint')}</Text>
            </Space>
          )}
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
              scroll={{ x: 1710 }}
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
      <JiraTokenModal
        open={tokenOpen}
        auth={auth}
        saved={!!jiraToken.auth}
        remember={jiraToken.remember}
        jiraBaseUrl={settings?.jiraBaseUrl}
        mock={settings?.mock}
        connection={connection}
        onSave={saveToken}
        onClear={clearToken}
        onCancel={() => setTokenOpen(false)}
      />
    </PageContainer>
  );
}

/**
 * The create screen's fields this page fills (Component/s, required fields without a Jira default). Options come
 * from Jira through the user's token; values are option ids, or the typed text.
 */
function JiraFieldsBar({ fields, values, onChange }: {
  fields: CreateField[];
  values: CreateFieldValues;
  onChange: (values: CreateFieldValues) => void;
}) {
  const { t } = useI18n();
  const set = (fieldId: string, value: string[]) => onChange({ ...values, [fieldId]: value });
  return (
    <Space wrap align="center" style={{ marginTop: 16 }}>
      <Tooltip title={t('veracode.fields.hint')}>
        <Text strong>{t('veracode.fields.title')}</Text>
      </Tooltip>
      {fields.map((f) => {
        const value = values[f.fieldId] ?? [];
        const label = <Text>{f.required && <Text type="danger">* </Text>}{f.name}</Text>;
        const control = f.kind === 'OPTIONS' ? (
          <Select
            mode={f.multiple ? 'multiple' : undefined}
            allowClear
            showSearch
            optionFilterProp="label"
            maxTagCount="responsive"
            status={f.required && value.length === 0 ? 'warning' : undefined}
            value={f.multiple ? value : value[0]}
            onChange={(v: string | string[] | undefined) => set(f.fieldId, v == null ? [] : Array.isArray(v) ? v : [v])}
            options={f.options.map((o) => ({ value: o.id, label: o.label }))}
            placeholder={t('veracode.fields.choose', { name: f.name })}
            style={{ minWidth: 200 }}
          />
        ) : f.multiple ? (
          <Select
            mode="tags"
            value={value}
            onChange={(v: string[]) => set(f.fieldId, v)}
            status={f.required && value.length === 0 ? 'warning' : undefined}
            placeholder={t('veracode.fields.type', { name: f.name })}
            style={{ minWidth: 200 }}
          />
        ) : (
          <Input
            value={value[0] ?? ''}
            onChange={(e) => set(f.fieldId, e.target.value.trim() ? [e.target.value] : [])}
            status={f.required && value.length === 0 ? 'warning' : undefined}
            placeholder={t(f.kind === 'USER' ? 'veracode.fields.user' : 'veracode.fields.type', { name: f.name })}
            style={{ width: 200 }}
          />
        );
        return <Space key={f.fieldId} size={4}>{label}{control}</Space>;
      })}
    </Space>
  );
}
