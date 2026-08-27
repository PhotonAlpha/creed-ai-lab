import { useCallback, useEffect, useMemo, useState } from 'react';
import { ProCard, ProTable } from '@ant-design/pro-components';
import type { ProColumns } from '@ant-design/pro-components';
import { Alert, App, Button, Popconfirm, Select, Space, Tag, Typography } from 'antd';
import {
  DeleteOutlined,
  EditOutlined,
  PlusOutlined,
  ReloadOutlined,
  SaveOutlined,
  UndoOutlined,
} from '@ant-design/icons';
import { envMatrixApi } from '../../api/envMatrix';
import { useI18n } from '../../locales';
import type { MessageKey } from '../../locales';
import type { AppLink, AppLinkRequest, BatchSaveIssue, Dimensions, LinkDirection } from '../../api/types';
import { LinkFormModal } from './LinkFormModal';
import type { LinkFormValues } from './LinkFormModal';

/** A link plus the local edit bookkeeping, mirroring `ConfigRow` for endpoints. */
interface LinkRow {
  _key: string;
  _new: boolean;
  _deleted: boolean;
  _dirty: boolean;
  id: number | null;
  tier: string;
  sourceApp: string;
  targetApp: string;
  direction: LinkDirection;
  note: string | null;
}

let newRowSequence = 0;

const toRow = (link: AppLink): LinkRow => ({
  _key: `db-${link.id}`,
  _new: false,
  _deleted: false,
  _dirty: false,
  id: link.id,
  tier: link.tier,
  sourceApp: link.sourceApp,
  targetApp: link.targetApp,
  direction: link.direction,
  note: link.note,
});

const toRequest = (row: LinkRow): AppLinkRequest => ({
  ...(row.id != null ? { id: row.id } : {}),
  tier: row.tier,
  sourceApp: row.sourceApp,
  targetApp: row.targetApp,
  direction: row.direction,
  note: row.note,
});

interface LinksPanelProps {
  dimensions: Dimensions;
}

/**
 * The topology link editor — the app-system wiring the graph draws its arrows from.
 *
 * Edits **one tier at a time**, which is the whole reason this table is easier to work with than the
 * endpoint one: the save is authoritative for the named tier only, so the page never has to hold
 * every row in the database just to be allowed to delete one.
 */
