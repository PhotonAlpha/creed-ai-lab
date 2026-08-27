import { ModalForm, ProFormSelect, ProFormTextArea } from '@ant-design/pro-components';
import { AutoComplete, Form } from 'antd';
import { useI18n } from '../../locales';
import type { LinkDirection } from '../../api/types';

export interface LinkFormValues {
  sourceApp: string;
  targetApp: string;
  direction: LinkDirection;
  note?: string | null;
}

interface LinkFormModalProps {
  open: boolean;
  /** Row being edited, or `undefined` when adding. */
  initial?: LinkFormValues;
  /** App systems that already have endpoints — a convenience list, not a closed set. */
  appSystems: string[];
  onCancel: () => void;
  onSubmit: (values: LinkFormValues) => void;
}

/**
 * Add/edit dialog for one link — a single, page-level instance driven by `open`, for the same
 * reason the endpoint editor is: a per-row `trigger` modal loses its open state when the cell
 * re-renders.
 *
 * The two app-system fields are {@link AutoComplete}, not a closed `Select`. The topology routinely
 * names systems the matrix has no endpoints for yet — that gap is what the placeholder nodes on the
 * graph are for — so typing a name that is not in the list has to be allowed.
 */
export function LinkFormModal({
  open,
  initial,
  appSystems,
  onCancel,
  onSubmit,
}: LinkFormModalProps) {
  const { t } = useI18n();
  const options = appSystems.map((value) => ({ label: value, value }));

  return (
    <ModalForm<LinkFormValues>
      open={open}
      title={initial ? t('links.editLink') : t('links.newLink')}
      width={520}
      // Remounts the fields whenever the dialog opens, so a second Edit click never shows the
      // previous row's values.
      key={open ? 'open' : 'closed'}
      initialValues={initial ?? { direction: 'ONE_WAY' }}
      modalProps={{ destroyOnHidden: true, okText: t('common.ok'), cancelText: t('common.cancel'), onCancel }}
      onFinish={async (values) => {
        onSubmit(values);
        return true;
      }}
    >
      <Form.Item
        name="sourceApp"
        label={t('links.sourceApp')}
        rules={[{ required: true, message: t('config.validation.required') }]}
      >
        <AutoComplete
          options={options}
          placeholder={t('links.appPlaceholder')}
          filterOption={(input, option) =>
            String(option?.value ?? '').toLowerCase().includes(input.toLowerCase())
          }
        />
      </Form.Item>

      <ProFormSelect<LinkDirection>
        name="direction"
        label={t('links.direction')}
        rules={[{ required: true, message: t('config.validation.required') }]}
        options={[
          { label: `→ ${t('links.direction.ONE_WAY')}`, value: 'ONE_WAY' },
          { label: `↔ ${t('links.direction.BIDIRECTIONAL')}`, value: 'BIDIRECTIONAL' },
        ]}
      />

      <Form.Item
        name="targetApp"
        label={t('links.targetApp')}
        dependencies={['sourceApp']}
        rules={[
          { required: true, message: t('config.validation.required') },
          ({ getFieldValue }) => ({
            validator: (_, value) =>
              value && value === getFieldValue('sourceApp')
                ? Promise.reject(new Error(t('links.selfLink')))
                : Promise.resolve(),
          }),
        ]}
      >
        <AutoComplete
          options={options}
          placeholder={t('links.appPlaceholder')}
          filterOption={(input, option) =>
            String(option?.value ?? '').toLowerCase().includes(input.toLowerCase())
          }
        />
      </Form.Item>

      <ProFormTextArea name="note" label={t('links.note')} fieldProps={{ rows: 2, maxLength: 512 }} />
    </ModalForm>
  );
}
