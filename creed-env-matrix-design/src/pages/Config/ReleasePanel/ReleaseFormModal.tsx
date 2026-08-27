import { ModalForm, ProFormSelect, ProFormText, ProFormTextArea } from '@ant-design/pro-components';
import { AutoComplete, Form } from 'antd';
import { useI18n } from '../../../locales';
import type { Dimensions, ReleaseStatus } from '../../../api/types';

export interface ReleaseFormValues {
  name: string;
  tier: string;
  status: ReleaseStatus;
  note?: string | null;
}

interface ReleaseFormModalProps {
  open: boolean;
  initial?: ReleaseFormValues;
  dimensions: Dimensions;
  /**
   * Identifies the row being edited, so the form remounts (and re-reads `initialValues`) when you
   * switch rows — but NOT when the dialog opens and closes. Keying on `open` instead unmounts the
   * dialog at the exact moment it is asked to appear, and it never renders.
   */
  formKey?: string;
  onCancel: () => void;
  onSubmit: (values: ReleaseFormValues) => void;
}

/** Add/edit dialog for a release's own fields. Participants and links are saved separately. */
export function ReleaseFormModal({
  open,
  initial,
  formKey,
  dimensions,
  onCancel,
  onSubmit,
}: ReleaseFormModalProps) {
  const { t } = useI18n();

  return (
    <ModalForm<ReleaseFormValues>
      open={open}
      title={initial ? t('links.editRelease') : t('links.newRelease')}
      width={480}
      key={formKey ?? 'new'}
      initialValues={initial ?? { status: 'DRAFT' }}
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
      <ProFormText
        name="name"
        label={t('links.releaseName')}
        rules={[{ required: true, message: t('config.validation.required') }]}
        fieldProps={{ maxLength: 64 }}
      />

      <Form.Item
        name="tier"
        label={t('links.releaseTier')}
        rules={[{ required: true, message: t('config.validation.required') }]}
      >
        <AutoComplete
          options={dimensions.tier.map((value) => ({ label: value, value }))}
          placeholder={t('filter.placeholder')}
        />
      </Form.Item>

      <ProFormSelect<ReleaseStatus>
        name="status"
        label={t('links.releaseStatus')}
        rules={[{ required: true, message: t('config.validation.required') }]}
        options={(['DRAFT', 'ACTIVE', 'ARCHIVED'] as const).map((value) => ({
          label: t(`links.status.${value}`),
          value,
        }))}
      />

      <ProFormTextArea name="note" label={t('links.note')} fieldProps={{ rows: 2, maxLength: 512 }} />
    </ModalForm>
  );
}
