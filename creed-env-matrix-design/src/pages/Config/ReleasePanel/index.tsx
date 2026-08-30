import { useCallback, useEffect, useMemo, useState } from 'react';
import { ProCard, ProTable } from '@ant-design/pro-components';
import type { ProColumns } from '@ant-design/pro-components';
import { Alert, App, Button, Col, Empty, List, Popconfirm, Row, Space, Tag, Typography } from 'antd';
import {
  DeleteOutlined,
  EditOutlined,
  PlusOutlined,
  ReloadOutlined,
  SaveOutlined,
} from '@ant-design/icons';
import { envMatrixApi } from '../../../api/envMatrix';
import { useI18n } from '../../../locales';
import type { MessageKey } from '../../../locales';
import { ANY_COUNTRY } from '../../../api/types';
import type { Dimensions, Release, ReleaseStatus, ReleaseTopologyIssue } from '../../../api/types';
import { ParticipantFormModal } from './ParticipantFormModal';
import type { ParticipantFormValues } from './ParticipantFormModal';
import { ConnectionFormModal } from './ConnectionFormModal';
import type { ConnectionFormValues } from './ConnectionFormModal';
import { ReleaseFormModal } from './ReleaseFormModal';
import type { ReleaseFormValues } from './ReleaseFormModal';
import { sliceOf, toConnectionRow, toParticipantRow, toTopologyRequest } from './types';
import type { ConnectionRow, ParticipantRow } from './types';

const STATUS_COLOR: Record<ReleaseStatus, string> = {
  DRAFT: 'default',
  ACTIVE: 'success',
  ARCHIVED: 'warning',
};

let sequence = 0;
const nextKey = (prefix: string) => `${prefix}-${(sequence += 1)}`;

interface ReleasePanelProps {
  dimensions: Dimensions;
}

/**
 * The release topology editor: a release list on the left, its participants and connections on the
 * right, one save.
 *
 * Participants and connections are saved together because they are one graph — a connection cannot
 * exist without its two ends, and the commonest edit is "add a participant and connect it". The
 * local rows therefore key on a client-side `_key` rather than a database id, and `toTopologyRequest`
 * turns an unsaved participant's `_key` into the payload's `ref`.
 */
