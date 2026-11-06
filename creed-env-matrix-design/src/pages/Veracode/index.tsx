import { useCallback, useEffect, useMemo, useRef, useState } from 'react';
import { PageContainer, ProCard } from '@ant-design/pro-components';
import {
  Alert,
  App,
  Button,
  Checkbox,
  Col,
  Collapse,
  Descriptions,
  Empty,
  Input,
  Popconfirm,
  Row,
  Segmented,
  Select,
  Space,
  Statistic,
  Switch,
  Table,
  Tabs,
  Tag,
  Tooltip,
  Progress,
  Typography,
  Upload,
  theme,
} from 'antd';
import type { TableColumnsType } from 'antd';
import {
  CloudDownloadOutlined, ExperimentOutlined, InboxOutlined, KeyOutlined, PlusCircleOutlined, ReloadOutlined, TagOutlined, TagsOutlined,
} from '@ant-design/icons';
import { jiraAuthComplete, UPLOAD_ACCEPT, veracodeApi } from '../../api/veracode';
import type {
  Analysis, ConfluenceAuth, CreateField, CreateFieldValues, CreateResult, Finding, FindingStatus, IssueRef, JiraAuth,
  JiraConnection, ReportView, VeracodeSettings,
} from '../../api/veracode';
import { ConfluenceSource } from './ConfluenceSource';
import { JiraTokenModal } from './JiraTokenModal';
import { emptyAuth, jiraTokenStore } from './jiraToken';
import { pageCache } from './pageCache';
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

/**
 * A missing finding can be created, and one whose issue lacks its application's label can be labelled (same button);
 * the version comes from the page's Jira version choice, not the finding.
 */
const creatable = (f: Finding) => f.status === 'MISSING' || f.needsLabel;

/**
 * Creating and labelling look apart everywhere: create = the primary blue, add label = orange, the colour of the
 * "label missing" tag and stat card. Rows use the light `filled` variant so a column of them stays calm.
 */
const CREATE_BUTTON = { color: 'primary', icon: <PlusCircleOutlined /> } as const;
const LABEL_BUTTON = { color: 'orange', icon: <TagOutlined /> } as const;

/** The Jira issue a finding belongs to, whichever application reported it — the backend's `Finding.issueKey`. */
const issueKey = (f: Finding) => `${f.cveId ?? `Unknown|${f.cwe ?? 'Unknown'}`}@${f.fileName}`;

/** Lower case, every run of non-letters/digits one space — so `CVE-2021-44228`, `cve 2021 44228` and `CVE_2021_44228` read alike. */
const fuzzy = (text: string) => text.toLowerCase().replace(/[^\p{L}\p{N}]+/gu, ' ').trim();

/**
 * Fuzzy, like the backend's title match: every word of the keyword appears somewhere in the finding or in
 * its Jira issues' titles (summaries) and keys, in any order, as part of a word.
 */
