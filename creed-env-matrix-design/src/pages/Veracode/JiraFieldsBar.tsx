import { Input, Select, Space, Tooltip, Typography } from 'antd';
import type { CreateField, CreateFieldValues } from '../../api/veracode';
import { useI18n } from '../../locales';

const { Text } = Typography;

/**
 * The create screen's fields this page fills (Component/s, required fields without a Jira default). Options come
 * from Jira through the user's token; values are option ids, or the typed text.
 */
export function JiraFieldsBar({ fields, values, onChange }: {
  fields: CreateField[];
  values: CreateFieldValues;
  onChange: (values: CreateFieldValues) => void;
}) {
  const { t } = useI18n();
  const set = (fieldId: string, value: string[]) => onChange({ ...values, [fieldId]: value });
  return (
    <Space wrap align="center" style={{ marginTop: 16 }}>
      <Tooltip title={t('veracode.fields.hint')}>
        <Text strong>{t('veracode.fields.title')}</Text>
      </Tooltip>
      {fields.map((f) => {
        const value = values[f.fieldId] ?? [];
        const label = <Text>{f.required && <Text type="danger">* </Text>}{f.name}</Text>;
        const control = f.kind === 'OPTIONS' ? (
          <Select
            mode={f.multiple ? 'multiple' : undefined}
            allowClear
            showSearch
            optionFilterProp="label"
            maxTagCount="responsive"
            status={f.required && value.length === 0 ? 'warning' : undefined}
            value={f.multiple ? value : value[0]}
            onChange={(v: string | string[] | undefined) => set(f.fieldId, v == null ? [] : Array.isArray(v) ? v : [v])}
            options={f.options.map((o) => ({ value: o.id, label: o.label }))}
            placeholder={t('veracode.fields.choose', { name: f.name })}
            style={{ minWidth: 200 }}
          />
        ) : f.multiple ? (
          <Select
            mode="tags"
            value={value}
            onChange={(v: string[]) => set(f.fieldId, v)}
            status={f.required && value.length === 0 ? 'warning' : undefined}
            placeholder={t('veracode.fields.type', { name: f.name })}
            style={{ minWidth: 200 }}
          />
        ) : (
          <Input
            value={value[0] ?? ''}
            onChange={(e) => set(f.fieldId, e.target.value.trim() ? [e.target.value] : [])}
            status={f.required && value.length === 0 ? 'warning' : undefined}
            placeholder={t(f.kind === 'USER' ? 'veracode.fields.user' : 'veracode.fields.type', { name: f.name })}
            style={{ width: 200 }}
          />
        );
        return <Space key={f.fieldId} size={4}>{label}{control}</Space>;
      })}
    </Space>
  );
}