export function LinksPanel({ dimensions }: LinksPanelProps) {
  const { t } = useI18n();
  const { message } = App.useApp();

  const [tier, setTier] = useState<string>();
  const [rows, setRows] = useState<LinkRow[]>([]);
  const [loading, setLoading] = useState(false);
  const [saving, setSaving] = useState(false);
  const [issues, setIssues] = useState<BatchSaveIssue[]>([]);
  /** `null` = closed, `{}` = adding, `{row}` = editing. One page-level dialog, never one per row. */
  const [editing, setEditing] = useState<{ row?: LinkRow } | null>(null);

  useEffect(() => {
    if (!tier && dimensions.tier.length > 0) setTier(dimensions.tier[0]);
  }, [dimensions.tier, tier]);

  const reload = useCallback(async () => {
    if (!tier) return;
    setLoading(true);
    try {
      setRows((await envMatrixApi.links(tier)).map(toRow));
      setIssues([]);
    } catch (e) {
      message.error((e as Error).message);
    } finally {
      setLoading(false);
    }
  }, [tier, message]);

  useEffect(() => {
    void reload();
  }, [reload]);

  const dirtyCount = rows.filter((row) => row._dirty || row._new || row._deleted).length;

  const upsert = (values: LinkFormValues, existing?: LinkRow) => {
    // Both directions of a pair would render as two arrows between the same two boxes, which reads
    // as a contradiction rather than as two facts. BIDIRECTIONAL is how you say "both ways".
    const clash = rows.find(
      (row) =>
        !row._deleted &&
        row._key !== existing?._key &&
        ((row.sourceApp === values.sourceApp && row.targetApp === values.targetApp) ||
          (row.sourceApp === values.targetApp && row.targetApp === values.sourceApp)),
    );
    if (clash) {
      message.error(t('links.duplicate', { source: clash.sourceApp, target: clash.targetApp }));
      return;
    }

    if (existing) {
      setRows((prev) =>
        prev.map((row) =>
          row._key === existing._key ? { ...row, ...values, _dirty: !row._new } : row,
        ),
      );
    } else {
      newRowSequence += 1;
      setRows((prev) => [
        {
          _key: `new-${newRowSequence}`,
          _new: true,
          _deleted: false,
          _dirty: false,
          id: null,
          tier: tier as string,
          note: null,
          ...values,
        },
        ...prev,
      ]);
    }
    setEditing(null);
  };

  const toggleDelete = (key: string) => {
    setRows((prev) =>
      prev.flatMap((row) => {
        if (row._key !== key) return [row];
        // A row that only ever existed in the browser is dropped outright — marking it for deletion
        // would show a struck-through row that the save has nothing to delete.
        if (row._new) return [];
        return [{ ...row, _deleted: !row._deleted }];
      }),
    );
  };

  const save = async () => {
    if (!tier) return;
    setSaving(true);
    try {
      const payload = rows.filter((row) => !row._deleted).map(toRequest);
      const result = await envMatrixApi.batchSaveLinks(tier, payload);
      if (!result.success) {
        setIssues(result.issues);
        message.error(t('config.rejected', { count: result.issues.length }));
        return;
      }
      setIssues([]);
      message.success(
        `${t('config.saved')} — ${t('config.savedDetail', {
          inserted: result.inserted,
          updated: result.updated,
          deleted: result.deleted,
        })}`,
      );
      await reload();
    } catch (e) {
      message.error((e as Error).message);
    } finally {
      setSaving(false);
    }
  };

  const issuesByIndex = useMemo(() => new Map(issues.map((issue) => [issue.index, issue])), [issues]);
  const visible = useMemo(() => rows.filter((row) => !row._deleted), [rows]);

  const columns: ProColumns<LinkRow>[] = [
    {
      title: t('links.sourceApp'),
      dataIndex: 'sourceApp',
      width: 180,
      render: (_, row) => <Typography.Text strong>{row.sourceApp}</Typography.Text>,
    },
    {
      title: t('links.direction'),
      dataIndex: 'direction',
      width: 120,
      render: (_, row) => (
        <Tag bordered={false} color={row.direction === 'BIDIRECTIONAL' ? 'blue' : 'default'}>
          {row.direction === 'BIDIRECTIONAL' ? '↔' : '→'}{' '}
          {t(`links.direction.${row.direction}` as MessageKey)}
        </Tag>
      ),
    },
    {
      title: t('links.targetApp'),
      dataIndex: 'targetApp',
      width: 180,
      render: (_, row) => <Typography.Text strong>{row.targetApp}</Typography.Text>,
    },
    { title: t('links.note'), dataIndex: 'note', ellipsis: true },
    {
      title: t('column.actions'),
      valueType: 'option',
      width: 140,
      fixed: 'right',
      render: (_, row) => [
        <Button key="edit" type="link" size="small" icon={<EditOutlined />} onClick={() => setEditing({ row })}>
          {t('common.edit')}
        </Button>,
        <Popconfirm
          key="delete"
          title={t('links.deleteConfirm')}
          okText={t('common.yes')}
          cancelText={t('common.no')}
          onConfirm={() => toggleDelete(row._key)}
        >
          <Button type="link" size="small" danger icon={<DeleteOutlined />} />
        </Popconfirm>,
      ],
    },
  ];

  const deletedRows = rows.filter((row) => row._deleted);

  return (
    <Space direction="vertical" size="middle" style={{ width: '100%' }}>
      <Alert type="info" showIcon message={t('links.saveHint')} description={t('links.appHint')} />

      {issues.length > 0 && (
        <Alert
          type="error"
          showIcon
          message={t('config.rejected', { count: issues.length })}
          description={
            <ul style={{ margin: 0, paddingInlineStart: 18 }}>
              {issues.map((issue) => (
                <li key={`${issue.index}-${issue.field}`}>
                  {t('config.rowLabel', { row: issue.index + 1 })} · {issue.field}: {issue.message}
                </li>
              ))}
            </ul>
          }
        />
      )}

      <ProCard size="small" bordered>
        <Space size="middle" wrap>
          <Typography.Text type="secondary">{t('links.tier')} *</Typography.Text>
          <Select
            style={{ width: 160 }}
            value={tier}
            onChange={setTier}
            options={dimensions.tier.map((option) => ({ label: option, value: option }))}
          />
          {deletedRows.length > 0 && (
            <Space size={4} wrap>
              <Typography.Text type="warning">{t('config.pendingDelete')}:</Typography.Text>
              {deletedRows.map((row) => (
                <Tag
                  key={row._key}
                  bordered={false}
                  icon={<UndoOutlined />}
                  style={{ cursor: 'pointer' }}
                  onClick={() => toggleDelete(row._key)}
                >
                  {row.sourceApp} → {row.targetApp}
                </Tag>
              ))}
            </Space>
          )}
        </Space>
      </ProCard>

      <ProTable<LinkRow>
        rowKey="_key"
        size="small"
        search={false}
        options={false}
        loading={loading}
        dataSource={visible}
        columns={columns}
        pagination={false}
        locale={{ emptyText: t('links.empty') }}
        rowClassName={(row, index) =>
          [
            row._dirty || row._new ? 'env-matrix-row--dirty' : '',
            issuesByIndex.has(index) ? 'env-matrix-row--dirty' : '',
          ]
            .filter(Boolean)
            .join(' ')
        }
        headerTitle={
          <Space size="small">
            <Typography.Text strong>{t('links.title')}</Typography.Text>
            <Typography.Text type={dirtyCount ? 'warning' : 'secondary'}>
              {dirtyCount ? t('config.dirty', { count: dirtyCount }) : t('config.clean')}
            </Typography.Text>
          </Space>
        }
        toolBarRender={() => [
          <Button key="add" icon={<PlusOutlined />} disabled={!tier} onClick={() => setEditing({})}>
            {t('links.add')}
          </Button>,
          <Button key="reload" icon={<ReloadOutlined />} disabled={saving} onClick={() => void reload()}>
            {t('links.reload')}
          </Button>,
          <Button
            key="save"
            type="primary"
            icon={<SaveOutlined />}
            loading={saving}
            disabled={dirtyCount === 0}
            onClick={() => void save()}
          >
            {saving ? t('config.saving') : t('links.save')}
          </Button>,
        ]}
      />

      <LinkFormModal
        open={editing !== null}
        initial={editing?.row}
        appSystems={dimensions.appSystem}
        onCancel={() => setEditing(null)}
        onSubmit={(values) => upsert(values, editing?.row)}
      />
    </Space>
  );
}