const matchesKeyword = (f: Finding, keyword: string) => {
  const words = fuzzy(keyword).split(' ').filter(Boolean);
  if (words.length === 0) return true;
  const text = ` ${fuzzy([f.title, f.application, f.fileName, f.description, f.affectedVersion, ...f.components,
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

/** What survives a reload besides the analysis (see `pageCache`). */
interface CachedView {
  jiraVersions: string[];
  applications: string[];
  groupByApplication: boolean;
  versionOverride: string;
  fieldValues: CreateFieldValues;
  statusFilter: StatusFilter;
  keyword: string;
  jiraFilters: JiraFilters;
  pageSize: number;
  /** Where reports come from: an upload, or a Confluence page's attachments. */
  source?: 'upload' | 'confluence';
  confluencePageId?: string;
}

const DEFAULT_PAGE_SIZE = 50;

const distinct = (values: (string | null)[]) =>
  [...new Set(values.filter((v): v is string => !!v))].sort((a, b) => a.localeCompare(b));

export function VeracodePage() {
  const { t } = useI18n();
  const { message, modal } = App.useApp();
  const { token: design } = theme.useToken();

  /** Read once: the state the tab had before a reload. */
  const [cached] = useState(() => pageCache.load<CachedView>());
  const [settings, setSettings] = useState<VeracodeSettings | null>(null);
  /** Shown instead of the report's `version`; display only — Jira is searched by `jiraVersions`. */
  const [versionOverride, setVersionOverride] = useState(cached?.view.versionOverride ?? '');
  /** The Jira versions to look up in (none = any); creating needs exactly one, the version it files under. */
  const [jiraVersions, setJiraVersions] = useState<string[]>(cached?.view.jiraVersions ?? []);
  const [autoCreate, setAutoCreate] = useState(false);
  const [analysis, setAnalysis] = useState<Analysis | null>(cached?.analysis ?? null);
  /** Set when the analysis on screen came from the cache, not from Jira just now. */
  const [restoredAt, setRestoredAt] = useState<number | null>(cached?.analysis ? cached.savedAt : null);
  const [loading, setLoading] = useState(false);
  /** The upload under way (one or more files): bytes sent, then (all sent) the server parsing and asking Jira. */
  const [upload, setUpload] = useState<{ name: string; loaded: number; total: number } | null>(null);
  const [creating, setCreating] = useState(false);
  const [exporting, setExporting] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [statusFilter, setStatusFilter] = useState<StatusFilter>(cached?.view.statusFilter ?? 'ALL');
  /** Several reports, several Veracode applications: filtered (none = all) and, if asked, grouped by it. */
  const [applications, setApplications] = useState<string[]>(cached?.view.applications ?? []);
  const [groupByApplication, setGroupByApplication] = useState(cached?.view.groupByApplication ?? true);
  const [keyword, setKeyword] = useState(cached?.view.keyword ?? '');
  const [jiraFilters, setJiraFilters] = useState<JiraFilters>(cached?.view.jiraFilters ?? NO_JIRA_FILTERS);
  const [selected, setSelected] = useState<string[]>([]);
  /** Controlled: a literal `pageSize` would make the size changer a no-op. */
  const [page, setPage] = useState({ current: 1, pageSize: cached?.view.pageSize ?? DEFAULT_PAGE_SIZE });
  const [source, setSource] = useState<'upload' | 'confluence'>(cached?.view.source ?? 'upload');
  const [confluencePageId, setConfluencePageId] = useState(cached?.view.confluencePageId ?? '');

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
  const [fieldValues, setFieldValues] = useState<CreateFieldValues>(cached?.view.fieldValues ?? {});
  /** Jira's defaults preselected; a choice the user already made for the same field is kept. */
  useEffect(() => {
    setFieldValues((prev) => Object.fromEntries(createFields.map((f) => [
      f.fieldId, prev[f.fieldId]?.length ? prev[f.fieldId] : f.defaults,
    ])));
  }, [createFields]);
  const missingFields = createFields.filter((f) => f.required && !fieldValues[f.fieldId]?.length).map((f) => f.name);
  /** Refused server-side otherwise; the buttons say why first. */
  const canCreate = hasToken && missingFields.length === 0 && jiraVersions.length === 1;
  const createBlocked = !hasToken
    ? t('veracode.token.needed')
    : jiraVersions.length !== 1
      ? t('veracode.jiraVersion.chooseOne')
      : missingFields.length > 0 ? t('veracode.fields.missing', { names: missingFields.join(', ') }) : undefined;
  /** Before the first analysis the connection test names them. */
  const versionList = analysis?.jiraVersions ?? connection?.versionNames ?? [];

  /** Everything restorable, written whenever it changes. */
  useEffect(() => {
    pageCache.save<CachedView>(analysis, {
      jiraVersions, applications, groupByApplication, versionOverride, fieldValues, statusFilter, keyword, jiraFilters,
      pageSize: page.pageSize, source, confluencePageId,
    });
  }, [analysis, jiraVersions, applications, groupByApplication, versionOverride, fieldValues, statusFilter, keyword,
    jiraFilters, page.pageSize, source, confluencePageId]);

  /** Back to the first page whenever the rows change underneath it. */
  useEffect(() => {
    setPage((p) => ({ ...p, current: 1 }));
  }, [analysis, statusFilter, keyword, jiraFilters, applications]);

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
      setRestoredAt(null);
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
      if (current === requestId.current) {
        setLoading(false);
        setUpload(null);
      }
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
    (uploadId: string, auto: boolean, versions: string[] = jiraVersions) =>
      withToken(() => void run(() => veracodeApi.analyze(uploadId, auth, versions, auto, fieldValues), auto)),
    [run, auth, withToken, fieldValues, jiraVersions],
  );

  /** Another Jira version choice is another search: the analysis on screen is redone with it. */
  const chooseVersions = (versions: string[]) => {
    setJiraVersions(versions);
    if (analysis) analyze(analysis.uploadId, false, versions);
  };

  /**
   * The backend's configured default versions (`veracode.jira.defaults.affects-versions`, only those this Jira has)
   * once a connection test answers - when nothing is chosen yet: a choice of the user's, or one restored from the
   * cache, is kept. A Jira without them changes nothing.
   */
  useEffect(() => {
    const defaults = connection?.defaultVersions ?? [];
    if (defaults.length && !jiraVersions.length) chooseVersions(defaults);
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [connection]);

  /**
   * The cache held only the upload id (the analysis was too big for the storage): analyse it again once the
   * token is there. Gone from the backend (restart, TTL) -> the usual "upload expired" message.
   */
  useEffect(() => {
    if (cached?.uploadId && !cached.analysis && hasToken) analyze(cached.uploadId, false);
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, []);

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
  const options = () => ({
    affectedVersion: versionOverride, jiraVersions, autoCreate: autoCreate && canCreate, fields: fieldValues,
  });

  /**
   * `false` keeps antd's Upload from posting the files itself; the page sends them so the result lands in
   * the same request-id guard as a re-analysis. antd calls this once per file of a drop, with the whole list:
   * the last call sends them all, as one upload (several reports analysed together).
   */
  const beforeUpload = (file: File, fileList: File[]) => {
    if (file !== fileList[fileList.length - 1]) return false;
    const name = fileList.length === 1 ? file.name : t('veracode.upload.files', { count: fileList.length });
    const total = fileList.reduce((n, f) => n + f.size, 0);
    withToken(() => confirmed(() => {
      setUpload({ name, loaded: 0, total });
      void run(() => veracodeApi.analyzeUpload(fileList, options(), auth,
        (p) => setUpload({ name, ...p })), options().autoCreate);
    }));
    return false;
  };

  /** Attachments the backend downloads from Confluence, analysed as an upload of them would be. */
  const analyzeConfluence = (pageId: string, attachmentIds: string[], name: string, confluence: ConfluenceAuth) =>
    withToken(() => confirmed(() => {
      setUpload(null);
      void run(() => veracodeApi.analyzeConfluence(pageId, attachmentIds, options(), auth, confluence),
        options().autoCreate);
      message.info(t('veracode.confluence.analysing', { name }));
    }));

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
    const done = (r?: CreateResult) => r?.outcome === 'CREATED' || r?.outcome === 'ALREADY_EXISTS' || r?.outcome === 'LABELED';
    setAnalysis((prev) => {
      if (!prev) return prev;
      // an issue just created for one application is also the issue of the other applications' same finding
      const filed = new Map<string, CreateResult>();
      prev.findings.forEach((f) => {
        const r = byId.get(f.id);
        if (r?.outcome === 'CREATED') filed.set(issueKey(f), r);
      });
      const findings = prev.findings.map((f): Finding => {
        const r = byId.get(f.id);
        if (r?.outcome === 'CREATED') return { ...f, status: 'CREATED', issues: r.issues, needsLabel: false };
        if (r?.outcome === 'ALREADY_EXISTS' || r?.outcome === 'LABELED') {
          return { ...f, status: 'EXISTS', issues: r.issues, needsLabel: false };
        }
        const sibling = f.status === 'MISSING' ? filed.get(issueKey(f)) : undefined;
        return sibling ? { ...f, status: 'EXISTS', issues: sibling.issues, needsLabel: true } : f;
      });
      const count = (s: FindingStatus) => findings.filter((f) => f.status === s).length;
      return {
        ...prev,
        findings,
        counts: {
          ...prev.counts,
          exists: count('EXISTS'),
          missing: count('MISSING'),
          created: count('CREATED'),
          needsLabel: findings.filter((f) => f.needsLabel).length,
        },
      };
    });
    setSelected((prev) => prev.filter((id) => !done(byId.get(id))));

    const created = results.filter((r) => r.outcome === 'CREATED').length;
    const labelled = results.filter((r) => r.outcome === 'LABELED').length;
    const others = results.filter((r) => r.outcome !== 'CREATED' && r.outcome !== 'LABELED');
    if (created > 0) message.success(t('veracode.createdCount', { count: created }));
    if (labelled > 0) message.success(t('veracode.labelledCount', { count: labelled }));
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
      const { results } = await veracodeApi.createIssues(analysis.uploadId, ids, fieldValues, jiraVersions, auth);
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
      const { blob, filename } = await veracodeApi.exportXlsx(analysis.uploadId, auth, jiraVersions);
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
      .filter((f) => applications.length === 0 || applications.includes(f.application ?? ''))
      .filter((f) => statusFilter === 'ALL' || f.status === statusFilter)
      .filter((f) => matchesJira(f, jiraFilters))
      .filter((f) => matchesKeyword(f, keyword.trim())),
    [analysis, applications, statusFilter, keyword, jiraFilters],
  );
  /** The upload's applications, in report order. */
  const applicationList = useMemo(
    () => [...new Set((analysis?.findings ?? []).map((f) => f.application ?? ''))],
    [analysis],
  );
  const severalApplications = applicationList.length > 1;
  const issues = useMemo(() => (analysis?.findings ?? []).flatMap((f) => f.issues), [analysis]);
  const filterOptions = useMemo(() => ({
    status: distinct(issues.map((i) => i.status)).map((v) => ({ value: v, label: v })),
    priority: distinct(issues.map((i) => i.priority)).map((v) => ({ value: v, label: v })),
    assignee: [
      ...distinct(issues.map((i) => i.assignee)).map((v) => ({ value: v, label: v })),
      ...(issues.some((i) => !i.assignee) ? [{ value: UNASSIGNED, label: t('veracode.jira.unassigned') }] : []),
    ],
    // the project's versions, not the report's: an issue's Affects Version/s is one of them
    version: (analysis?.jiraVersions ?? distinct(issues.flatMap((i) => i.versions))).map((v) => ({ value: v, label: v })),
  }), [issues, analysis, t]);
  const jiraFilterCount = Object.values(jiraFilters).filter((v) => v.length > 0).length;
  /** Split, so labelling can be done in one go and is not lost among the creates. */
  const allMissing = (analysis?.findings ?? []).filter((f) => f.status === 'MISSING' && !f.needsLabel).map((f) => f.id);
  const allUnlabelled = (analysis?.findings ?? []).filter((f) => f.needsLabel).map((f) => f.id);

  const applicationColumn = severalApplications && !groupByApplication;
  const columns: TableColumnsType<Finding> = [
    {
      title: t('veracode.col.status'),
      dataIndex: 'status',
      width: 110,
      render: (s: FindingStatus, f) => (
        <Space direction="vertical" size={2}>
          <Tag color={STATUS_COLOR[s]}>{t(`veracode.status.${s}` as MessageKey)}</Tag>
          {f.needsLabel && (
            <Tooltip title={t('veracode.needsLabelHint')}><Tag color="warning">{t('veracode.needsLabel')}</Tag></Tooltip>
          )}
        </Space>
      ),
    },
    // the collapse header names the application of a group, the reports table that of a single one: a column only
    // when several applications share one ungrouped table
    ...(applicationColumn ? [{
      title: t('veracode.col.application'),
      dataIndex: 'application',
      width: 180,
      render: (a: string | null) => a ?? '—',
    }] : []),
    {
      // cve_id|cwe_id: the CVE links to NVD, the CWE to MITRE; "Unknown" for a missing part
      title: t('veracode.col.title'),
      dataIndex: 'title',
      width: 220,
      render: (_, f) => (
        <Space size={0} wrap>
          {f.cve
            ? <a href={`https://nvd.nist.gov/vuln/detail/${f.cve}`} target="_blank" rel="noreferrer">{f.cve}</a>
            : <Text type={f.cveId ? undefined : 'secondary'}>{f.cveId ?? 'Unknown'}</Text>}
          <Text type="secondary">|</Text>
          {f.cwe && /^CWE-\d+$/.test(f.cwe)
            ? <a href={`https://cwe.mitre.org/data/definitions/${f.cwe.slice(4)}.html`} target="_blank" rel="noreferrer">{f.cwe}</a>
            : <Text type="secondary">{f.cwe ?? 'Unknown'}</Text>}
        </Space>
      ),
    },
    {
      title: t('veracode.col.component'),
      dataIndex: 'fileName',
      width: 240,
      render: (file: string, f) => (
        <Space direction="vertical" size={2}>
          <Text>{file}</Text>
          <Space size={[0, 4]} wrap>{f.components.map((c) => <Tag key={c}>{c}</Tag>)}</Space>
        </Space>
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
      // the report's, for reading only: Jira is searched and filed by the Jira versions above
    },
    {
      // the whole cve_summary, but folded to 4 lines: a long one made its row a screen tall. Expanding is per
      // row and only in the browser - the Excel export and the Jira description keep the full text
      title: t('veracode.col.description'),
      dataIndex: 'description',
      width: 420,
      render: (d: string | null) => (
        <Paragraph ellipsis={{ rows: 4, expandable: 'collapsible' }} style={{ margin: 0, whiteSpace: 'pre-wrap' }}>
          {d ?? '—'}
        </Paragraph>
      ),
    },
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
      width: 130,
      fixed: 'right',
      // an existing issue of another application is labelled, not created again
      render: (_, f) => creatable(f) && (canCreate ? (
        <Popconfirm
          title={f.needsLabel
            ? t('veracode.labelOneConfirm', { key: f.issues[0]?.key ?? '' })
            : t('veracode.createOneConfirm', { project: analysis?.projectKey ?? '' })}
          onConfirm={() => create([f.id])}
        >
          <Button size="small" variant="filled" {...(f.needsLabel ? LABEL_BUTTON : CREATE_BUTTON)} loading={creating}>
            {t(f.needsLabel ? 'veracode.labelOne' : 'veracode.createOne')}
          </Button>
        </Popconfirm>
      ) : (
        <Tooltip title={createBlocked}>
          <Button size="small" variant="filled" {...(f.needsLabel ? LABEL_BUTTON : CREATE_BUTTON)}
                  {...(hasToken ? {} : { icon: <KeyOutlined /> })} disabled={hasToken}
                  onClick={() => setTokenOpen(true)}>
            {t(f.needsLabel ? 'veracode.labelOne' : 'veracode.createOne')}
          </Button>
        </Tooltip>
      )),
    },
  ];

  /**
   * The findings table. Paged by the page's state when it is the only one; in a group each table keeps its own page,
   * all with the chosen page size. Selections add up across the groups' tables.
   */
  const findingsTable = (data: Finding[], single: boolean) => {
    const ids = new Set(data.map((f) => f.id));
    return (
      <Table<Finding>
        rowKey="id"
        size="middle"
        loading={loading}
        columns={columns}
        dataSource={data}
        pagination={{
          ...(single ? { current: page.current } : {}),
          pageSize: page.pageSize,
          showSizeChanger: true,
          pageSizeOptions: [20, 50, 100, 200],
          showTotal: (total) => t('veracode.pageTotal', { total }),
          onChange: (current, pageSize) => setPage({ current: pageSize === page.pageSize ? current : 1, pageSize }),
        }}
        // Numeric width, not 'max-content': with a fixed-right column the latter collapses the last
        // scrolling column (see the creed-env-matrix skill).
        scroll={{ x: applicationColumn ? 2190 : 2010 }}
        rowSelection={{
          selectedRowKeys: selected.filter((id) => ids.has(id)),
          onChange: (keys) => setSelected((prev) => [...prev.filter((id) => !ids.has(id)), ...(keys as string[])]),
          getCheckboxProps: (f) => ({ disabled: !creatable(f) }),
        }}
      />
    );
  };

  const counts = analysis?.counts;

  return (
    <PageContainer title={t('veracode.title')} subTitle={t('veracode.subtitle')}>
      <Space direction="vertical" size="middle" style={{ width: '100%' }}>
        <ProCard bordered>
          <Space wrap align="center">
            <Tooltip title={t('veracode.jiraVersion.hint')}>
              <Select
                mode="multiple"
                allowClear
                showSearch
                // a long list stays one line: the tags collapse into "+N", the options scroll in the dropdown
                maxTagCount="responsive"
                value={jiraVersions}
                onChange={chooseVersions}
                options={versionList.map((v) => ({ value: v, label: v }))}
                placeholder={t('veracode.jiraVersion.placeholder')}
                notFoundContent={t('veracode.jiraVersion.none')}
                status={autoCreate && jiraVersions.length !== 1 ? 'warning' : undefined}
                disabled={loading}
                style={{ width: 300 }}
              />
            </Tooltip>
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
                <Button icon={<ReloadOutlined />} loading={loading} onClick={() => analyze(analysis.uploadId, false)}>
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
          {(() => {
            const dragger = (
              <Upload.Dragger
                accept={UPLOAD_ACCEPT}
                multiple
                showUploadList={false}
                beforeUpload={beforeUpload}
                disabled={loading}
              >
                <p className="ant-upload-drag-icon"><InboxOutlined /></p>
                <p className="ant-upload-text">{t('veracode.upload.title')}</p>
                <p className="ant-upload-hint">{t('veracode.upload.hint')}</p>
              </Upload.Dragger>
            );
            // no Confluence configured: the upload alone, as before
            if (!settings?.confluenceBaseUrl) return <div style={{ marginTop: 16 }}>{dragger}</div>;
            return (
              <Tabs
                style={{ marginTop: 8 }}
                activeKey={source}
                onChange={(key) => setSource(key as 'upload' | 'confluence')}
                items={[
                  { key: 'upload', label: t('veracode.source.upload'), children: dragger },
                  {
                    key: 'confluence',
                    label: t('veracode.source.confluence'),
                    children: (
                      <ConfluenceSource
                        baseUrl={settings.confluenceBaseUrl}
                        cloud={settings.confluenceCloud}
                        mock={settings.mock}
                        busy={loading}
                        pageId={confluencePageId}
                        onPageIdChange={setConfluencePageId}
                        onAnalyze={analyzeConfluence}
                      />
                    ),
                  },
                ]}
              />
            );
          })()}
          {upload && <UploadProgressBar {...upload} autoCreate={options().autoCreate} />}
          {analysis && (
            <>
              <Descriptions size="small" column={{ xs: 1, md: 2, xl: 4 }} style={{ marginTop: 16 }}>
                <Descriptions.Item label={t('veracode.project')}>{analysis.projectKey}</Descriptions.Item>
                <Descriptions.Item label={t('veracode.jiraVersions')}>
                  {/* one line however many: the full list is in the tooltip and the version dropdown */}
                  <Text ellipsis={{ tooltip: analysis.selectedVersions.join(', ') }} style={{ maxWidth: 260 }}>
                    {analysis.selectedVersions.length === 0
                      ? t('veracode.jiraVersion.any', { count: analysis.jiraVersions.length })
                      : analysis.selectedVersions.join(', ')}
                  </Text>
                </Descriptions.Item>
                <Descriptions.Item label={t('veracode.analyzedAt')}>
                  {new Date(analysis.analyzedAt).toLocaleString()}
                </Descriptions.Item>
              </Descriptions>
              {/* one line per uploaded report: its Veracode application, version and policy */}
              <Table<ReportView>
                size="small"
                rowKey="fileName"
                pagination={false}
                dataSource={analysis.reports}
                columns={[
                  { title: t('veracode.file'), dataIndex: 'fileName' },
                  { title: t('veracode.app'), dataIndex: 'appName', render: (v: string | null) => v ?? '—' },
                  {
                    title: t('veracode.affectedVersion'),
                    dataIndex: 'affectedVersion',
                    render: (v: string | null, r) => (
                      <Space size={4}>
                        {v ?? '—'}
                        {r.versionOverridden && (
                          <Tooltip title={t('veracode.scanVersion', { version: r.scanVersion ?? '—' })}>
                            <Tag color="warning">{t('veracode.overridden')}</Tag>
                          </Tooltip>
                        )}
                      </Space>
                    ),
                  },
                  {
                    title: t('veracode.policy'),
                    dataIndex: 'policyName',
                    render: (v: string | null, r) => (
                      <Space size={4}>
                        {v ?? '—'}
                        {r.policyCompliance && (
                          <Tag color={/^pass/i.test(r.policyCompliance) ? 'success' : 'error'}>{r.policyCompliance}</Tag>
                        )}
                      </Space>
                    ),
                  },
                  { title: t('veracode.generatedAt'), dataIndex: 'generatedAt', render: (v: string | null) => v ?? '—' },
                ]}
                style={{ marginTop: 8 }}
              />
            </>
          )}
        </ProCard>

        {error && <Alert type="error" showIcon message={t('veracode.failed')} description={error} />}

        {analysis && restoredAt && (
          <Alert
            type="info"
            showIcon
            closable
            onClose={() => setRestoredAt(null)}
            message={t('veracode.cache.restored', { time: new Date(restoredAt).toLocaleString() })}
            action={(
              <Space>
                <Button size="small" icon={<ReloadOutlined />} loading={loading} onClick={() => analyze(analysis.uploadId, false)}>
                  {t('veracode.reanalyze')}
                </Button>
                <Button size="small" onClick={() => { pageCache.clear(); setAnalysis(null); setRestoredAt(null); }}>
                  {t('veracode.cache.clear')}
                </Button>
              </Space>
            )}
          />
        )}

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
            {/* each count in the colour of its status tag, from the theme's tokens */}
            {([
              ['total', counts.total, design.colorInfoBg, design.colorInfoBorder, design.colorInfoText],
              ['EXISTS', counts.exists, design.colorSuccessBg, design.colorSuccessBorder, design.colorSuccessText],
              ['MISSING', counts.missing, design.colorErrorBg, design.colorErrorBorder, design.colorErrorText],
              ['CREATED', counts.created, design.colorPrimaryBg, design.colorPrimaryBorder, design.colorPrimaryText],
              ['needsLabel', counts.needsLabel, design.colorWarningBg, design.colorWarningBorder, design.colorWarningText],
            ] as const).map(([key, value, background, border, color]) => (
              <Col key={key} xs={12} md={8} xl={4} flex="auto">
                <ProCard bordered style={{ background, borderColor: border }}>
                  <Statistic
                    title={key === 'total' ? t('veracode.stat.total')
                      : key === 'needsLabel' ? t('veracode.needsLabel') : t(`veracode.status.${key}` as MessageKey)}
                    value={value}
                    valueStyle={{ color }}
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
              {severalApplications && (
                <>
                  <Select
                    mode="multiple"
                    allowClear
                    maxTagCount="responsive"
                    placeholder={t('veracode.application.filter')}
                    value={applications}
                    onChange={setApplications}
                    options={applicationList.map((a) => ({ value: a, label: a || '—' }))}
                    style={{ width: 240 }}
                  />
                  <Space size={4}>
                    <Switch size="small" checked={groupByApplication} onChange={setGroupByApplication} />
                    <Text>{t('veracode.application.group')}</Text>
                  </Space>
                </>
              )}
              <Input.Search
                allowClear
                defaultValue={keyword}
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
                    <Button variant="outlined" {...CREATE_BUTTON} disabled={selected.length === 0 || !canCreate} loading={creating}>
                      {t('veracode.createSelected', { count: selected.length })}
                    </Button>
                  </Popconfirm>
                </span>
              </Tooltip>
              <Tooltip title={createBlocked}>
                <span>
                  <Popconfirm
                    title={t('veracode.createSelectedConfirm', { count: allMissing.length, project: analysis?.projectKey ?? '' })}
                    onConfirm={() => create(allMissing)}
                    disabled={allMissing.length === 0 || !canCreate}
                  >
                    <Button variant="solid" {...CREATE_BUTTON} disabled={allMissing.length === 0 || !canCreate} loading={creating}>
                      {t('veracode.createAllMissing', { count: allMissing.length })}
                    </Button>
                  </Popconfirm>
                </span>
              </Tooltip>
              <Tooltip title={createBlocked ?? t('veracode.labelAllHint')}>
                <span>
                  <Popconfirm
                    title={t('veracode.labelAllConfirm', { count: allUnlabelled.length })}
                    onConfirm={() => create(allUnlabelled)}
                    disabled={allUnlabelled.length === 0 || !canCreate}
                  >
                    <Button variant="solid" {...LABEL_BUTTON} icon={<TagsOutlined />}
                            disabled={allUnlabelled.length === 0 || !canCreate} loading={creating}>
                      {t('veracode.labelAll', { count: allUnlabelled.length })}
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
          {analysis && severalApplications && groupByApplication ? (
            // one collapsible table per Veracode application; the selection spans them all
            <Collapse
              defaultActiveKey={applicationList}
              items={applicationList
                .filter((a) => applications.length === 0 || applications.includes(a))
                .map((a) => {
                  const own = rows.filter((f) => (f.application ?? '') === a);
                  return {
                    key: a,
                    label: (
                      <Space>
                        <Text strong>{a || '—'}</Text>
                        <Tag>{t('veracode.application.count', { count: own.length })}</Tag>
                        {own.some((f) => f.status === 'MISSING') && (
                          <Tag color="error">{own.filter((f) => f.status === 'MISSING').length} {t('veracode.status.MISSING')}</Tag>
                        )}
                      </Space>
                    ),
                    children: findingsTable(own, false),
                  };
                })}
            />
          ) : analysis || loading ? (
            findingsTable(rows, true)
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

const formatBytes = (n: number) =>
  n < 1024 ? `${n} B` : n < 1024 * 1024 ? `${(n / 1024).toFixed(1)} KB` : `${(n / 1024 / 1024).toFixed(1)} MB`;

/**
 * Two phases: the upload, measured in bytes; then the server's part (parse the XML, look every finding up in
 * Jira, create when asked), which reports nothing while it runs — shown as a running bar, not a fake percentage.
 */
function UploadProgressBar({ name, loaded, total, autoCreate }: { name: string; loaded: number; total: number; autoCreate: boolean }) {
  const { t } = useI18n();
  const sent = total > 0 && loaded >= total;
  const percent = total > 0 ? Math.min(100, Math.floor((loaded / total) * 100)) : 0;
  return (
    <div style={{ marginTop: 12 }}>
      <Typography.Text type="secondary">
        {sent
          ? t(autoCreate ? 'veracode.upload.creating' : 'veracode.upload.analysing', { name })
          : t('veracode.upload.sending', { name, loaded: formatBytes(loaded), total: formatBytes(total) })}
      </Typography.Text>
      <Progress percent={sent ? 100 : percent} status="active" showInfo={!sent} />
    </div>
  );
}
