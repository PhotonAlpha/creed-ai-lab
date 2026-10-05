import { useMemo } from 'react';
import { ModalForm, ProFormSelect, ProFormText, ProFormTextArea } from '@ant-design/pro-components';
import { Alert, AutoComplete, Col, Form, InputNumber, Row } from 'antd';
import type { FormRule } from 'antd';
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
  /**
   * Adding, pre-filled from this row — the "Copy" action. Kept apart from `initial` because the
   * result is a new row: the source is a template, never the row the dialog writes back to.
   */
  copyFrom?: ConfigRow;
  /** Every row on the page — what a copy's host + IP is checked against. Only read when copying. */
  rows: ConfigRow[];
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
  copyFrom,
  rows,
  dimensions,
  onCancel,
  onSubmit,
}: EndpointFormModalProps) {
  const { t } = useI18n();

  const required = [{ required: true, message: t('config.validation.required') }];

  /**
   * Copy only: a copy must not reuse a host + IP already in the table, so it cannot be confirmed
   * until one of the two is changed. Deliberately narrower than the save's seven-dimension identity
   * and not enforced anywhere else — the existing data legitimately has the same host + IP on
   * several rows (one service's http and https listeners), so add and edit keep the save's rule.
   * Rows marked for deletion are about to go, so their address is free.
   */
  const hostIpOwner = useMemo(() => {
    if (!copyFrom) return null;
    const owners = new Map<string, ConfigRow>();
    rows.forEach((row) => {
      const key = hostIpKey(row.host, row.ip);
      if (!row._deleted && !owners.has(key)) owners.set(key, row);
    });
    return owners;
  }, [copyFrom, rows]);

  // On both fields, each re-run when the other changes (`dependencies`), so fixing either clears both.
  const hostIpRule: FormRule = ({ getFieldValue }) => ({
    validator: async () => {
      const host = getFieldValue('host') as string | undefined;
      const ip = getFieldValue('ip') as string | undefined;
      if (!hostIpOwner || !host?.trim() || !ip?.trim()) return;
      const owner = hostIpOwner.get(hostIpKey(host, ip));
      if (owner) {
        throw new Error(
          t('config.copyHostIpTaken', {
            row: [owner.appSystem, owner.envInstance, owner.country, owner.service, owner.instance, owner.scheme].join(' / '),
          }),
        );
      }
    },
  });

  return (
    <ModalForm<EndpointFormValues>
      title={initial ? t('common.edit') : copyFrom ? t('config.copyTitle') : t('config.add')}
      open={open}
      // Remount per open so `initialValues` is re-read; otherwise the form would still hold the
      // previous row's values.
      key={initial?._key ?? (copyFrom ? `copy-${copyFrom._key}` : 'new')}
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
        initial ??
        copyFrom ?? {
          scheme: dimensions.scheme.includes('https') ? 'https' : (dimensions.scheme[0] ?? 'https'),
          port: 8443,
        }
      }
      onFinish={async (values) => {
        onSubmit(values);
        return true;
      }}
    >
      {copyFrom && (
        <Alert type="info" showIcon style={{ marginBottom: 16 }} message={t('config.copyHint')} />
      )}
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
          <ProFormText
            name="ip"
            label={t('column.ip')}
            rules={[...required, hostIpRule]}
            dependencies={['host']}
          />
        </Col>

        <Col xs={24}>
          <ProFormText
            name="host"
            label={t('column.host')}
            rules={[...required, hostIpRule]}
            dependencies={['ip']}
          />
        </Col>

        <Col xs={24}>
          <ProFormTextArea name="note" label={t('column.note')} fieldProps={{ rows: 2 }} />
        </Col>
      </Row>
    </ModalForm>
  );
}

/** Hostnames are case-insensitive; surrounding whitespace is never meaningful in either field. */
function hostIpKey(host: string, ip: string) {
  return `${host.trim().toLowerCase()}|${ip.trim()}`;
}
