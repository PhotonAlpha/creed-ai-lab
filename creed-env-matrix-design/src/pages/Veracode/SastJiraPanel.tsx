import { useEffect, useMemo, useState } from 'react';
import type { ReactNode } from 'react';
import { ProCard } from '@ant-design/pro-components';
import {
  Alert, App, Button, Col, Input, Popconfirm, Row, Segmented, Select, Space, Statistic, Table, Tag, Tooltip, Typography,
  theme,
} from 'antd';
import type { TableColumnsType } from 'antd';
import { KeyOutlined, PlusCircleOutlined, TagOutlined, TagsOutlined } from '@ant-design/icons';
import { veracodeApi } from '../../api/veracode';
import type {
  Analysis, CreateFieldValues, CreateResult, FindingStatus, JiraAuth, SastFlaw, SastIssue, SastJira,
} from '../../api/veracode';
import { useI18n } from '../../locales';
import type { MessageKey } from '../../locales';
import { JiraFieldsBar } from './JiraFieldsBar';
import { severityColor, severityRank } from './severity';
import { ApplicationName } from './applicationName';
import { RECREATE_BUTTON, confirmRecreate } from './recreateClosed';

const { Text } = Typography;

type StatusFilter = 'ALL' | FindingStatus;
const STATUS_COLOR: Record<FindingStatus, string> = { EXISTS: 'success', MISSING: 'error', CREATED: 'processing' };
const fileName = (path: string | null) => (path ? path.slice(path.lastIndexOf('/') + 1) : '—');
const cweUrl = (cwe: string) => `https://cwe.mitre.org/data/definitions/${cwe.replace(/^CWE-/i, '')}.html`;
/** As on the SCA tab: missing → created; filed for another application → labelled. */
const actionable = (i: SastIssue) => i.status === 'MISSING' || i.needsLabel;
const CREATE_BUTTON = { color: 'primary', icon: <PlusCircleOutlined /> } as const;
const LABEL_BUTTON = { color: 'orange', icon: <TagOutlined /> } as const;

/**
 * SAST issues in Jira: one per CWE + flaw category (open flaws only), found and created by the summary
 * `{project} - CWE-89 - {category}` in the SAST project — its own issue type, create screen and version, so this
 * tab has its own field bar and version choice. The token is the page's.
 */
