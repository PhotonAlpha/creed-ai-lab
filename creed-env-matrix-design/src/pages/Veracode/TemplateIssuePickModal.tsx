import { useEffect, useMemo, useState } from 'react';
import { Alert, Button, Modal, Radio, Space, Table, Tag, Tooltip, Typography } from 'antd';
import type { TableColumnsType } from 'antd';
import type { Analysis, IssueRef, TemplateIssuePicks } from '../../api/veracode';
import { useI18n } from '../../locales';
import { applicationLabel } from './applicationName';

const { Text } = Typography;

/**
 * Rows of the template export that matched the same set of Jira issues - one choice for all of them (the same CVE +
 * jar in several applications is one issue in Jira, so its rows share their matches).
 */
interface PickGroup {
  id: string;
  kind: 'sca' | 'sast';
  /** Row ids (`Finding.id` / `SastIssue.id`) and what the dialog shows for each. */
  rows: { id: string; label: string }[];
  issues: IssueRef[];
}

const number = (key: string) => {
  const n = Number(key.slice(key.lastIndexOf('-') + 1));
  return Number.isFinite(n) ? n : Number.MAX_SAFE_INTEGER;
};

/**
 * The backend's default (`TemplateFields.pick`), preselected: the first issue not in a closed status, else the first -
 * by key number, the oldest (a later one is usually its duplicate).
 */
export const defaultPick = (issues: IssueRef[], closedStatuses: string[] = []) => {
  const closed = new Set(closedStatuses.map((s) => s.trim().toLowerCase()));
  const isClosed = (i: IssueRef) => !!i.status && closed.has(i.status.trim().toLowerCase());
  return [...issues].sort((a, b) => Number(isClosed(a)) - Number(isClosed(b))
    || a.key.slice(0, a.key.lastIndexOf('-')).localeCompare(b.key.slice(0, b.key.lastIndexOf('-')))
    || number(a.key) - number(b.key))[0]?.key;
};

/** The rows with more than one Jira issue, grouped by the issues they matched; none = export straight away. */
export const ambiguousGroups = (analysis: Analysis): PickGroup[] => {
  const groups = new Map<string, PickGroup>();
  const add = (kind: PickGroup['kind'], id: string, label: string, issues: IssueRef[]) => {
    if (issues.length < 2) return;
    const groupId = `${kind}|${issues.map((i) => i.key).sort().join(',')}`;
    const group = groups.get(groupId) ?? { id: groupId, kind, rows: [], issues };
    group.rows.push({ id, label });
    groups.set(groupId, group);
  };
  analysis.findings.forEach((f) => add('sca', f.id,
    [f.cveId ?? f.title, f.fileName, applicationLabel(f.application, analysis.applications)].filter(Boolean).join(' · '),
    f.issues));
  (analysis.sast?.issues ?? []).forEach((i) => add('sast', i.id, i.summary, i.issues));
  return [...groups.values()];
};

/**
 * Asks which Jira issue a template row shows when it matched several (asked for, 2026-11-16: one per row, not all of
 * them in the cell). Every group is preselected - the last choice for this upload, else {@link defaultPick} - so a
 * plain "Export" is one click; the backend applies the same default to a row matched only at export (Jira is read
 * afresh there).
 */
export function TemplateIssuePickModal({ open, analysis, previous, exporting, onCancel, onExport }: {
  open: boolean;
  analysis: Analysis;
  /** The choices made for this upload before, kept while the page is. */
  previous?: TemplateIssuePicks;
  exporting: boolean;
  onCancel: () => void;
  onExport: (picks: TemplateIssuePicks) => void;
}) {
  const { t } = useI18n();
  const groups = useMemo(() => ambiguousGroups(analysis), [analysis]);
  const defaults = useMemo(() => Object.fromEntries(groups.map((g) => {
    const before = previous?.[g.kind][g.rows[0].id];
    return [g.id, g.issues.some((i) => i.key === before) ? before! : defaultPick(g.issues, analysis.closedStatuses)];
  })), [groups, previous, analysis.closedStatuses]);
  const [chosen, setChosen] = useState<Record<string, string | undefined>>(defaults);
  useEffect(() => {
    if (open) setChosen(defaults);
  }, [open, defaults]);
  const rowCount = groups.reduce((n, g) => n + g.rows.length, 0);

  const exportChosen = () => {
    const picks: TemplateIssuePicks = { sca: { ...previous?.sca }, sast: { ...previous?.sast } };
    groups.forEach((g) => {
      const key = chosen[g.id];
      if (key) g.rows.forEach((r) => { picks[g.kind][r.id] = key; });
    });
    onExport(picks);
  };

  const columns: TableColumnsType<PickGroup> = [
    {
      title: t('veracode.tpl.pick.rows'),
      key: 'rows',
      render: (_, g) => (
        <Space direction="vertical" size={2}>
          <Tag color={g.kind === 'sca' ? 'blue' : 'purple'}>{g.kind === 'sca' ? 'SCA' : 'SAST'}</Tag>
          {g.rows.map((r) => <Text key={r.id} style={{ wordBreak: 'break-word' }}>{r.label}</Text>)}
        </Space>
      ),
    },
    {
      title: t('veracode.tpl.pick.issue'),
      key: 'issue',
      width: 360,
      render: (_, g) => (
        <Radio.Group value={chosen[g.id]} onChange={(e) => setChosen((c) => ({ ...c, [g.id]: e.target.value as string }))}>
          <Space direction="vertical" size={4}>
            {g.issues.map((i) => (
              <Radio key={i.key} value={i.key}>
                <Tooltip title={i.summary}>
                  <Space size={4} wrap>
                    <a href={i.url} target="_blank" rel="noreferrer" onClick={(e) => e.stopPropagation()}>{i.key}</a>
                    {i.status && <Text type="secondary">{i.status}</Text>}
                    <Text type="secondary">{i.assignee ?? t('veracode.jira.unassigned')}</Text>
                    {i.versions.map((v) => <Tag key={v}>{v}</Tag>)}
                  </Space>
                </Tooltip>
              </Radio>
            ))}
          </Space>
        </Radio.Group>
      ),
    },
  ];

  return (
    <Modal
      open={open}
      width={860}
      title={t('veracode.tpl.pick.title')}
      onCancel={onCancel}
      destroyOnHidden
      footer={[
        <Button key="reset" onClick={() => setChosen(Object.fromEntries(groups.map((g) => [g.id, defaultPick(g.issues, analysis.closedStatuses)])))}>
          {t('veracode.tpl.pick.reset')}
        </Button>,
        <Button key="cancel" onClick={onCancel}>{t('common.cancel')}</Button>,
        <Button key="export" type="primary" loading={exporting} onClick={exportChosen}>{t('veracode.tpl.export')}</Button>,
      ]}
    >
      <Alert type="info" showIcon style={{ marginBottom: 12 }}
             message={t('veracode.tpl.pick.hint', { rows: rowCount, groups: groups.length })} />
      <Table<PickGroup>
        rowKey="id"
        size="small"
        columns={columns}
        dataSource={groups}
        pagination={groups.length > 8 ? { pageSize: 8, showSizeChanger: false } : false}
      />
    </Modal>
  );
}
