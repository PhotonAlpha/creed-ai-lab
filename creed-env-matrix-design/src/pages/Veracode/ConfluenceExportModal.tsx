import { useEffect, useMemo, useState } from 'react';
import { Alert, Button, Form, Input, Modal, Result, Space, Tag, Typography } from 'antd';
import { KeyOutlined } from '@ant-design/icons';
import { confluenceAuthComplete, veracodeApi } from '../../api/veracode';
import type {
  Analysis, ConfluenceAuth, ConfluenceConnection, ConfluenceCreatedPage, JiraAuth, VeracodeSettings,
} from '../../api/veracode';
import { useI18n } from '../../locales';
import { ConfluenceTokenModal } from './ConfluenceTokenModal';
import { pageIdOf } from './ConfluenceSource';
import { confluenceTokenStore, emptyConfluenceAuth } from './confluenceToken';

const { Text } = Typography;

/** The parent page last exported under: per viewer, a convenience only. */
const PARENT_KEY = 'veracode.confluenceExportParent';
const loadParent = () => {
  try {
    return localStorage.getItem(PARENT_KEY) ?? '';
  } catch {
    return '';
  }
};
const saveParent = (value: string) => {
  try {
    localStorage.setItem(PARENT_KEY, value);
  } catch {
    // not remembered; the export itself is unaffected
  }
};

const distinct = (values: (string | null | undefined)[]) =>
  [...new Set(values.filter((v): v is string => !!v && !!v.trim()))];

/**
 * "Export to Confluence": a child page of the page the user names, laid out as `sample/confluence-template.png`
 * (summary, SCA issues, SAST issues). The title is the configured template; `{project}` and `{version}` default to
 * the reports' and can be changed here, `{timestamp}` is the server's time of creation.
 */
export function ConfluenceExportModal({ open, analysis, settings, auth, jiraVersions, onClose }: {
  open: boolean;
  analysis: Analysis;
  settings: VeracodeSettings;
  auth: JiraAuth;
  jiraVersions: string[];
  onClose: () => void;
}) {
  const { t } = useI18n();
  const [parent, setParent] = useState(loadParent);
  const [project, setProject] = useState('');
  const [version, setVersion] = useState('');
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [created, setCreated] = useState<ConfluenceCreatedPage | null>(null);

  const [token, setToken] = useState(() => confluenceTokenStore.load());
  const confluenceAuth = useMemo<ConfluenceAuth>(
    () => token.auth ?? emptyConfluenceAuth(settings.confluenceCloud ? 'cloud' : 'dataCenter'),
    [token.auth, settings.confluenceCloud],
  );
  const hasToken = confluenceAuthComplete(confluenceAuth);
  const [tokenOpen, setTokenOpen] = useState(false);
  const [connection, setConnection] = useState<ConfluenceConnection | null>(null);

  const defaultProject = distinct(analysis.reports.map((r) => r.appName)).join(', ');
  const defaultVersion = distinct(analysis.reports.map((r) => r.affectedVersion)).join(', ');

  /** Each opening starts clean, with the token as the source tab may have just saved it. */
  useEffect(() => {
    if (!open) return;
    setToken(confluenceTokenStore.load());
    setCreated(null);
    setError(null);
  }, [open]);

  const title = settings.confluenceExportTitle
    .replace('{project}', project.trim() || defaultProject)
    .replace('{version}', version.trim() || defaultVersion)
    .replace('{timestamp}', t('veracode.confluenceExport.timestamp'));

  const exportPage = async () => {
    const parentPageId = pageIdOf(parent);
    if (!/^\d+$/.test(parentPageId)) {
      setError(t('veracode.confluenceExport.parentInvalid'));
      return;
    }
    if (!hasToken) {
      setTokenOpen(true);
      return;
    }
    setBusy(true);
    setError(null);
    try {
      const page = await veracodeApi.exportToConfluence({
        uploadId: analysis.uploadId, parentPageId, project: project.trim(), version: version.trim(), jiraVersions,
      }, auth, confluenceAuth);
      saveParent(parent.trim());
      setCreated(page);
    } catch (e) {
      setError((e as Error).message);
    } finally {
      setBusy(false);
    }
  };

  return (
    <>
      <Modal
        open={open}
        title={t('veracode.confluenceExport.title')}
        width={640}
        onCancel={onClose}
        footer={created ? [<Button key="close" onClick={onClose}>{t('veracode.confluenceExport.close')}</Button>] : [
          <Button key="cancel" onClick={onClose}>{t('veracode.confluenceExport.cancel')}</Button>,
          <Button key="ok" type="primary" loading={busy} disabled={!parent.trim()} onClick={exportPage}>
            {t('veracode.confluenceExport.create')}
          </Button>,
        ]}
      >
        {created ? (
          <Result
            status="success"
            title={t('veracode.confluenceExport.done')}
            subTitle={<Text copyable={{ text: created.url }}>{created.title}</Text>}
            extra={<Button type="primary" href={created.url} target="_blank">{t('veracode.confluenceExport.open')}</Button>}
          >
            {created.attachmentError ? (
              <Alert type="warning" showIcon message={t('veracode.confluenceExport.attachFailed', { error: created.attachmentError })} />
            ) : created.attachments.length > 0 && (
              <Text type="secondary">{t('veracode.confluenceExport.attached', { files: created.attachments.join(', ') })}</Text>
            )}
          </Result>
        ) : (
          <Form layout="vertical">
            <Form.Item label={t('veracode.confluenceExport.parent')} extra={t('veracode.confluenceExport.parentHint')} required>
              <Input value={parent} onChange={(e) => setParent(e.target.value)} placeholder="123456 / …/pages/viewpage.action?pageId=123456" />
            </Form.Item>
            <Space.Compact block>
              <Form.Item label="{project}" style={{ flex: 1, marginInlineEnd: 8 }}>
                <Input value={project} onChange={(e) => setProject(e.target.value)} placeholder={defaultProject} allowClear />
              </Form.Item>
              <Form.Item label="{version}" style={{ flex: 1 }}>
                <Input value={version} onChange={(e) => setVersion(e.target.value)} placeholder={defaultVersion} allowClear />
              </Form.Item>
            </Space.Compact>
            <Form.Item label={t('veracode.confluenceExport.pageTitle')}>
              <Text code>{title}</Text>
            </Form.Item>
            <Form.Item label={t('veracode.confluenceExport.token')}>
              <Button icon={<KeyOutlined />} onClick={() => setTokenOpen(true)}>
                {hasToken ? <Tag color="success" style={{ marginInlineEnd: 0 }}>{t('veracode.confluenceExport.tokenSet')}</Tag>
                  : t('veracode.confluenceExport.tokenNeeded')}
              </Button>
            </Form.Item>
            {error && <Alert type="error" showIcon message={error} />}
          </Form>
        )}
      </Modal>
      <ConfluenceTokenModal
        open={tokenOpen}
        auth={confluenceAuth}
        saved={!!token.auth}
        remember={token.remember}
        baseUrl={settings.confluenceBaseUrl ?? ''}
        mock={settings.mock}
        connection={connection}
        onSave={(value, remember, tested) => {
          confluenceTokenStore.save(value, remember);
          setToken({ auth: value, remember });
          setConnection(tested);
          setTokenOpen(false);
        }}
        onClear={() => {
          confluenceTokenStore.clear();
          setToken({ auth: null, remember: false });
          setConnection(null);
          setTokenOpen(false);
        }}
        onCancel={() => setTokenOpen(false)}
      />
    </>
  );
}
