import { useEffect, useState } from 'react';
import { Alert, Button, Checkbox, Form, Input, Modal, Segmented, Space, Typography } from 'antd';
import { ApiOutlined } from '@ant-design/icons';
import { confluenceAuthComplete, veracodeApi } from '../../api/veracode';
import type { ConfluenceAuth, ConfluenceConnection, JiraDeployment } from '../../api/veracode';
import { useI18n } from '../../locales';

const { Paragraph } = Typography;

interface Props {
  open: boolean;
  auth: ConfluenceAuth;
  saved: boolean;
  remember: boolean;
  baseUrl?: string | null;
  mock?: boolean;
  connection: ConfluenceConnection | null;
  onSave: (auth: ConfluenceAuth, remember: boolean, connection: ConfluenceConnection | null) => void;
  onClear: () => void;
  onCancel: () => void;
}

const trimmed = (auth: ConfluenceAuth): ConfluenceAuth => ({
  deployment: auth.deployment,
  token: auth.token.trim(),
  email: auth.deployment === 'cloud' ? auth.email.trim() : '',
});

/**
 * The user's own Confluence sign-in, the attachments are listed and downloaded with it. Like the Jira dialog:
 * Cloud = e-mail + API token, Data Center = personal access token; tested through the backend.
 */
export function ConfluenceTokenModal({ open, auth, saved, remember, baseUrl, mock, connection, onSave, onClear, onCancel }: Props) {
  const { t } = useI18n();
  const [draft, setDraft] = useState(auth);
  const [keep, setKeep] = useState(remember);
  const [testing, setTesting] = useState(false);
  const [result, setResult] = useState<ConfluenceConnection | null>(connection);
  const [error, setError] = useState<string | null>(null);

  useEffect(() => {
    if (open) {
      setDraft(auth);
      setKeep(remember);
      setResult(connection);
      setError(null);
    }
  }, [open, auth, remember, connection]);

  const cloud = draft.deployment === 'cloud';
  const complete = confluenceAuthComplete(draft);

  const change = (patch: Partial<ConfluenceAuth>) => {
    setDraft((d) => ({ ...d, ...patch }));
    setResult(null);
    setError(null);
  };

  const test = async () => {
    setTesting(true);
    setError(null);
    try {
      setResult(await veracodeApi.testConfluence(trimmed(draft)));
    } catch (e) {
      setResult(null);
      setError((e as Error).message);
    } finally {
      setTesting(false);
    }
  };

  return (
    <Modal
      open={open}
      title={t('veracode.confluence.token.title')}
      onCancel={onCancel}
      destroyOnHidden
      footer={(
        <Space>
          {saved && <Button danger onClick={onClear}>{t('veracode.token.clear')}</Button>}
          <Button onClick={onCancel}>{t('veracode.token.cancel')}</Button>
          <Button type="primary" disabled={!complete} onClick={() => onSave(trimmed(draft), keep, result)}>
            {t('veracode.token.save')}
          </Button>
        </Space>
      )}
    >
      <Segmented<JiraDeployment>
        block
        value={draft.deployment}
        onChange={(deployment) => change({ deployment })}
        options={[
          { value: 'cloud', label: t('veracode.confluence.token.cloud') },
          { value: 'dataCenter', label: t('veracode.confluence.token.dataCenter') },
        ]}
        style={{ marginBottom: 12 }}
      />
      <Paragraph type="secondary">
        {t(cloud ? 'veracode.confluence.token.cloudHint' : 'veracode.confluence.token.hint', { url: baseUrl ?? '' })}
      </Paragraph>
      {mock && <Paragraph type="secondary">{t('veracode.confluence.token.mockHint')}</Paragraph>}
      <Form layout="vertical" component="div">
        {cloud && (
          <Form.Item label={t('veracode.token.email')} style={{ marginBottom: 12 }}>
            <Input
              type="email"
              value={draft.email}
              onChange={(e) => change({ email: e.target.value })}
              placeholder={t('veracode.token.emailPlaceholder')}
              autoComplete="username"
            />
          </Form.Item>
        )}
        <Form.Item label={t(cloud ? 'veracode.token.apiToken' : 'veracode.token.pat')} style={{ marginBottom: 0 }}>
          <Space.Compact style={{ width: '100%' }}>
            <Input.Password
              value={draft.token}
              onChange={(e) => change({ token: e.target.value })}
              onPressEnter={() => complete && void test()}
              placeholder={t(cloud ? 'veracode.token.cloudPlaceholder' : 'veracode.confluence.token.placeholder')}
              autoComplete="off"
            />
            <Button icon={<ApiOutlined />} loading={testing} disabled={!complete} onClick={() => void test()}>
              {t('veracode.token.test')}
            </Button>
          </Space.Compact>
        </Form.Item>
      </Form>
      <Checkbox checked={keep} onChange={(e) => setKeep(e.target.checked)} style={{ marginTop: 12 }}>
        {t('veracode.token.remember')}
      </Checkbox>
      {result && (
        <Alert
          style={{ marginTop: 16 }}
          showIcon
          type={result.ok ? 'success' : 'error'}
          message={result.ok ? t('veracode.confluence.token.ok', { user: result.user ?? '' }) : t('veracode.token.failed')}
          description={result.ok ? undefined : result.message}
        />
      )}
      {error && <Alert style={{ marginTop: 16 }} showIcon type="error" message={t('veracode.token.failed')} description={error} />}
    </Modal>
  );
}
