import { ModalForm, ProFormSelect, ProFormText, ProFormTextArea } from '@ant-design/pro-components';
import { AutoComplete, Col, Form, InputNumber, Row } from 'antd';
import { useI18n } from '../../locales';
import type { MessageKey } from '../../locales';
import type { Dimensions } from '../../api/types';
import type { ConfigRow } from './types';

/** Values the form edits — the stored fields only, none of the derived ones. */
export interface EndpointFormValues {
  appSystem: string;
  tier: string;
  envInstance: string;
  country: string;
  service: string;
  instance: string;
  scheme: string;
  host: string;
  ip: string;
  port: number;
  note?: string | null;
}

const DIMENSION_FIELDS = [
  'appSystem',
  'tier',
  'envInstance',
  'country',
  'service',
  'instance',
] as const;

interface EndpointFormModalProps {
  open: boolean;
  /** Row being edited, or `undefined` when adding. */
  initial?: ConfigRow;
  dimensions: Dimensions;
  onCancel: () => void;
  onSubmit: (values: EndpointFormValues) => void;
}

/**
 * Add/edit dialog — a single, page-level instance driven by `open`.
 *
 * Deliberately *not* a per-row `trigger` modal: rendering one `ModalForm` inside every table row
 * mounts one modal per visible row, and the trigger's open state was being lost when the table
 * re-rendered the cell, so the first click after a load did nothing.
 *
 * The six identity dimensions use {@link AutoComplete} rather than a fixed `Select`: the option list
 * is only a convenience derived from existing rows, and the requirement is explicit that new
 * dimension values (an extra country, a `UAT6`) must be addable as data. `scheme` is the exception —
 * it is a `Select` limited to http/https, because the backend rejects anything else.
 */
export function EndpointFormModal({
  open,
  initial,
  dimensions,
  onCancel,
  onSubmit,
}: EndpointFormModalProps) {
  const { t } = useI18n();

  const required = [{ required: true, message: t('config.validation.required') }];

  return (
    <ModalForm<EndpointFormValues>
      title={initial ? t('common.edit') : t('config.add')}
      open={open}
      // Remount per open so `initialValues` is re-read; otherwise the form would still hold the
      // previous row's values.
      key={initial?._key ?? 'new'}
      modalProps={{
        destroyOnHidden: true,
        okText: t('common.ok'),
        cancelText: t('common.cancel'),
        onCancel,
      }}
      onOpenChange={(next) => {
        if (!next) onCancel();
      }}
      initialValues={
        initial ?? {
          scheme: dimensions.scheme.includes('https') ? 'https' : (dimensions.scheme[0] ?? 'https'),
          port: 8443,
        }
      }
      onFinish={async (values) => {
        onSubmit(values);
        return true;
      }}
    >
      <Row gutter={12}>
        {DIMENSION_FIELDS.map((field) => (
          <Col xs={24} sm={12} md={8} key={field}>
            <Form.Item name={field} label={t(`column.${field}` as MessageKey)} rules={required}>
              <AutoComplete
                allowClear
                placeholder={t('filter.placeholder')}
                options={dimensions[field].map((value) => ({ value }))}
                // Suggestions narrow as you type, but any new value is accepted.
                filterOption={(input, option) =>
                  String(option?.value ?? '')
                    .toLowerCase()
                    .includes(input.toLowerCase())
                }
              />
            </Form.Item>
          </Col>
        ))}

        <Col xs={24} sm={12} md={8}>
          <ProFormSelect
            name="scheme"
            label={t('column.scheme')}
            rules={required}
            options={[
              { label: 'https', value: 'https' },
              { label: 'http', value: 'http' },
            ]}
          />
        </Col>

        <Col xs={24} sm={12} md={8}>
          <Form.Item
            name="port"
            label={t('column.port')}
            rules={[
              ...required,
              { type: 'number', min: 1, max: 65535, message: t('config.validation.port') },
            ]}
          >
            <InputNumber style={{ width: '100%' }} min={1} max={65535} />
          </Form.Item>
        </Col>

        <Col xs={24} sm={12} md={8}>
          <ProFormText name="ip" label={t('column.ip')} rules={required} />
        </Col>

        <Col xs={24}>
          <ProFormText name="host" label={t('column.host')} rules={required} />
        </Col>

        <Col xs={24}>
          <ProFormTextArea name="note" label={t('column.note')} fieldProps={{ rows: 2 }} />
        </Col>
      </Row>
    </ModalForm>
  );
}
