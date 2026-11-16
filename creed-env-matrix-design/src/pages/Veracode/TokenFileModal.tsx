import { useEffect, useState } from 'react';
import { Alert, App, Checkbox, Descriptions, Input, Modal, Radio, Space, Tooltip, Typography, Upload } from 'antd';
import { FileProtectOutlined, InboxOutlined } from '@ant-design/icons';
import { saveBlob } from '../../api/veracode';
import type { ConfluenceAuth, JiraAuth } from '../../api/veracode';
import { useI18n } from '../../locales';
import type { MessageKey } from '../../locales';
import {
  TokenFileError, canEncrypt, inspectTokenFile, readTokenFile, writeTokenFile,
} from './tokenFile';
import type { TokenFileContent } from './tokenFile';

const { Text } = Typography;

/** `••••abcd`: enough to tell two tokens apart, never the token. */
const masked = (token: string) => (token.length > 4 ? `••••${token.slice(-4)}` : '••••');

/**
 * Saves the Jira and Confluence tokens to a file, or restores them from one (asked for, 2026-11-16: browser data gets
 * cleared). See `tokenFile.ts` for the format and the encryption; this is the dialog around it.
 */
export function TokenFileModal({ mode, jira, confluence, onImport, onClose }: {
  /** `null` = closed. */
  mode: 'export' | 'import' | null;
  jira: { auth: JiraAuth; remember: boolean } | null;
  confluence: { auth: ConfluenceAuth; remember: boolean } | null;
  onImport: (content: TokenFileContent) => void;
  onClose: () => void;
}) {
  const { t } = useI18n();
  const { message } = App.useApp();
  const secure = canEncrypt();
  /** The user's choice (asked for, 2026-11-16); encrypting is preselected wherever the browser can. */
  const [encrypt, setEncrypt] = useState(secure);
  const [password, setPassword] = useState('');
  const [confirm, setConfirm] = useState('');
  const [file, setFile] = useState<{ name: string; text: string; encrypted: boolean; exportedAt: string } | null>(null);
  const [remember, setRemember] = useState(true);
  const [error, setError] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);

  useEffect(() => {
    if (!mode) return;
    setEncrypt(secure);
    setPassword('');
    setConfirm('');
    setFile(null);
    setRemember(true);
    setError(null);
  }, [mode, secure]);

  const reason = (e: unknown) => (e instanceof TokenFileError
    ? t(`veracode.tokenFile.error.${e.reason}` as MessageKey)
    : (e as Error).message);

  const describe = (kind: 'jira' | 'confluence', value: { auth: JiraAuth | ConfluenceAuth } | null) => (value ? (
    <Space size={6} wrap>
      <Text>{t(kind === 'jira' ? `veracode.token.${value.auth.deployment}` : `veracode.confluence.token.${value.auth.deployment}`)}</Text>
      {value.auth.email && <Text type="secondary">{value.auth.email}</Text>}
      <Text code>{masked(value.auth.token)}</Text>
      {'issueType' in value.auth && value.auth.issueType && <Text type="secondary">{value.auth.issueType}</Text>}
    </Space>
  ) : <Text type="secondary">{t('veracode.token.unset')}</Text>);

  const doExport = async () => {
    setBusy(true);
    try {
      const text = await writeTokenFile({ jira, confluence }, encrypt ? password : '');
      const stamp = new Date().toISOString().slice(0, 10).replace(/-/g, '');
      saveBlob({ blob: new Blob([text], { type: 'application/json' }), filename: `veracode-tokens-${stamp}.json` });
      message.success(t('veracode.tokenFile.exported'));
      onClose();
    } catch (e) {
      setError(reason(e));
    } finally {
      setBusy(false);
    }
  };

  const doImport = async () => {
    if (!file) return;
    setBusy(true);
    try {
      const content = await readTokenFile(file.text, password);
      if (!content.jira && !content.confluence) {
        setError(t('veracode.tokenFile.error.empty'));
        return;
      }
      onImport({
        jira: content.jira && { ...content.jira, remember },
        confluence: content.confluence && { ...content.confluence, remember },
      });
      message.success(t('veracode.tokenFile.imported', {
        what: [content.jira && 'Jira', content.confluence && 'Confluence'].filter(Boolean).join(' + '),
      }));
      onClose();
    } catch (e) {
      setError(reason(e));
    } finally {
      setBusy(false);
    }
  };

  const mismatch = mode === 'export' && encrypt && !!confirm && password !== confirm;
  const okDisabled = mode === 'export'
    ? (!jira && !confluence) || (encrypt && (!password || password !== confirm))
    : !file || (file.encrypted && !password);

  return (
    <Modal
      open={mode !== null}
      title={<Space><FileProtectOutlined />{t(mode === 'import' ? 'veracode.tokenFile.importTitle' : 'veracode.tokenFile.exportTitle')}</Space>}
      okText={t(mode === 'import' ? 'veracode.tokenFile.import' : 'veracode.tokenFile.download')}
      okButtonProps={{ disabled: okDisabled, loading: busy }}
      onOk={() => void (mode === 'import' ? doImport() : doExport())}
      onCancel={onClose}
      destroyOnHidden
    >
      {mode === 'export' ? (
        <Space direction="vertical" style={{ width: '100%' }} size="middle">
          <Text type="secondary">{t('veracode.tokenFile.exportHint')}</Text>
          <Descriptions size="small" column={1} bordered items={[
            { key: 'jira', label: 'Jira', children: describe('jira', jira) },
            { key: 'confluence', label: 'Confluence', children: describe('confluence', confluence) },
          ]} />
          <Radio.Group value={encrypt} onChange={(e) => { setEncrypt(e.target.value as boolean); setError(null); }}>
            <Tooltip title={secure ? undefined : t('veracode.tokenFile.insecurePlain')}>
              <Radio value disabled={!secure}>{t('veracode.tokenFile.encrypt')}</Radio>
            </Tooltip>
            <Radio value={false}>{t('veracode.tokenFile.noEncrypt')}</Radio>
          </Radio.Group>
          {encrypt ? (
            <Space direction="vertical" style={{ width: '100%' }} size={8}>
              <Input.Password placeholder={t('veracode.tokenFile.passwordRequired')} value={password} autoComplete="new-password" autoFocus
                              onChange={(e) => { setPassword(e.target.value); setError(null); }} />
              <Input.Password placeholder={t('veracode.tokenFile.confirm')} value={confirm} autoComplete="new-password"
                              status={mismatch ? 'error' : undefined} disabled={!password}
                              onChange={(e) => setConfirm(e.target.value)} />
              {mismatch && <Text type="danger">{t('veracode.tokenFile.mismatch')}</Text>}
              <Alert type="info" showIcon message={t('veracode.tokenFile.encryptedHint')} />
            </Space>
          ) : (
            <Alert type="warning" showIcon
                   message={t(secure ? 'veracode.tokenFile.plainWarning' : 'veracode.tokenFile.insecurePlain')} />
          )}
        </Space>
      ) : (
        <Space direction="vertical" style={{ width: '100%' }} size="middle">
          <Text type="secondary">{t('veracode.tokenFile.importHint')}</Text>
          <Upload.Dragger
            accept=".json,application/json"
            maxCount={1}
            showUploadList={false}
            beforeUpload={(f) => {
              void f.text().then((text) => {
                try {
                  setFile({ name: f.name, text, ...inspectTokenFile(text) });
                  setError(null);
                } catch (e) {
                  setFile(null);
                  setError(reason(e));
                }
              });
              return false;
            }}
          >
            <p style={{ margin: 0 }}><InboxOutlined style={{ fontSize: 32 }} /></p>
            <p style={{ margin: '8px 0 0' }}>{file ? file.name : t('veracode.tokenFile.drop')}</p>
            {file && (
              <Text type="secondary">
                {t(file.encrypted ? 'veracode.tokenFile.fileEncrypted' : 'veracode.tokenFile.filePlain', {
                  date: new Date(file.exportedAt).toLocaleString(),
                })}
              </Text>
            )}
          </Upload.Dragger>
          {file?.encrypted && (
            <Input.Password placeholder={t('veracode.tokenFile.passwordRequired')} value={password} autoFocus
                            onChange={(e) => { setPassword(e.target.value); setError(null); }}
                            onPressEnter={() => void doImport()} />
          )}
          <Checkbox checked={remember} onChange={(e) => setRemember(e.target.checked)}>
            {t('veracode.tokenFile.remember')}
          </Checkbox>
        </Space>
      )}
      {error && <Alert type="error" showIcon style={{ marginTop: 12 }} message={error} />}
    </Modal>
  );
}
