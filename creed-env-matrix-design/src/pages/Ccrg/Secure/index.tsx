import { useState } from 'react';
import { PageContainer, ProCard } from '@ant-design/pro-components';
import { App, Button, Form, Input, Space, Typography } from 'antd';
import { UnlockOutlined } from '@ant-design/icons';
import { ccrgApi } from '../../../api/ccrg';
import { useI18n } from '../../../locales';
import { errorText } from '../shared';

/**
 * System secure management: decrypt a value with its hex symmetric key (AES, IV = the first 16 bytes
 * of the Base64 payload — `DecryptionController`). The key never leaves this request; nothing is
 * stored. Migrated from `ccrg-tool-ui/src/views/Secure`.
 */

interface SecureValues {
  symmetricKey: string;
  encryptedData: string;
}

export function SecurePage() {
  const { t } = useI18n();
  const { message } = App.useApp();
  const [busy, setBusy] = useState(false);
  const [plain, setPlain] = useState<string>();

  const submit = async ({ symmetricKey, encryptedData }: SecureValues) => {
    setBusy(true);
    setPlain(undefined);
    try {
      setPlain(await ccrgApi.decrypt(symmetricKey.trim(), encryptedData.trim()));
      message.success(t('ccrg.secure.done'));
    } catch (e) {
      message.error(errorText(e));
    } finally {
      setBusy(false);
    }
  };

  const required = [{ required: true, message: t('ccrg.required') }];
  return (
    <PageContainer title={t('nav.ccrg.secure')}>
      <Space direction="vertical" size="middle" style={{ width: '100%' }}>
        <ProCard bordered>
          <Form<SecureValues> layout="vertical" style={{ maxWidth: 600 }} onFinish={submit} disabled={busy}>
            <Form.Item name="symmetricKey" label={t('ccrg.secure.key')} rules={required}>
              <Input.TextArea autoSize spellCheck={false} />
            </Form.Item>
            <Form.Item name="encryptedData" label={t('ccrg.secure.data')} rules={required}>
              <Input.TextArea rows={10} spellCheck={false} />
            </Form.Item>
            <Button type="primary" htmlType="submit" loading={busy} icon={<UnlockOutlined />}>
              {t('ccrg.submit')}
            </Button>
          </Form>
        </ProCard>
        {plain != null && (
          <ProCard bordered title={t('ccrg.secure.result')}>
            <Typography.Paragraph copyable={{ text: plain }} style={{ whiteSpace: 'pre-wrap', marginBottom: 0 }}>
              <Typography.Text code>{plain}</Typography.Text>
            </Typography.Paragraph>
          </ProCard>
        )}
      </Space>
    </PageContainer>
  );
}
