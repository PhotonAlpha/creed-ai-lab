import { ModalForm, ProFormSelect, ProFormTextArea } from '@ant-design/pro-components';
import { useI18n } from '../../../locales';
import type { LinkDirection } from '../../../api/types';
import { sliceOf } from './types';
import type { ParticipantRow } from './types';

export interface ConnectionFormValues {
  sourceKey: string;
  targetKey: string;
  direction: LinkDirection;
  note?: string | null;
}

interface ConnectionFormModalProps {
  open: boolean;
  initial?: ConnectionFormValues;
  /** This release's participants — the only things a connection may name. */
  participants: ParticipantRow[];
  /**
   * Identifies the row being edited, so the form remounts (and re-reads `initialValues`) when you
   * switch rows — but NOT when the dialog opens and closes. Keying on `open` instead unmounts the
   * dialog at the exact moment it is asked to appear, and it never renders.
   */
  formKey?: string;
  onCancel: () => void;
  onSubmit: (values: ConnectionFormValues) => void;
}

/**
 * Add/edit dialog for one connection.
 *
 * Both ends are closed `Select`s over the release's own participants — unlike the participant form,
 * a free-text end is not allowed. A connection has to point at something that exists, which is why
 * the editor makes you add the participant first.
 */
export function ConnectionFormModal({
  open,
  initial,
  formKey,
  participants,
  onCancel,
  onSubmit,
}: ConnectionFormModalProps) {
  const { t } = useI18n();

  const options = participants.map((row) => ({ label: sliceOf(row), value: row._key }));

  return (
    <ModalForm<ConnectionFormValues>
      open={open}
      title={initial ? t('links.editConnection') : t('links.newConnection')}
      width={520}
      key={formKey ?? 'new'}
      initialValues={initial ?? { direction: 'ONE_WAY' }}
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
      <ProFormSelect
        name="sourceKey"
        label={t('links.sourceApp')}
        options={options}
        rules={[{ required: true, message: t('config.validation.required') }]}
        showSearch
      />

      <ProFormSelect<LinkDirection>
        name="direction"
        label={t('links.direction')}
        rules={[{ required: true, message: t('config.validation.required') }]}
        options={[
          { label: `→ ${t('links.direction.ONE_WAY')}`, value: 'ONE_WAY' },
          { label: `↔ ${t('links.direction.BIDIRECTIONAL')}`, value: 'BIDIRECTIONAL' },
        ]}
      />

      <ProFormSelect
        name="targetKey"
        label={t('links.targetApp')}
        options={options}
        showSearch
        dependencies={['sourceKey']}
        rules={[
          { required: true, message: t('config.validation.required') },
          ({ getFieldValue }) => ({
            validator: (_, value) =>
              value && value === getFieldValue('sourceKey')
                ? Promise.reject(new Error(t('links.selfLink')))
                : Promise.resolve(),
          }),
        ]}
      />

      <ProFormTextArea name="note" label={t('links.note')} fieldProps={{ rows: 2, maxLength: 512 }} />
    </ModalForm>
  );
}
