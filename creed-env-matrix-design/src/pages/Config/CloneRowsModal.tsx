import { useMemo } from 'react';
import { ModalForm } from '@ant-design/pro-components';
import { Alert, AutoComplete, Col, Form, Input, Row, Space, Table, Tooltip, Typography, theme } from 'antd';
import type { TableColumnsType } from 'antd';
import { WarningOutlined } from '@ant-design/icons';
import { useI18n } from '../../locales';
import type { MessageKey } from '../../locales';
import type { Dimensions } from '../../api/types';
import type { ConfigRow } from './types';
import { CLONE_DIMENSIONS, previewClones } from './clone';
import type { CloneOverrides, ClonePreview } from './clone';
import type { EndpointFormValues } from './EndpointFormModal';

interface CloneRowsModalProps {
  open: boolean;
  /** The ticked rows, in table order. */
  sources: ConfigRow[];
  /** Every row on the page — what the clones' identities are checked against. */
  rows: ConfigRow[];
  dimensions: Dimensions;
  onCancel: () => void;
  onSubmit: (clones: EndpointFormValues[]) => void;
}

/**
 * "Clone selected" — copies the ticked rows as new, unsaved rows, optionally moved to another
 * environment on the way.
 *
 * A clone with nothing changed has the same seven-dimension identity as its source and the save
 * would reject it, so the dialog offers the edits a new environment almost always needs, applied to
 * every clone at once, and previews which clones would still collide. Collisions are a warning, not
 * a block: the clones stay editable one by one afterwards, and the save reports any left over.
 *
 * Single page-level instance, like {@link EndpointFormModal}, for the same reason.
 */
export function CloneRowsModal({ open, sources, rows, dimensions, onCancel, onSubmit }: CloneRowsModalProps) {
  const { t } = useI18n();
  const { token } = theme.useToken();
  const [form] = Form.useForm<CloneOverrides>();
  const overrides = Form.useWatch([], form) as CloneOverrides | undefined;

  const preview = useMemo(
    () => (open ? previewClones(sources, rows, overrides ?? {}) : []),
    [open, sources, rows, overrides],
  );
  const duplicates = preview.filter((clone) => clone.duplicate).length;

  // Sized to fit the dialog without a horizontal scrollbar: host and IP are what find/replace
  // rewrites, so they are the columns that must stay on screen.
  const columns: TableColumnsType<ClonePreview> = [
    {
      title: '',
      key: 'duplicate',
      width: 40,
      render: (_, c) =>
        c.duplicate ? (
          <Tooltip title={t('config.clone.duplicateRow')}>
            <WarningOutlined style={{ color: token.colorWarning }} />
          </Tooltip>
        ) : null,
    },
    { title: t('column.appSystem'), width: 100, render: (_, c) => c.values.appSystem },
    { title: t('column.tier'), width: 60, render: (_, c) => c.values.tier },
    { title: t('column.envInstance'), width: 90, render: (_, c) => c.values.envInstance },
    { title: t('column.country'), width: 70, render: (_, c) => c.values.country },
    { title: t('column.service'), width: 110, ellipsis: true, render: (_, c) => c.values.service },
    { title: t('column.instance'), width: 75, render: (_, c) => c.values.instance },
    { title: t('column.scheme'), width: 75, render: (_, c) => c.values.scheme },
    { title: t('column.host'), width: 240, ellipsis: true, render: (_, c) => c.values.host },
    { title: t('column.ip'), width: 110, render: (_, c) => c.values.ip },
    { title: t('column.port'), width: 65, render: (_, c) => c.values.port },
  ];

  return (
    <ModalForm<CloneOverrides>
      form={form}
      title={t('config.clone.title', { count: sources.length })}
      open={open}
      width={1100}
      modalProps={{
        destroyOnHidden: true,
        okText: t('config.clone.ok', { count: sources.length }),
        cancelText: t('common.cancel'),
        onCancel,
      }}
      onOpenChange={(next) => {
        // Start every clone from "keep everything" — a find/replace left over from the last batch
        // would silently rewrite this one.
        if (!next) {
          form.resetFields();
          onCancel();
        }
      }}
      onFinish={async () => {
        onSubmit(preview.map((clone) => clone.values));
        form.resetFields();
        return true;
      }}
    >
      <Typography.Paragraph type="secondary">{t('config.clone.hint')}</Typography.Paragraph>

      <Row gutter={12}>
        {CLONE_DIMENSIONS.map((field) => (
          <Col xs={24} sm={12} md={6} key={field}>
            <Form.Item name={field} label={t(`column.${field}` as MessageKey)}>
              <AutoComplete
                allowClear
                placeholder={t('config.clone.keep')}
                options={dimensions[field].map((value) => ({ value }))}
                filterOption={(input, option) =>
                  String(option?.value ?? '').toLowerCase().includes(input.toLowerCase())
                }
              />
            </Form.Item>
          </Col>
        ))}

        <Col xs={24} md={12}>
          <Form.Item label={t('config.clone.hostReplace')} tooltip={t('config.clone.replaceTip')}>
            <Space.Compact block>
              <Form.Item name="hostFind" noStyle>
                <Input placeholder={t('config.clone.find')} style={{ flex: 1 }} />
              </Form.Item>
              <Form.Item name="hostReplace" noStyle>
                <Input placeholder={t('config.clone.replaceWith')} style={{ flex: 1 }} />
              </Form.Item>
            </Space.Compact>
          </Form.Item>
        </Col>
        <Col xs={24} md={12}>
          <Form.Item label={t('config.clone.ipReplace')} tooltip={t('config.clone.replaceTip')}>
            <Space.Compact block>
              <Form.Item name="ipFind" noStyle>
                <Input placeholder={t('config.clone.find')} style={{ flex: 1 }} />
              </Form.Item>
              <Form.Item name="ipReplace" noStyle>
                <Input placeholder={t('config.clone.replaceWith')} style={{ flex: 1 }} />
              </Form.Item>
            </Space.Compact>
          </Form.Item>
        </Col>
      </Row>

      {duplicates > 0 && (
        <Alert
          type="warning"
          showIcon
          style={{ marginBottom: 12 }}
          message={t('config.clone.duplicates', { count: duplicates, total: preview.length })}
        />
      )}

      <Table<ClonePreview>
        size="small"
        bordered
        rowKey="sourceKey"
        columns={columns}
        dataSource={preview}
        pagination={false}
        // The column widths' sum; only scrolls on a viewport narrower than the dialog.
        scroll={{ x: 1035, y: 280 }}
      />
    </ModalForm>
  );
}
