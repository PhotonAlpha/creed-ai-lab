import { useEffect, useState } from 'react';
import { Alert, Button, Checkbox, Input, Modal, Space, Typography } from 'antd';
import { ApiOutlined } from '@ant-design/icons';
import { veracodeApi } from '../../api/veracode';
import type { JiraConnection } from '../../api/veracode';
import { useI18n } from '../../locales';

const { Paragraph } = Typography;

interface Props {
  open: boolean;
  token: string;
  remember: boolean;
  jiraBaseUrl?: string;
  mock?: boolean;
  /** The last test of the saved token, shown when the dialog opens. */
  connection: JiraConnection | null;
  onSave: (token: string, remember: boolean, connection: JiraConnection | null) => void;
  onClear: () => void;
  onCancel: () => void;
}

/**
 * The user's own Jira access token: issues are created with it, as that user. Testing goes through the
 * backend (`POST /jira/connection`) — the browser cannot call Jira itself (CORS, private network).
 */
export function JiraTokenModal({ open, token, remember, jiraBaseUrl, mock, connection, onSave, onClear, onCancel }: Props) {
  const { t } = useI18n();
  const [draft, setDraft] = useState(token);
  const [keep, setKeep] = useState(remember);
  const [testing, setTesting] = useState(false);
  const [result, setResult] = useState<JiraConnection | null>(connection);
  const [error, setError] = useState<string | null>(null);

  useEffect(() => {
    if (open) {
      setDraft(token);
      setKeep(remember);
      setResult(connection);
      setError(null);
    }
  }, [open, token, remember, connection]);

  const test = async () => {
    setTesting(true);
    setError(null);
    try {
      setResult(await veracodeApi.testConnection(draft.trim()));
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
      title={t('veracode.token.title')}
      onCancel={onCancel}
      destroyOnHidden
      footer={(
        <Space>
          {token && <Button danger onClick={onClear}>{t('veracode.token.clear')}</Button>}
          <Button onClick={onCancel}>{t('veracode.token.cancel')}</Button>
          <Button type="primary" disabled={!draft.trim()} onClick={() => onSave(draft.trim(), keep, result)}>
            {t('veracode.token.save')}
          </Button>
        </Space>
      )}
    >
      <Paragraph type="secondary">{t('veracode.token.hint', { url: jiraBaseUrl ?? '' })}</Paragraph>
      {mock && <Paragraph type="secondary">{t('veracode.token.mockHint')}</Paragraph>}
      <Space.Compact style={{ width: '100%' }}>
        <Input.Password
          value={draft}
          // a result only describes the token it was made with
          onChange={(e) => { setDraft(e.target.value); setResult(null); setError(null); }}
          onPressEnter={() => draft.trim() && void test()}
          placeholder={t('veracode.token.placeholder')}
          autoComplete="off"
        />
        <Button icon={<ApiOutlined />} loading={testing} disabled={!draft.trim()} onClick={test}>
          {t('veracode.token.test')}
        </Button>
      </Space.Compact>
      <Checkbox checked={keep} onChange={(e) => setKeep(e.target.checked)} style={{ marginTop: 12 }}>
        {t('veracode.token.remember')}
      </Checkbox>
      {result && (
        <Alert
          style={{ marginTop: 16 }}
          showIcon
          type={result.ok ? 'success' : 'error'}
          message={result.ok
            ? t('veracode.token.ok', { issueType: result.issueType, project: result.projectKey, versions: result.versions })
            : result.tokenAccepted
              ? t('veracode.token.noProject', { issueType: result.issueType, project: result.projectKey })
              : t('veracode.token.rejected', { status: result.status })}
          description={result.message}
        />
      )}
      {error && <Alert style={{ marginTop: 16 }} showIcon type="error" message={t('veracode.token.failed')} description={error} />}
    </Modal>
  );
}
