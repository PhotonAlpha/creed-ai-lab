import { Alert, Collapse, Space, Table, Tag, Typography } from 'antd';
import type { TableColumnsType } from 'antd';
import { useI18n } from '../../locales';
import type { MessageKey } from '../../locales';
import type { ConflictGroup, ConflictScope, Endpoint } from '../../api/types';

interface ConflictPanelProps {
  conflicts: ConflictGroup[];
  scope: ConflictScope;
}

/**
 * The "why is that cell red" panel: one collapsible section per colliding address, listing the
 * endpoints that claim it. Collapsed by default — on a clean estate this is a single green line, and
 * on a messy one nobody wants twenty expanded tables at once.
 */
export function ConflictPanel({ conflicts, scope }: ConflictPanelProps) {
  const { t } = useI18n();

  const scopeLabel = t(`conflict.scope.${scope}` as MessageKey);

  if (conflicts.length === 0) {
    return (
      <Alert
        type="success"
        showIcon
        message={t('conflict.none')}
        description={`${t('conflict.scope')}: ${scopeLabel} — ${t('conflict.scopeHint')}`}
      />
    );
  }

  const columns: TableColumnsType<Endpoint> = [
    { title: t('column.appSystem'), dataIndex: 'appSystem', width: 110 },
    { title: t('column.envInstance'), dataIndex: 'envInstance', width: 100 },
    { title: t('column.country'), dataIndex: 'country', width: 80 },
    { title: t('column.service'), dataIndex: 'service', width: 110 },
    { title: t('column.instance'), dataIndex: 'instance', width: 90 },
    {
      title: t('column.scheme'),
      dataIndex: 'scheme',
      width: 80,
      render: (scheme: string) => <Tag bordered={false}>{scheme}</Tag>,
    },
    { title: t('column.host'), dataIndex: 'host', ellipsis: true },
    { title: t('column.ip'), dataIndex: 'ip', width: 120 },
    { title: t('column.port'), dataIndex: 'port', width: 80 },
    { title: t('column.note'), dataIndex: 'note', ellipsis: true },
  ];

  return (
    <Space direction="vertical" size="middle" style={{ width: '100%' }}>
      <Alert
        type="error"
        showIcon
        message={t('conflict.summary', { count: conflicts.length })}
        description={`${t('conflict.scope')}: ${scopeLabel} — ${t('conflict.scopeHint')}`}
      />
      <Collapse
        size="small"
        items={conflicts.map((group, index) => ({
          key: `${group.kind}-${group.scopeKey}-${group.value}-${index}`,
          label: (
            <Space size="small" wrap>
              <Tag color="red" bordered={false}>
                {t(`conflict.kind.${group.kind}` as MessageKey)}
              </Tag>
              <Typography.Text strong>{group.value}</Typography.Text>
              <Typography.Text type="secondary">{group.scopeKey}</Typography.Text>
              <Typography.Text type="secondary">
                {t('conflict.members', { count: group.endpoints.length })}
              </Typography.Text>
            </Space>
          ),
          children: (
            <Table<Endpoint>
              rowKey="id"
              size="small"
              columns={columns}
              dataSource={group.endpoints}
              pagination={false}
              scroll={{ x: 'max-content' }}
            />
          ),
        }))}
      />
    </Space>
  );
}