export function SastJiraPanel({ analysis, sast, auth, hasToken, scaVersions, countries, onTokenNeeded, onUpdate }: {
  analysis: Analysis;
  sast: SastJira;
  auth: JiraAuth;
  hasToken: boolean;
  /** The SCA versions chosen at the top: preselected here when the SAST project has the same one. */
  scaVersions: string[];
  /** The Countries chosen at the top: SAST is looked up (and re-checked before creating) with them too. */
  countries: string[];
  onTokenNeeded: () => void;
  onUpdate: (update: (previous: Analysis) => Analysis) => void;
}) {
  const { t } = useI18n();
  const { message, modal } = App.useApp();
  const { token: design } = theme.useToken();
  const [statusFilter, setStatusFilter] = useState<StatusFilter>('ALL');
  const [keyword, setKeyword] = useState('');
  const [selected, setSelected] = useState<string[]>([]);
  const [creating, setCreating] = useState(false);
  const [version, setVersion] = useState<string | undefined>();
  const [fieldValues, setFieldValues] = useState<CreateFieldValues>({});

  /** Jira's (or the configured) defaults preselected; a choice already made for the same field is kept. */
  useEffect(() => {
    setFieldValues((prev) => Object.fromEntries(sast.createFields.map((f) => [
      f.fieldId, prev[f.fieldId]?.length ? prev[f.fieldId] : f.defaults,
    ])));
  }, [sast.createFields]);
  /** Kept while the project has it; else the configured SAST default; else the SCA choice when SAST has it too. */
  useEffect(() => {
    setVersion((prev) => (prev && sast.jiraVersions.includes(prev) ? prev
      : sast.defaultVersions?.[0] ?? scaVersions.find((v) => sast.jiraVersions.includes(v))));
  }, [sast.jiraVersions, sast.defaultVersions, scaVersions]);

  const missingFields = sast.createFields.filter((f) => f.required && !fieldValues[f.fieldId]?.length).map((f) => f.name);
  const createBlocked = !hasToken
    ? t('veracode.token.needed')
    : sast.error
      ? sast.error
      : sast.versionRequired && !version
        ? t('veracode.sastJira.versionNeeded')
        : missingFields.length > 0 ? t('veracode.fields.missing', { names: missingFields.join(', ') }) : undefined;
  const canCreate = !createBlocked;

  const rows = useMemo(() => {
    const needle = keyword.trim().toLowerCase();
    return sast.issues.filter((i) => (statusFilter === 'ALL' || i.status === statusFilter)
      && (!needle || [i.summary, i.cwe, i.cweName, i.category, ...i.applications, ...i.issues.map((r) => r.key),
        ...i.flaws.map((f) => f.sourceFile)].some((v) => v?.toLowerCase().includes(needle))));
  }, [sast.issues, statusFilter, keyword]);
  const allMissing = sast.issues.filter((i) => i.status === 'MISSING').map((i) => i.id);
  const allUnlabelled = sast.issues.filter((i) => i.needsLabel).map((i) => i.id);

  const applyResults = (results: CreateResult[]) => {
    const byId = new Map(results.map((r) => [r.findingId, r]));
    onUpdate((prev) => {
      if (!prev.sast) return prev;
      const issues = prev.sast.issues.map((i): SastIssue => {
        const r = byId.get(i.id);
        if (r?.outcome === 'CREATED') {
          return { ...i, status: 'CREATED', issues: r.issues, needsLabel: false, missingLabels: [], recreatable: false };
        }
        if (r?.outcome === 'ALREADY_EXISTS' || r?.outcome === 'LABELED') {
          return { ...i, status: 'EXISTS', issues: r.issues, needsLabel: false, missingLabels: [], recreatable: false };
        }
        return i;
      });
      const count = (s: FindingStatus) => issues.filter((i) => i.status === s).length;
      return {
        ...prev,
        sast: {
          ...prev.sast,
          issues,
          counts: {
            ...prev.sast.counts, exists: count('EXISTS'), missing: count('MISSING'), created: count('CREATED'),
            needsLabel: issues.filter((i) => i.needsLabel).length,
          },
        },
      };
    });
    setSelected((prev) => prev.filter((id) => !['CREATED', 'ALREADY_EXISTS', 'LABELED'].includes(byId.get(id)?.outcome ?? '')));
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

  const create = async (ids: string[], recreateClosed = false) => {
    if (ids.length === 0) return;
    setCreating(true);
    try {
      const { results } = await veracodeApi.createSastIssues(analysis.uploadId, ids, fieldValues,
        version ? [version] : [], auth, recreateClosed, countries);
      applyResults(results);
    } catch (e) {
      message.error((e as Error).message);
    } finally {
      setCreating(false);
    }
  };

  /** Create (blue) or add label (orange) - one request either way: the server decides per issue, re-reading Jira. */
  const createButton = (ids: string[], label: string, confirm: string,
                        { size, kind = CREATE_BUTTON, icon }: { size?: 'small'; kind?: typeof CREATE_BUTTON | typeof LABEL_BUTTON; icon?: ReactNode } = {}) => (canCreate ? (
    <Popconfirm title={confirm} onConfirm={() => create(ids)} disabled={ids.length === 0}>
      <Button size={size} variant={size ? 'filled' : 'solid'} {...kind} {...(icon ? { icon } : {})}
              loading={creating} disabled={ids.length === 0}>
        {label}
      </Button>
    </Popconfirm>
  ) : (
    <Tooltip title={createBlocked}>
      <Button size={size} variant={size ? 'filled' : 'solid'} {...kind} {...(hasToken ? {} : { icon: <KeyOutlined /> })}
              disabled={hasToken} onClick={onTokenNeeded}>
        {label}
      </Button>
    </Tooltip>
  ));

  const columns: TableColumnsType<SastIssue> = [
    {
      title: t('veracode.col.status'),
      dataIndex: 'status',
      width: 100,
      render: (s: FindingStatus | null, i) => (s ? (
        <Space direction="vertical" size={4}>
          <Tag color={STATUS_COLOR[s]}>{t(`veracode.status.${s}` as MessageKey)}</Tag>
          {i.needsLabel && (
            <Tooltip title={t('veracode.sastJira.needsLabelHint', { labels: i.missingLabels.join(', ') })}>
              <Tag color="warning">{t('veracode.needsLabel')}</Tag>
            </Tooltip>
          )}
        </Space>
      ) : '—'),
    },
    {
      // CWE + category and the open flaws in one column, per application (asked for, 2026-11-10):
      //   Creed-CCS: IDB VERACODE STSA - CRLF Injection / CWE 117 <name> / CWE 117 (Flaw IDs: 257, 258)
      // Display only: the issue is still found and filed by `summary`, shown in the tooltip
      title: <Tooltip title={t('veracode.sastJira.summaryHint')}>{t('veracode.sastJira.summary')}</Tooltip>,
      dataIndex: 'summary',
      width: 460,
      sorter: (a, b) => a.flaws.length - b.flaws.length,
      render: (summary: string, i) => (
        <Tooltip title={t('veracode.sastJira.jiraSummary', { summary })} placement="topLeft">
          <Space direction="vertical" size={8}>
            {(i.byApplication ?? []).map((b) => (
              <Space key={b.appName ?? b.name} direction="vertical" size={0}>
                <Text>
                  {b.url ? <a href={b.url} target="_blank" rel="noreferrer" title={b.appName ?? undefined}>{b.name}</a> : b.name}
                  {`: ${b.heading}`}
                </Text>
                <Text type="secondary" style={{ fontSize: 12 }}>
                  {i.cwe ? <a href={cweUrl(i.cwe)} target="_blank" rel="noreferrer">{b.cwe}</a> : b.cwe}
                </Text>
                <Text type="secondary" style={{ fontSize: 12 }}>{b.flawIds}</Text>
              </Space>
            ))}
            {/* an analysis cached before the blocks existed */}
            {!i.byApplication && <Text>{summary}</Text>}
          </Space>
        </Tooltip>
      ),
    },
    {
      title: t('veracode.col.severity'),
      dataIndex: 'severity',
      width: 110,
      defaultSortOrder: 'descend',
      sorter: (a, b) => severityRank(a.severity) - severityRank(b.severity),
      render: (s: string | null) => (s ? <Tag color={severityColor(s)}>{s}</Tag> : '—'),
    },
    {
      title: t('veracode.col.application'),
      dataIndex: 'applications',
      width: 220,
      render: (apps: string[]) => (
        <Space direction="vertical" size={0}>
          {apps.map((a) => <ApplicationName key={a} app={a} apps={analysis.applications} style={{ fontSize: 12 }} />)}
        </Space>
      ),
    },
    {
      title: t('veracode.col.jira'),
      key: 'jira',
      width: 200,
      render: (_, i) => (i.issues.length === 0 ? '—' : (
        <Space direction="vertical" size={0}>
          {i.issues.map((r) => (
            <Space key={r.key} size={4}>
              <a href={r.url} target="_blank" rel="noreferrer">{r.key}</a>
              {r.status && <Tag style={{ marginInlineEnd: 0 }}>{r.status}</Tag>}
            </Space>
          ))}
        </Space>
      )),
    },
    {
      title: t('veracode.col.actions'),
      key: 'actions',
      width: 130,
      fixed: 'right',
      // an existing issue of another application is labelled, not created again
      render: (_, i) => (i.needsLabel
        ? createButton([i.id], t('veracode.labelOne'),
          t('veracode.sastJira.labelOneConfirm', { key: i.issues[0]?.key ?? '', labels: i.missingLabels.join(', ') }),
          { size: 'small', kind: LABEL_BUTTON })
        : i.status === 'MISSING' ? createButton([i.id], t('veracode.createOne'),
          t('veracode.sastJira.createOneConfirm', { project: sast.projectKey, type: sast.issueType }), { size: 'small' })
          : i.recreatable && (
            <Tooltip title={canCreate ? t('veracode.recreate.hint') : createBlocked}>
              <Button size="small" variant="filled" {...RECREATE_BUTTON} loading={creating} disabled={!canCreate}
                      onClick={() => confirmRecreate(modal, t, i.issues, sast.projectKey, () => create([i.id], true))}>
                {t('veracode.recreate.button')}
              </Button>
            </Tooltip>
          )),
    },
  ];

  /** The issue's open flaws: where in the code, in which application. */
  const flawRows = (i: SastIssue) => (
    <Table<SastFlaw>
      size="small"
      pagination={false}
      rowKey={(f) => `${f.application ?? ''}|${f.issueId ?? ''}|${f.sourceFile ?? ''}|${f.line ?? ''}`}
      dataSource={i.flaws}
      columns={[
        { title: t('veracode.col.application'), dataIndex: 'application', render: (v: string | null) => <ApplicationName app={v} apps={analysis.applications} /> },
        { title: t('veracode.col.severity'), dataIndex: 'severity', render: (s: string | null) => (s ? <Tag color={severityColor(s)}>{s}</Tag> : '—') },
        {
          title: t('veracode.sast.location'),
          key: 'location',
          render: (_, f) => (
            <Tooltip title={f.sourceFile}><Text code>{fileName(f.sourceFile)}{f.line != null && `:${f.line}`}</Text></Tooltip>
          ),
        },
        { title: 'Module', dataIndex: 'module', render: (v: string | null) => v ?? '—' },
        { title: t('veracode.sast.issueId'), dataIndex: 'issueId', render: (v: string | null) => v ?? '—' },
        { title: t('veracode.sast.firstFound'), dataIndex: 'firstFound', render: (v: string | null) => v ?? '—' },
      ]}
    />
  );

  const counts = sast.counts;
  return (
    <Space direction="vertical" size="middle" style={{ width: '100%' }}>
      {sast.error && (
        <Alert type="error" showIcon message={t('veracode.sastJira.error', { project: sast.projectKey, type: sast.issueType })}
               description={sast.error} />
      )}
      <ProCard bordered>
        <Space wrap align="center">
          <Tooltip title={t('veracode.sastJira.targetHint')}>
            <Text strong>{t('veracode.sastJira.target')}</Text>
          </Tooltip>
          <Tag color="blue">{sast.projectKey}</Tag>
          <Tag>{sast.issueType}</Tag>
          {sast.affectsVersion && (
            <Select
              allowClear
              showSearch
              value={version}
              onChange={setVersion}
              status={sast.versionRequired && !version ? 'warning' : undefined}
              options={sast.jiraVersions.map((v) => ({ value: v, label: v }))}
              placeholder={t(sast.versionRequired ? 'veracode.sastJira.versionRequired' : 'veracode.sastJira.versionOptional')}
              style={{ minWidth: 220 }}
            />
          )}
        </Space>
        {sast.createFields.length > 0 && (
          <JiraFieldsBar fields={sast.createFields} values={fieldValues} onChange={setFieldValues} />
        )}
      </ProCard>

      <Row gutter={16}>
        {([
          ['total', counts.total, design.colorInfoBg, design.colorInfoBorder, design.colorInfoText],
          ['EXISTS', counts.exists, design.colorSuccessBg, design.colorSuccessBorder, design.colorSuccessText],
          ['MISSING', counts.missing, design.colorErrorBg, design.colorErrorBorder, design.colorErrorText],
          ['CREATED', counts.created, design.colorPrimaryBg, design.colorPrimaryBorder, design.colorPrimaryText],
          ['needsLabel', counts.needsLabel, design.colorWarningBg, design.colorWarningBorder, design.colorWarningText],
        ] as const).map(([key, value, background, border, color]) => (
          <Col key={key} xs={12} md={6} flex="auto">
            <ProCard bordered style={{ background, borderColor: border }}>
              <Statistic
                title={key === 'total' ? t('veracode.sastJira.total')
                  : key === 'needsLabel' ? t('veracode.needsLabel') : t(`veracode.status.${key}` as MessageKey)}
                value={value}
                valueStyle={{ color }}
              />
            </ProCard>
          </Col>
        ))}
      </Row>

      <ProCard bordered title={t('veracode.sastJira.title')}>
        <Space wrap style={{ marginBottom: 12 }}>
          <Segmented<StatusFilter>
            value={statusFilter}
            onChange={setStatusFilter}
            options={(['ALL', 'EXISTS', 'MISSING', 'CREATED'] as const).map((s) => ({
              value: s,
              label: s === 'ALL' ? t('veracode.filter.all') : t(`veracode.status.${s}` as MessageKey),
            }))}
          />
          {createButton(selected, t('veracode.createSelected', { count: selected.length }),
            t('veracode.sastJira.createConfirm', { count: selected.length, project: sast.projectKey, type: sast.issueType }))}
          {createButton(allMissing, t('veracode.createAllMissing', { count: allMissing.length }),
            t('veracode.sastJira.createConfirm', { count: allMissing.length, project: sast.projectKey, type: sast.issueType }))}
          <Tooltip title={canCreate ? t('veracode.labelAllHint') : undefined}>
            <span>
              {createButton(allUnlabelled, t('veracode.labelAll', { count: allUnlabelled.length }),
                t('veracode.sastJira.labelAllConfirm', { count: allUnlabelled.length }),
                { kind: LABEL_BUTTON, icon: <TagsOutlined /> })}
            </span>
          </Tooltip>
          <Input.Search
            allowClear
            style={{ width: 280 }}
            placeholder={t('veracode.sastJira.search')}
            onSearch={setKeyword}
            onChange={(e) => !e.target.value && setKeyword('')}
          />
        </Space>
        <Table<SastIssue>
          rowKey="id"
          size="middle"
          columns={columns}
          dataSource={rows}
          expandable={{ expandedRowRender: flawRows }}
          // numeric, not 'max-content': a fixed-right column needs it (see the creed-env-matrix skill)
          scroll={{ x: 1300 }}
          pagination={{ defaultPageSize: 50, showSizeChanger: true, pageSizeOptions: [20, 50, 100, 200] }}
          rowSelection={{
            selectedRowKeys: selected,
            onChange: (keys) => setSelected(keys as string[]),
            getCheckboxProps: (i) => ({ disabled: !actionable(i) }),
          }}
        />
      </ProCard>
    </Space>
  );
}
