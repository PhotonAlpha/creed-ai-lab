import { ModalForm, ProFormTextArea } from '@ant-design/pro-components';
import { AutoComplete, Form } from 'antd';
import { useI18n } from '../../../locales';
import { ANY_COUNTRY } from '../../../api/types';
import type { Dimensions } from '../../../api/types';

export interface ParticipantFormValues {
  appSystem: string;
  country: string;
  envInstance: string;
  note?: string | null;
}

interface ParticipantFormModalProps {
  open: boolean;
  initial?: ParticipantFormValues;
  dimensions: Dimensions;
  /**
   * Identifies the row being edited, so the form remounts (and re-reads `initialValues`) when you
   * switch rows — but NOT when the dialog opens and closes. Keying on `open` instead unmounts the
   * dialog at the exact moment it is asked to appear, and it never renders.
   */
  formKey?: string;
  onCancel: () => void;
  onSubmit: (values: ParticipantFormValues) => void;
}

/**
 * Add/edit dialog for one participant — a single, page-level instance driven by `open`, for the
 * same reason the endpoint editor's is: a per-row `trigger` modal loses its open state when the
 * cell re-renders.
 *
 * All three fields are {@link AutoComplete}, not a closed `Select`. A release routinely names a
 * slice the matrix has no endpoints for yet — that gap is what the placeholder nodes on the graph
 * are for — so a value outside the current options has to be typeable.
 */
export function ParticipantFormModal({
  open,
  initial,
  formKey,
  dimensions,
  onCancel,
  onSubmit,
}: ParticipantFormModalProps) {
  const { t } = useI18n();

  const options = (values: string[], extra: string[] = []) =>
    [...new Set([...extra, ...values])].map((value) => ({ label: value, value }));

  const filterOption = (input: string, option?: { value?: string | number }) =>
    String(option?.value ?? '').toLowerCase().includes(input.toLowerCase());

  return (
    <ModalForm<ParticipantFormValues>
      open={open}
      title={initial ? t('links.editParticipant') : t('links.newParticipant')}
      width={520}
      key={formKey ?? 'new'}
      initialValues={initial ?? { country: ANY_COUNTRY }}
      modalProps={{
        destroyOnHidden: true,
        okText: t('common.ok'),
        cancelText: t('common.cancel'),
        onCancel,
      }}
      onOpenChange={(next) => {
        if (!next) onCancel();
      }}
      onFinish={async (values) => {
        onSubmit(values);
        return true;
      }}
    >
      <Form.Item
        name="appSystem"
        label={t('column.appSystem')}
        rules={[{ required: true, message: t('config.validation.required') }]}
      >
        <AutoComplete
          options={options(dimensions.appSystem)}
          placeholder={t('links.appPlaceholder')}
          filterOption={filterOption}
        />
      </Form.Item>

      <Form.Item
        name="country"
        label={t('column.country')}
        rules={[{ required: true, message: t('config.validation.required') }]}
        // `*` is not a country in /dimensions — it is the sentinel for "not country-specific",
        // so it is offered as an option here rather than being something you have to know to type.
        extra={`${ANY_COUNTRY} = ${t('links.anyCountry')}`}
      >
        <AutoComplete
          options={options(dimensions.country, [ANY_COUNTRY])}
          placeholder={t('links.countryPlaceholder')}
          filterOption={filterOption}
        />
      </Form.Item>

      <Form.Item
        name="envInstance"
        label={t('column.envInstance')}
        rules={[{ required: true, message: t('config.validation.required') }]}
      >
        <AutoComplete
          options={options(dimensions.envInstance)}
          placeholder={t('links.envPlaceholder')}
          filterOption={filterOption}
        />
      </Form.Item>

      <ProFormTextArea name="note" label={t('links.note')} fieldProps={{ rows: 2, maxLength: 512 }} />
    </ModalForm>
  );
}
