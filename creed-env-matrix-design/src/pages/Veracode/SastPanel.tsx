import { useMemo, useState } from 'react';
import { ProCard } from '@ant-design/pro-components';
import { Input, Segmented, Select, Space, Table, Tag, Tooltip, Typography } from 'antd';
import type { TableColumnsType } from 'antd';
import type { ReportView, SastFlaw, SastSummary } from '../../api/veracode';
import { useI18n } from '../../locales';
import { severityColor, severityRank } from './severity';

const { Text, Paragraph } = Typography;

type SastStatus = 'OPEN' | 'ALL' | 'MITIGATED' | 'FIXED';

const statusOf = (f: SastFlaw): Exclude<SastStatus, 'ALL'> => (f.fixed ? 'FIXED' : f.mitigated ? 'MITIGATED' : 'OPEN');
const STATUS_COLOR: Record<Exclude<SastStatus, 'ALL'>, string> = { OPEN: 'error', MITIGATED: 'default', FIXED: 'success' };
const fileName = (path: string | null) => (path ? path.slice(path.lastIndexOf('/') + 1) : '—');
const cweUrl = (cwe: string) => `https://cwe.mitre.org/data/definitions/${cwe.replace(/^CWE-/i, '')}.html`;

/**
 * The reports' static-analysis statistics, one row per report and a total when there are several — the workbook's
 * "SAST summary" sheet. The severity columns count open flaws; a count the report does not state is "—".
 */
export function SastSummaryTable({ reports }: { reports: ReportView[] }) {
  const { t } = useI18n();
  const rows = reports.filter((r): r is ReportView & { sast: SastSummary } => r.sast != null && r.sast.flaws > 0);
  if (rows.length === 0) return null;
  const levels = rows[0].sast.bySeverity.map((l) => l.severity);
  const stated = (n: number | null) => n ?? '—';
  const sum = (pick: (s: SastSummary) => number | null) => rows.reduce((acc, r) => acc + (pick(r.sast) ?? 0), 0);

  const columns: TableColumnsType<ReportView & { sast: SastSummary }> = [
    {
      title: t('veracode.sca.report'),
      key: 'report',
      fixed: 'left',
      render: (_, r) => <Text ellipsis={{ tooltip: r.fileName }} style={{ maxWidth: 200 }}>{r.appName ?? r.fileName}</Text>,
    },
    {
      title: t('veracode.sast.rating'),
      key: 'rating',
      render: (_, r) => r.sast.rating ? <Tag style={{ marginInlineEnd: 0 }}>{r.sast.rating}</Tag> : '—',
    },
    { title: t('veracode.sast.score'), key: 'score', align: 'right', render: (_, r) => stated(r.sast.score) },
    {
      title: <Tooltip title={t('veracode.sast.statedHint')}>{t('veracode.sast.totalFlaws')}</Tooltip>,
      key: 'total',
      align: 'right',
      render: (_, r) => stated(r.sast.totalFlaws),
    },
    {
      title: <Tooltip title={t('veracode.sast.statedHint')}>{t('veracode.sast.notMitigated')}</Tooltip>,
      key: 'notMitigated',
      align: 'right',
      render: (_, r) => stated(r.sast.flawsNotMitigated),
    },
    { title: t('veracode.sast.listed'), key: 'listed', align: 'right', render: (_, r) => r.sast.flaws },
    { title: t('veracode.sast.open'), key: 'open', align: 'right', render: (_, r) => <Text strong>{r.sast.open}</Text> },
    { title: t('veracode.sast.mitigated'), key: 'mitigated', align: 'right', render: (_, r) => r.sast.mitigated },
    { title: t('veracode.sast.fixed'), key: 'fixed', align: 'right', render: (_, r) => r.sast.fixed },
    { title: t('veracode.sast.affectsPolicy'), key: 'policy', align: 'right', render: (_, r) => r.sast.affectsPolicy },
    ...levels.map((severity, i) => ({
      title: <Tag color={severityColor(severity)} style={{ marginInlineEnd: 0 }}>{severity}</Tag>,
      key: `level-${severity}`,
      align: 'right' as const,
      render: (_: unknown, r: ReportView & { sast: SastSummary }) => {
        const n = r.sast.bySeverity[i]?.count ?? 0;
        return n > 0 ? <Tag color={severityColor(severity)} style={{ marginInlineEnd: 0 }}>{n}</Tag> : 0;
      },
    })),
    { title: t('veracode.sca.unknown'), key: 'unknown', align: 'right', render: (_, r) => r.sast.unknownSeverity },
  ];

  return (
    <>
      <Typography.Title level={5} style={{ marginTop: 16 }}>
        <Tooltip title={t('veracode.sast.levelsHint')}>{t('veracode.sast.title')}</Tooltip>
      </Typography.Title>
      <Table<ReportView & { sast: SastSummary }>
        size="small"
        rowKey="fileName"
        pagination={false}
        dataSource={rows}
        columns={columns}
        scroll={{ x: 'max-content' }}
        summary={() => rows.length < 2 ? null : (
          <Table.Summary.Row>
            <Table.Summary.Cell index={0}><Text strong>{t('veracode.sca.total')}</Text></Table.Summary.Cell>
            {/* rating and score are per scan: no sum */}
            <Table.Summary.Cell index={1} />
            <Table.Summary.Cell index={2} />
            <Table.Summary.Cell index={3} align="right">{sum((s) => s.totalFlaws)}</Table.Summary.Cell>
            <Table.Summary.Cell index={4} align="right">{sum((s) => s.flawsNotMitigated)}</Table.Summary.Cell>
            <Table.Summary.Cell index={5} align="right">{sum((s) => s.flaws)}</Table.Summary.Cell>
            <Table.Summary.Cell index={6} align="right"><Text strong>{sum((s) => s.open)}</Text></Table.Summary.Cell>
            <Table.Summary.Cell index={7} align="right">{sum((s) => s.mitigated)}</Table.Summary.Cell>
            <Table.Summary.Cell index={8} align="right">{sum((s) => s.fixed)}</Table.Summary.Cell>
            <Table.Summary.Cell index={9} align="right">{sum((s) => s.affectsPolicy)}</Table.Summary.Cell>
            {levels.map((_, i) => (
              <Table.Summary.Cell key={i} index={10 + i} align="right">{sum((s) => s.bySeverity[i]?.count ?? 0)}</Table.Summary.Cell>
            ))}
            <Table.Summary.Cell index={10 + levels.length} align="right">{sum((s) => s.unknownSeverity)}</Table.Summary.Cell>
          </Table.Summary.Row>
        )}
      />
    </>
  );
}