export function ReleasePanel({ dimensions }: ReleasePanelProps) {
  const { t } = useI18n();
  const { message } = App.useApp();

  const [releases, setReleases] = useState<Release[]>([]);
  const [selectedId, setSelectedId] = useState<number>();
  const [participants, setParticipants] = useState<ParticipantRow[]>([]);
  const [connections, setConnections] = useState<ConnectionRow[]>([]);
  const [loading, setLoading] = useState(false);
  const [saving, setSaving] = useState(false);
  const [issues, setIssues] = useState<ReleaseTopologyIssue[]>([]);

  const [editingRelease, setEditingRelease] = useState<{ release?: Release } | null>(null);
  const [editingParticipant, setEditingParticipant] = useState<{ row?: ParticipantRow } | null>(null);
  const [editingConnection, setEditingConnection] = useState<{ row?: ConnectionRow } | null>(null);

  const selected = releases.find((release) => release.id === selectedId);

  const loadReleases = useCallback(async () => {
    try {
      const rows = await envMatrixApi.releases();
      setReleases(rows);
      setSelectedId((current) =>
        current && rows.some((r) => r.id === current) ? current : rows[0]?.id,
      );
    } catch (e) {
      message.error((e as Error).message);
    }
  }, [message]);

  useEffect(() => {
    void loadReleases();
  }, [loadReleases]);

  const loadTopology = useCallback(async () => {
    if (!selectedId) {
      setParticipants([]);
      setConnections([]);
      return;
    }
    setLoading(true);
    try {
      const topology = await envMatrixApi.releaseTopology(selectedId);
      setParticipants(topology.nodes.map(toParticipantRow));
      setConnections(topology.links.map(toConnectionRow));
      setIssues([]);
    } catch (e) {
      message.error((e as Error).message);
    } finally {
      setLoading(false);
    }
  }, [selectedId, message]);

  useEffect(() => {
    void loadTopology();
  }, [loadTopology]);

  const dirtyCount =
    participants.filter((row) => row._dirty || row._new).length +
    connections.filter((row) => row._dirty || row._new).length;

  /** Set once the local rows have diverged from what was loaded — enables Save. */
  const [removedSomething, setRemovedSomething] = useState(false);
  useEffect(() => setRemovedSomething(false), [selectedId]);

  // ---------------------------------------------------------------- releases

  const submitRelease = async (values: ReleaseFormValues) => {
    try {
      if (editingRelease?.release) {
        await envMatrixApi.updateRelease(editingRelease.release.id, values);
      } else {
        const created = await envMatrixApi.createRelease(values);
        setSelectedId(created.id);
      }
      setEditingRelease(null);
      await loadReleases();
    } catch (e) {
      message.error((e as Error).message);
    }
  };

  const deleteRelease = async (id: number) => {
    try {
      await envMatrixApi.removeRelease(id);
      setSelectedId(undefined);
      await loadReleases();
    } catch (e) {
      message.error((e as Error).message);
    }
  };

  // ---------------------------------------------------------------- participants

  const upsertParticipant = (values: ParticipantFormValues, existing?: ParticipantRow) => {
    const clash = participants.find(
      (row) =>
        row._key !== existing?._key &&
        row.appSystem === values.appSystem &&
        row.country === values.country &&
        row.envInstance === values.envInstance,
    );
    if (clash) {
      message.error(t('links.duplicateParticipant', { slice: sliceOf(clash) }));
      return;
    }

    if (existing) {
      setParticipants((prev) =>
        prev.map((row) =>
          row._key === existing._key
            ? { ...row, ...values, note: values.note ?? null, _dirty: !row._new }
            : row,
        ),
      );
    } else {
      setParticipants((prev) => [
        ...prev,
        {
          _key: nextKey('new'),
          _new: true,
          _dirty: false,
          id: null,
          label: null,
          note: values.note ?? null,
          appSystem: values.appSystem,
          country: values.country,
          envInstance: values.envInstance,
          // Unpinned: the graph derives its layer from the links until someone says otherwise.
          layer: null,
          sortOrder: 0,
        },
      ]);
    }
    setEditingParticipant(null);
  };

  /** Removing a participant takes its connections with it — the backend would reject them anyway. */
  const removeParticipant = (key: string) => {
    setParticipants((prev) => prev.filter((row) => row._key !== key));
    setConnections((prev) => prev.filter((row) => row.sourceKey !== key && row.targetKey !== key));
    setRemovedSomething(true);
  };

  // ---------------------------------------------------------------- connections

  const upsertConnection = (values: ConnectionFormValues, existing?: ConnectionRow) => {
    const clash = connections.find(
      (row) =>
        row._key !== existing?._key &&
        ((row.sourceKey === values.sourceKey && row.targetKey === values.targetKey) ||
          (row.sourceKey === values.targetKey && row.targetKey === values.sourceKey)),
    );
    if (clash) {
      message.error(t('links.duplicateConnection'));
      return;
    }

    if (existing) {
      setConnections((prev) =>
        prev.map((row) =>
          row._key === existing._key
            ? { ...row, ...values, note: values.note ?? null, _dirty: !row._new }
            : row,
        ),
      );
    } else {
      setConnections((prev) => [
        ...prev,
        {
          _key: nextKey('link'),
          _new: true,
          _dirty: false,
          id: null,
          note: values.note ?? null,
          sourceKey: values.sourceKey,
          targetKey: values.targetKey,
          direction: values.direction,
        },
      ]);
    }
    setEditingConnection(null);
  };

  const removeConnection = (key: string) => {
    setConnections((prev) => prev.filter((row) => row._key !== key));
    setRemovedSomething(true);
  };

  // ---------------------------------------------------------------- save

  const save = async () => {
    if (!selectedId) return;
    setSaving(true);
    try {
      const result = await envMatrixApi.saveReleaseTopology(
        selectedId,
        toTopologyRequest(participants, connections),
      );
      if (!result.success) {
        setIssues(result.issues);
        message.error(t('config.rejected', { count: result.issues.length }));
        return;
      }
      setIssues([]);
      message.success(
        `${t('config.saved')} — ${t('links.participants')} ${result.nodesInserted}/${
          result.nodesUpdated
        }/${result.nodesDeleted} · ${t('links.connections')} ${result.linksInserted}/${
          result.linksUpdated
        }/${result.linksDeleted}`,
      );
      setRemovedSomething(false);
      await loadReleases();
      await loadTopology();
    } catch (e) {
      message.error((e as Error).message);
    } finally {
      setSaving(false);
    }
  };

  // ---------------------------------------------------------------- render

  const titleOf = (key: string) => {
    const row = participants.find((p) => p._key === key);
    return row ? sliceOf(row) : key;
  };

  /**
   * Participants whose env instance does not start with the release's tier.
   *
   * Only a warning: a promotion chain legitimately spans tiers, and `release.tier` is a label rather
   * than a constraint on either side of the wire.
   */
  const tierMismatch = useMemo(() => {
    if (!selected) return [];
    return participants
      .filter((row) => !row.envInstance.toUpperCase().startsWith(selected.tier.toUpperCase()))
      .map(sliceOf);
  }, [participants, selected]);

  const participantColumns: ProColumns<ParticipantRow>[] = [
    { title: t('column.appSystem'), dataIndex: 'appSystem', width: 150,
      render: (_, row) => <Typography.Text strong>{row.appSystem}</Typography.Text> },
    { title: t('column.country'), dataIndex: 'country', width: 130,
      render: (_, row) =>
        row.country === ANY_COUNTRY ? (
          <Tag bordered={false}>{t('links.anyCountry')}</Tag>
        ) : (
          row.country
        ) },
    { title: t('column.envInstance'), dataIndex: 'envInstance', width: 130 },
    { title: t('links.note'), dataIndex: 'note', ellipsis: true },
    {
      title: t('column.actions'),
      valueType: 'option',
      width: 120,
      render: (_, row) => [
        <Button key="edit" type="link" size="small" icon={<EditOutlined />}
          onClick={() => setEditingParticipant({ row })} />,
        <Popconfirm key="delete" title={t('links.deleteParticipantConfirm')}
          okText={t('common.yes')} cancelText={t('common.no')}
          onConfirm={() => removeParticipant(row._key)}>
          <Button type="link" size="small" danger icon={<DeleteOutlined />} />
        </Popconfirm>,
      ],
    },
  ];

  const connectionColumns: ProColumns<ConnectionRow>[] = [
    { title: t('links.sourceApp'), dataIndex: 'sourceKey', width: 220,
      render: (_, row) => <Typography.Text strong>{titleOf(row.sourceKey)}</Typography.Text> },
    { title: t('links.direction'), dataIndex: 'direction', width: 120,
      render: (_, row) => (
        <Tag bordered={false} color={row.direction === 'BIDIRECTIONAL' ? 'blue' : 'default'}>
          {row.direction === 'BIDIRECTIONAL' ? '↔' : '→'}{' '}
          {t(`links.direction.${row.direction}` as MessageKey)}
        </Tag>
      ) },
    { title: t('links.targetApp'), dataIndex: 'targetKey', width: 220,
      render: (_, row) => <Typography.Text strong>{titleOf(row.targetKey)}</Typography.Text> },
    { title: t('links.note'), dataIndex: 'note', ellipsis: true },
    {
      title: t('column.actions'),
      valueType: 'option',
      width: 120,
      render: (_, row) => [
        <Button key="edit" type="link" size="small" icon={<EditOutlined />}
          onClick={() => setEditingConnection({ row })} />,
        <Popconfirm key="delete" title={t('links.deleteConfirm')}
          okText={t('common.yes')} cancelText={t('common.no')}
          onConfirm={() => removeConnection(row._key)}>
          <Button type="link" size="small" danger icon={<DeleteOutlined />} />
        </Popconfirm>,
      ],
    },
  ];

  const issuesFor = (section: 'nodes' | 'links') => issues.filter((issue) => issue.section === section);

  return (
    <Space direction="vertical" size="middle" style={{ width: '100%' }}>
      <Alert type="info" showIcon message={t('links.saveHint')} description={t('links.participantsHint')} />

      {issues.length > 0 && (
        <Alert
          type="error"
          showIcon
          message={t('config.rejected', { count: issues.length })}
          description={
            <ul style={{ margin: 0, paddingInlineStart: 18 }}>
              {issues.map((issue) => (
                <li key={`${issue.section}-${issue.index}-${issue.field}`}>
                  {issue.section === 'nodes' ? t('links.participants') : t('links.connections')} ·{' '}
                  {t('config.rowLabel', { row: issue.index + 1 })} · {issue.field}: {issue.message}
                </li>
              ))}
            </ul>
          }
        />
      )}

      {tierMismatch.length > 0 && (
        <Alert type="warning" showIcon message={t('links.tierMismatch', { slices: tierMismatch.join(', ') })} />
      )}

      <Row gutter={[12, 12]}>
        <Col xs={24} lg={7}>
          <ProCard
            size="small"
            bordered
            title={t('links.releases')}
            extra={
              <Button size="small" icon={<PlusOutlined />} onClick={() => setEditingRelease({})}>
                {t('links.newRelease')}
              </Button>
            }
            bodyStyle={{ padding: 0 }}
          >
            {releases.length === 0 ? (
              <Empty
                image={Empty.PRESENTED_IMAGE_SIMPLE}
                description={t('links.emptyReleases')}
                style={{ padding: '24px 0' }}
              />
            ) : (
              <List
                size="small"
                dataSource={releases}
                renderItem={(release) => (
                  <List.Item
                    onClick={() => setSelectedId(release.id)}
                    style={{
                      cursor: 'pointer',
                      paddingInline: 14,
                      background: release.id === selectedId ? 'var(--env-matrix-selected-bg)' : undefined,
                    }}
                    actions={[
                      <Button key="edit" type="link" size="small" icon={<EditOutlined />}
                        onClick={(event) => {
                          event.stopPropagation();
                          setEditingRelease({ release });
                        }} />,
                      <Popconfirm key="delete" title={t('links.deleteReleaseConfirm')}
                        okText={t('common.yes')} cancelText={t('common.no')}
                        onConfirm={() => void deleteRelease(release.id)}>
                        <Button type="link" size="small" danger icon={<DeleteOutlined />}
                          onClick={(event) => event.stopPropagation()} />
                      </Popconfirm>,
                    ]}
                  >
                    <List.Item.Meta
                      title={
                        <Space size={6}>
                          <Typography.Text strong>{release.name}</Typography.Text>
                          <Tag bordered={false} color={STATUS_COLOR[release.status]}>
                            {t(`links.status.${release.status}` as MessageKey)}
                          </Tag>
                        </Space>
                      }
                      description={
                        <Typography.Text type="secondary" style={{ fontSize: 12 }}>
                          {release.tier} · {release.nodeCount} / {release.linkCount}
                        </Typography.Text>
                      }
                    />
                  </List.Item>
                )}
              />
            )}
          </ProCard>
        </Col>

        <Col xs={24} lg={17}>
          {!selected ? (
            <ProCard size="small" bordered>
              <Empty image={Empty.PRESENTED_IMAGE_SIMPLE} description={t('links.noRelease')} />
            </ProCard>
          ) : (
            <Space direction="vertical" size="middle" style={{ width: '100%' }}>
              <ProTable<ParticipantRow>
                rowKey="_key"
                size="small"
                search={false}
                options={false}
                loading={loading}
                dataSource={participants}
                columns={participantColumns}
                pagination={false}
                locale={{ emptyText: t('links.emptyParticipants') }}
                rowClassName={(row, index) =>
                  [
                    row._dirty || row._new ? 'env-matrix-row--dirty' : '',
                    issuesFor('nodes').some((issue) => issue.index === index)
                      ? 'env-matrix-row--dirty'
                      : '',
                  ]
                    .filter(Boolean)
                    .join(' ')
                }
                headerTitle={
                  <Space size="small">
                    <Typography.Text strong>{t('links.participants')}</Typography.Text>
                    <Typography.Text type={dirtyCount || removedSomething ? 'warning' : 'secondary'}>
                      {dirtyCount || removedSomething
                        ? t('config.dirty', { count: dirtyCount })
                        : t('config.clean')}
                    </Typography.Text>
                  </Space>
                }
                toolBarRender={() => [
                  <Button key="add" size="small" icon={<PlusOutlined />}
                    onClick={() => setEditingParticipant({})}>
                    {t('links.addParticipant')}
                  </Button>,
                ]}
              />

              <ProTable<ConnectionRow>
                rowKey="_key"
                size="small"
                search={false}
                options={false}
                loading={loading}
                dataSource={connections}
                columns={connectionColumns}
                pagination={false}
                locale={{ emptyText: t('links.emptyConnections') }}
                rowClassName={(row, index) =>
                  [
                    row._dirty || row._new ? 'env-matrix-row--dirty' : '',
                    issuesFor('links').some((issue) => issue.index === index)
                      ? 'env-matrix-row--dirty'
                      : '',
                  ]
                    .filter(Boolean)
                    .join(' ')
                }
                headerTitle={<Typography.Text strong>{t('links.connections')}</Typography.Text>}
                toolBarRender={() => [
                  <Button key="add" size="small" icon={<PlusOutlined />}
                    disabled={participants.length < 2}
                    onClick={() => setEditingConnection({})}>
                    {t('links.addConnection')}
                  </Button>,
                  <Button key="reload" size="small" icon={<ReloadOutlined />} disabled={saving}
                    onClick={() => void loadTopology()}>
                    {t('links.reload')}
                  </Button>,
                  <Button key="save" size="small" type="primary" icon={<SaveOutlined />}
                    loading={saving} disabled={dirtyCount === 0 && !removedSomething}
                    onClick={() => void save()}>
                    {saving ? t('config.saving') : t('links.save')}
                  </Button>,
                ]}
              />

              <Typography.Text type="secondary" style={{ fontSize: 12 }}>
                {t('links.connectionsHint')}
              </Typography.Text>
            </Space>
          )}
        </Col>
      </Row>

      <ReleaseFormModal
        open={editingRelease !== null}
        initial={editingRelease?.release}
        formKey={editingRelease?.release ? `release-${editingRelease.release.id}` : undefined}
        dimensions={dimensions}
        onCancel={() => setEditingRelease(null)}
        onSubmit={(values) => void submitRelease(values)}
      />

      <ParticipantFormModal
        open={editingParticipant !== null}
        initial={editingParticipant?.row}
        formKey={editingParticipant?.row?._key}
        dimensions={dimensions}
        onCancel={() => setEditingParticipant(null)}
        onSubmit={(values) => upsertParticipant(values, editingParticipant?.row)}
      />

      <ConnectionFormModal
        open={editingConnection !== null}
        initial={editingConnection?.row}
        formKey={editingConnection?.row?._key}
        participants={participants}
        onCancel={() => setEditingConnection(null)}
        onSubmit={(values) => upsertConnection(values, editingConnection?.row)}
      />
    </Space>
  );
}
