import { useCallback, useEffect, useMemo, useState } from 'react';
import { PageContainer, ProCard, ProTable } from '@ant-design/pro-components';
import type { ProColumns } from '@ant-design/pro-components';
import { Alert, App, Button, Popconfirm, Space, Tag, Tooltip, Typography } from 'antd';
import {
  DeleteOutlined,
  EditOutlined,
  PlusOutlined,
  ReloadOutlined,
  SaveOutlined,
  UndoOutlined,
  WarningOutlined,
} from '@ant-design/icons';
import { FilterBar } from '../../components/FilterBar';
import { HealthTag } from '../../components/HealthTag';
import { useDimensions } from '../../hooks/useDimensions';
import { envMatrixApi } from '../../api/envMatrix';
import { useI18n } from '../../locales';
import type { BatchSaveIssue, EndpointFilter } from '../../api/types';
import { EndpointFormModal } from './EndpointFormModal';
import { LinksPanel } from './LinksPanel';
import type { EndpointFormValues } from './EndpointFormModal';
import type { ConfigRow } from './types';
import { toConfigRow, toRequest } from './types';

let newRowSequence = 0;

export function ConfigPage() {
  const { t } = useI18n();
  const { message, modal } = App.useApp();
  const { dimensions, reload: reloadDimensions } = useDimensions();

  const [rows, setRows] = useState<ConfigRow[]>([]);
  const [loading, setLoading] = useState(true);
  const [saving, setSaving] = useState(false);
  const [issues, setIssues] = useState<BatchSaveIssue[]>([]);
  const [filter, setFilter] = useState<EndpointFilter>({});
  /** Which editor is on screen. The two tables share nothing but this page's chrome. */
  const [tab, setTab] = useState<'endpoints' | 'links'>('endpoints');
  /**
   * Which row the single page-level dialog is editing: `null` = closed, `{row: undefined}` = add.
   * One dialog for the whole page rather than one per table row.
   */
  const [editing, setEditing] = useState<{ row?: ConfigRow } | null>(null);

  /**
   * Always loads the complete, unfiltered table.
   *
   * Saving uses `deleteMissing: true` — the payload is authoritative and anything absent from it is
   * deleted — so the page must never hold a server-filtered subset. Narrowing is therefore done
   * client-side, over rows that are all present in memory.
   */
  const load = useCallback(async () => {
    setLoading(true);
    try {
      const endpoints = await envMatrixApi.endpoints();
      setRows(endpoints.map(toConfigRow));
      setIssues([]);
    } catch (e) {
      message.error((e as Error).message);
    } finally {
      setLoading(false);
    }
  }, [message]);

  useEffect(() => {
    void load();
  }, [load]);

  const dirtyCount = rows.filter((row) => row._dirty || row._new || row._deleted).length;

  const visibleRows = useMemo(() => {
    const matches = (row: ConfigRow) => {
      const dimensionMatch = (
        ['appSystem', 'tier', 'envInstance', 'country', 'service', 'instance', 'scheme'] as const
      ).every((key) => {
        const selected = filter[key];
        return !selected?.length || selected.includes(row[key]);
      });
      if (!dimensionMatch) return false;
      if (!filter.keyword) return true;
      const needle = filter.keyword.toLowerCase();
      return [row.host, row.ip, row.service, row.note ?? ''].some((field) =>
        field.toLowerCase().includes(needle),
      );
    };
    return rows.filter(matches);
  }, [rows, filter]);

  const issuesByKey = useMemo(() => {
    // Issues are reported by index into the payload we sent, which is the non-deleted rows in order.
    const submitted = rows.filter((row) => !row._deleted);
    const map = new Map<string, BatchSaveIssue>();
    issues.forEach((issue) => {
      const row = submitted[issue.index];
      if (row) map.set(row._key, issue);
    });
    return map;
  }, [issues, rows]);

  const upsertRow = (values: EndpointFormValues, existing?: ConfigRow) => {
    setIssues([]);
    if (existing) {
      setRows((current) =>
        current.map((row) =>
          row._key === existing._key
            ? { ...row, ...values, note: values.note ?? null, _dirty: !row._new }
            : row,
        ),
      );
    } else {
      newRowSequence += 1;
      setRows((current) => [
        {
          _key: `new-${newRowSequence}`,
          _new: true,
          _deleted: false,
          _dirty: false,
          id: null,
          ...values,
          note: values.note ?? null,
          url: `${values.scheme}://${values.host}:${values.port}`,
          conflict: false,
          conflictKeys: [],
          health: 'UNKNOWN',
        },
        ...current,
      ]);
    }
  };

  const toggleDelete = (key: string) => {
    setIssues([]);
    setRows((current) =>
      current
        // A row that was only ever local can just disappear; nothing to delete server-side.
        .filter((row) => !(row._key === key && row._new))
        .map((row) => (row._key === key ? { ...row, _deleted: !row._deleted } : row)),
    );
  };

  const save = async () => {
    setSaving(true);
    try {
      const payload = rows.filter((row) => !row._deleted).map(toRequest);
      const result = await envMatrixApi.batchSave(payload, true);
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
      await Promise.all([load(), reloadDimensions()]);
    } catch (e) {
      message.error((e as Error).message);
    } finally {
      setSaving(false);
    }
  };

  const reload = () => {
    if (dirtyCount === 0) {
      void load();
      return;
    }
    modal.confirm({
      title: t('config.discardConfirm'),
      okText: t('common.yes'),
      cancelText: t('common.no'),
      onOk: () => void load(),
    });
  };

  const columns: ProColumns<ConfigRow>[] = [
    { title: t('column.appSystem'), dataIndex: 'appSystem', width: 110, fixed: 'left' },
    { title: t('column.tier'), dataIndex: 'tier', width: 80 },
    { title: t('column.envInstance'), dataIndex: 'envInstance', width: 110 },
    { title: t('column.country'), dataIndex: 'country', width: 90 },
    { title: t('column.service'), dataIndex: 'service', width: 130 },
    { title: t('column.instance'), dataIndex: 'instance', width: 90 },
    {
      title: t('column.scheme'),
      dataIndex: 'scheme',
      width: 80,
      render: (_, row) => (
        <Tag bordered={false} color={row.scheme === 'https' ? 'blue' : 'default'}>
          {row.scheme}
        </Tag>
      ),
    },
    { title: t('column.host'), dataIndex: 'host', width: 260, ellipsis: true },
    { title: t('column.ip'), dataIndex: 'ip', width: 130 },
    { title: t('column.port'), dataIndex: 'port', width: 80 },
    {
      title: t('column.health'),
      dataIndex: 'health',
      width: 100,
      render: (_, row) => <HealthTag health={row.health} />,
    },
    {
      title: t('column.conflict'),
      dataIndex: 'conflict',
      width: 110,
      render: (_, row) =>
        row.conflict ? (
          <Tooltip title={row.conflictKeys.join(', ')}>
            <Tag icon={<WarningOutlined />} color="error" bordered={false}>
              {row.conflictKeys.length}
            </Tag>
          </Tooltip>
        ) : (
          <Typography.Text type="secondary">—</Typography.Text>
        ),
    },
    { title: t('column.note'), dataIndex: 'note', width: 220, ellipsis: true },
    {
      title: t('column.actions'),
      key: 'actions',
      width: 150,
      fixed: 'right',
      render: (_, row) => (
        <Space size="small">
          <Button
            size="small"
            type="link"
            icon={<EditOutlined />}
            disabled={row._deleted}
            onClick={() => setEditing({ row })}
          >
            {t('common.edit')}
          </Button>
          {row._deleted ? (
            <Button
              size="small"
              type="link"
              icon={<UndoOutlined />}
              onClick={() => toggleDelete(row._key)}
            >
              {t('config.undoDelete')}
            </Button>
          ) : (
            <Popconfirm
              title={t('config.deleteConfirm')}
              okText={t('common.yes')}
              cancelText={t('common.no')}
              onConfirm={() => toggleDelete(row._key)}
            >
              <Button size="small" type="link" danger icon={<DeleteOutlined />}>
                {t('config.delete')}
              </Button>
            </Popconfirm>
          )}
        </Space>
      ),
    },
  ];

  return (
    <PageContainer
      title={t('config.title')}
      subTitle={tab === 'links' ? t('links.subtitle') : t('config.subtitle')}
      tabList={[
        { tab: t('links.tab.endpoints'), key: 'endpoints' },
        { tab: t('links.tab.links'), key: 'links' },
      ]}
      tabActiveKey={tab}
      onTabChange={(key) => setTab(key as 'endpoints' | 'links')}
    >
      {tab === 'links' && <LinksPanel dimensions={dimensions} />}

      <Space
        direction="vertical"
        size="middle"
        style={{ width: '100%', display: tab === 'endpoints' ? undefined : 'none' }}
      >
        <Alert type="info" showIcon message={t('config.saveHint')} />

        {issues.length > 0 && (
          <Alert
            type="error"
            showIcon
            message={t('config.rejected', { count: issues.length })}
            description={
              <ul style={{ margin: 0, paddingInlineStart: 20 }}>
                {issues.map((issue) => (
                  <li key={`${issue.index}-${issue.field}`}>
                    {t('config.rowLabel', { row: issue.index + 1 })} — {issue.field}:{' '}
                    {issue.message}
                  </li>
                ))}
              </ul>
            }
          />
        )}

        <ProCard title={t('config.localFilter')} size="small" bordered collapsible defaultCollapsed>
          <FilterBar dimensions={dimensions} value={filter} onChange={setFilter} />
        </ProCard>

        <ProTable<ConfigRow>
          rowKey="_key"
          size="small"
          bordered
          loading={loading}
          columns={columns}
          dataSource={visibleRows}
          // Everything is already in memory; server-side search would break the save semantics.
          search={false}
          options={{ reload: false, density: true, setting: true }}
          // Explicit total width (the sum of the column widths below), not 'max-content': with a
          // fixed-right column, 'max-content' lets the last scrolling column — note — collapse to a
          // few pixels and clip its header.
          scroll={{ x: 1740, y: 560 }}
          pagination={{ pageSize: 20, showSizeChanger: true, showTotal: (total) => `${total}` }}
          rowClassName={(row) =>
            [
              row._deleted ? 'env-matrix-row--deleted' : '',
              row._dirty || row._new ? 'env-matrix-row--dirty' : '',
              issuesByKey.has(row._key) ? 'env-matrix-row--dirty' : '',
            ]
              .filter(Boolean)
              .join(' ')
          }
          headerTitle={
            <Space size="small">
              <Typography.Text strong>{t('config.title')}</Typography.Text>
              <Typography.Text type={dirtyCount ? 'warning' : 'secondary'}>
                {dirtyCount ? t('config.dirty', { count: dirtyCount }) : t('config.clean')}
              </Typography.Text>
            </Space>
          }
          toolBarRender={() => [
            <Button
              key="add"
              type="default"
              icon={<PlusOutlined />}
              onClick={() => setEditing({})}
            >
              {t('config.add')}
            </Button>,
            <Button key="reload" icon={<ReloadOutlined />} onClick={reload} disabled={saving}>
              {t('config.reload')}
            </Button>,
            <Button
              key="save"
              type="primary"
              icon={<SaveOutlined />}
              loading={saving}
              disabled={dirtyCount === 0}
              onClick={() => void save()}
            >
              {saving ? t('config.saving') : t('config.save')}
            </Button>,
          ]}
        />

        <EndpointFormModal
          open={editing !== null}
          initial={editing?.row}
          dimensions={dimensions}
          onCancel={() => setEditing(null)}
          onSubmit={(values) => {
            upsertRow(values, editing?.row);
            setEditing(null);
          }}
        />
      </Space>
    </PageContainer>
  );
}