/**
 * Every report's static flaws: open ones by default, filterable by status, severity and application, searchable.
 * Every flaw, fixed and mitigated ones included; the Jira issues (open flaws by CWE + category) are the table above.
 */
export function SastPanel({ flaws, multipleApplications }: { flaws: SastFlaw[]; multipleApplications: boolean }) {
  const { t } = useI18n();
  const [status, setStatus] = useState<SastStatus>('OPEN');
  const [severities, setSeverities] = useState<string[]>([]);
  const [applications, setApplications] = useState<string[]>([]);
  const [search, setSearch] = useState('');
  const [pageSize, setPageSize] = useState(50);

  const count = (s: SastStatus) => (s === 'ALL' ? flaws.length : flaws.filter((f) => statusOf(f) === s).length);
  const severityOptions = useMemo(
    () => [...new Set(flaws.map((f) => f.severity ?? ''))]
      .filter(Boolean)
      .sort((a, b) => severityRank(b) - severityRank(a))
      .map((s) => ({ value: s, label: s })),
    [flaws],
  );
  const applicationOptions = useMemo(
    () => [...new Set(flaws.map((f) => f.application ?? ''))].filter(Boolean).sort().map((a) => ({ value: a, label: a })),
    [flaws],
  );
  const rows = useMemo(() => {
    const needle = search.trim().toLowerCase();
    return flaws.filter((f) =>
      (status === 'ALL' || statusOf(f) === status)
      && (severities.length === 0 || severities.includes(f.severity ?? ''))
      && (applications.length === 0 || applications.includes(f.application ?? ''))
      && (!needle || [f.issueId, f.cwe, f.cweName, f.category, f.sourceFile, f.module, f.description]
        .some((v) => v?.toLowerCase().includes(needle))));
  }, [flaws, status, severities, applications, search]);

  const columns: TableColumnsType<SastFlaw> = [
    ...(multipleApplications ? [{
      title: t('veracode.col.application'),
      dataIndex: 'application',
      key: 'application',
      render: (v: string | null) => v ?? '—',
    }] : []),
    {
      title: t('veracode.col.severity'),
      dataIndex: 'severity',
      key: 'severity',
      defaultSortOrder: 'descend',
      sorter: (a, b) => severityRank(a.severity) - severityRank(b.severity),
      render: (s: string | null) => (s ? <Tag color={severityColor(s)}>{s}</Tag> : '—'),
    },
    { title: t('veracode.sast.issueId'), dataIndex: 'issueId', key: 'issueId', render: (v: string | null) => v ?? '—' },
    {
      title: 'CWE',
      key: 'cwe',
      width: 260,
      render: (_, f) => (
        <Space direction="vertical" size={0}>
          {f.cwe ? <a href={cweUrl(f.cwe)} target="_blank" rel="noreferrer">{f.cwe}</a> : '—'}
          <Text type="secondary" ellipsis={{ tooltip: f.cweName ?? f.category }} style={{ maxWidth: 240, fontSize: 12 }}>
            {f.cweName ?? f.category}
          </Text>
        </Space>
      ),
    },
    {
      title: t('veracode.sast.location'),
      key: 'location',
      sorter: (a, b) => (a.sourceFile ?? '').localeCompare(b.sourceFile ?? '') || (a.line ?? 0) - (b.line ?? 0),
      render: (_, f) => (
        <Space direction="vertical" size={0}>
          <Tooltip title={f.sourceFile}>
            <Text code>{fileName(f.sourceFile)}{f.line != null && `:${f.line}`}</Text>
          </Tooltip>
          {f.module && <Text type="secondary" style={{ fontSize: 12 }}>{f.module}</Text>}
        </Space>
      ),
    },
    {
      title: t('veracode.col.status'),
      key: 'status',
      render: (_, f) => (
        <Space size={[4, 4]} wrap>
          <Tooltip title={[f.remediationStatus, f.mitigationStatus].filter(Boolean).join(' · ')}>
            <Tag color={STATUS_COLOR[statusOf(f)]}>{t(`veracode.sast.status.${statusOf(f)}`)}</Tag>
          </Tooltip>
          {f.affectsPolicy && !f.fixed && !f.mitigated && <Tag color="warning">{t('veracode.sast.policyTag')}</Tag>}
        </Space>
      ),
    },
    { title: t('veracode.sast.firstFound'), dataIndex: 'firstFound', key: 'firstFound', render: (v: string | null) => v ?? '—' },
    {
      title: t('veracode.col.description'),
      dataIndex: 'description',
      key: 'description',
      width: 420,
      render: (v: string | null) => (v ? (
        <Paragraph ellipsis={{ rows: 2, expandable: 'collapsible' }} style={{ margin: 0, whiteSpace: 'pre-wrap' }}>
          {v}
        </Paragraph>
      ) : '—'),
    },
  ];

  return (
    <ProCard
      bordered
      title={(
        <Space>
          {t('veracode.sast.flaws')}
          <Tooltip title={t('veracode.sast.noJira')}><Tag>{t('veracode.sast.displayOnly')}</Tag></Tooltip>
        </Space>
      )}
    >
      {/* in the body, not `extra`: next to five filters the title was squeezed onto two lines */}
      <Space wrap style={{ marginBottom: 12 }}>
        <Segmented<SastStatus>
          value={status}
          onChange={setStatus}
          options={(['OPEN', 'ALL', 'MITIGATED', 'FIXED'] as const).map((s) => ({
            value: s,
            label: `${t(s === 'ALL' ? 'veracode.sast.status.ALL' : `veracode.sast.status.${s}`)} (${count(s)})`,
          }))}
        />
        <Select
          mode="multiple"
          allowClear
          maxTagCount="responsive"
          style={{ minWidth: 180 }}
          placeholder={t('veracode.sast.severityFilter')}
          options={severityOptions}
          value={severities}
          onChange={setSeverities}
        />
        {multipleApplications && (
          <Select
            mode="multiple"
            allowClear
            maxTagCount="responsive"
            style={{ minWidth: 220 }}
            placeholder={t('veracode.application.filter')}
            options={applicationOptions}
            value={applications}
            onChange={setApplications}
          />
        )}
        <Input.Search
          allowClear
          style={{ width: 260 }}
          placeholder={t('veracode.sast.search')}
          onSearch={setSearch}
          onChange={(e) => !e.target.value && setSearch('')}
        />
      </Space>
      <Table<SastFlaw>
        size="small"
        rowKey={(f) => `${f.application ?? ''}|${f.issueId ?? ''}|${f.sourceFile ?? ''}|${f.line ?? ''}`}
        dataSource={rows}
        columns={columns}
        scroll={{ x: 'max-content' }}
        pagination={{
          pageSize,
          showSizeChanger: true,
          pageSizeOptions: [20, 50, 100, 200],
          onShowSizeChange: (_, size) => setPageSize(size),
          showTotal: (total) => t('veracode.sast.total', { count: total }),
        }}
      />
    </ProCard>
  );
}
