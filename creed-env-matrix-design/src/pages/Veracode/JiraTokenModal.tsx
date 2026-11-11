import { useEffect, useState } from 'react';
import { Alert, Button, Checkbox, Flex, Form, Input, Modal, Segmented, Select, Space, Steps, Tag, Typography } from 'antd';
import type { StepsProps } from 'antd';
import { ApiOutlined } from '@ant-design/icons';
import { jiraAuthComplete, veracodeApi } from '../../api/veracode';
import type { JiraAuth, JiraConnection, JiraDeployment, JiraProblem } from '../../api/veracode';
import { useI18n } from '../../locales';
import { TokenHelp, tokenPageUrl } from './tokenHelp';

const { Paragraph } = Typography;

interface Props {
  open: boolean;
  /** The saved sign-in, or an empty one of the suggested kind. */
  auth: JiraAuth;
  /** Something is saved — offers "clear". */
  saved: boolean;
  remember: boolean;
  jiraBaseUrl?: string;
  mock?: boolean;
  /** The last test of the saved token, shown when the dialog opens. */
  connection: JiraConnection | null;
  onSave: (auth: JiraAuth, remember: boolean, connection: JiraConnection | null) => void;
  onClear: () => void;
  onCancel: () => void;
}

const trimmed = (auth: JiraAuth): JiraAuth => ({
  ...auth,
  deployment: auth.deployment,
  token: auth.token.trim(),
  // Data Center has no e-mail: none is kept, so a switched-back draft cannot send one
  email: auth.deployment === 'cloud' ? auth.email.trim() : '',
});

/** The checks in the order the backend makes them; a failed one stops the rest. */
const CHECKS: { key: string; fails: JiraProblem[] }[] = [
  { key: 'token', fails: ['UNREACHABLE', 'TOKEN_REJECTED'] },
  { key: 'issueType', fails: ['PROJECT', 'ISSUE_TYPE'] },
  { key: 'affectsVersion', fails: ['AFFECTS_VERSION'] },
  { key: 'versions', fails: ['NO_VERSIONS'] },
];

/**
 * The user's own Jira sign-in: issues are created with it, as that user. Jira Cloud takes the Atlassian account
 * e-mail with an API token, Data Center a personal access token alone — the user says which. Testing goes
 * through the backend (`POST /jira/connection`) — the browser cannot call Jira itself (CORS, private network).
 */
export function JiraTokenModal({ open, auth, saved, remember, jiraBaseUrl, mock, connection, onSave, onClear, onCancel }: Props) {
  const { t } = useI18n();
  const [draft, setDraft] = useState(auth);
  const [keep, setKeep] = useState(remember);
  const [testing, setTesting] = useState(false);
  const [result, setResult] = useState<JiraConnection | null>(connection);
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
  const complete = jiraAuthComplete(draft);

  /** A result only describes the sign-in it was made with. */
  const change = (patch: Partial<JiraAuth>) => {
    setDraft((d) => ({ ...d, ...patch }));
    setResult(null);
    setError(null);
  };

  const test = async (auth: JiraAuth = draft) => {
    setTesting(true);
    setError(null);
    try {
      setResult(await veracodeApi.testConnection(trimmed(auth)));
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
      // test on the left of the actions: it checks what is typed, before saving it
      footer={(
        <Flex justify="space-between" wrap gap={8}>
          <span>{saved && <Button danger onClick={onClear}>{t('veracode.token.clear')}</Button>}</span>
          <Space>
            <Button icon={<ApiOutlined />} loading={testing} disabled={!complete} onClick={() => void test()}>
              {t('veracode.token.test')}
            </Button>
            <Button onClick={onCancel}>{t('veracode.token.cancel')}</Button>
            <Button type="primary" disabled={!complete} onClick={() => onSave(trimmed(draft), keep, result)}>
              {t('veracode.token.save')}
            </Button>
          </Space>
        </Flex>
      )}
    >
      <Form layout="vertical" component="div">
        <Form.Item label={t('veracode.token.deployment')} style={{ marginBottom: 16 }}>
          <Segmented<JiraDeployment>
            block
            value={draft.deployment}
            onChange={(deployment) => change({ deployment })}
            options={[
              { value: 'cloud', label: t('veracode.token.cloud') },
              { value: 'dataCenter', label: t('veracode.token.dataCenter') },
            ]}
          />
        </Form.Item>
        <TokenHelp
          url={tokenPageUrl('jira', draft.deployment, jiraBaseUrl)}
          steps={t(cloud ? 'veracode.tokenHelp.cloud' : 'veracode.tokenHelp.jiraDataCenter', { url: jiraBaseUrl ?? '' })}
        />
        {mock && <Paragraph type="secondary">{t('veracode.token.mockHint')}</Paragraph>}
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
        <Form.Item
          label={t(cloud ? 'veracode.token.apiToken' : 'veracode.token.pat')}
          extra={t('veracode.token.privacy')}
          style={{ marginBottom: 8 }}
        >
          <Input.Password
            value={draft.token}
            onChange={(e) => change({ token: e.target.value })}
            onPressEnter={() => complete && void test()}
            placeholder={t(cloud ? 'veracode.token.cloudPlaceholder' : 'veracode.token.placeholder')}
            autoComplete="off"
          />
        </Form.Item>
        <Checkbox checked={keep} onChange={(e) => setKeep(e.target.checked)}>
          {t('veracode.token.remember')}
        </Checkbox>
      </Form>
      {result && (
        <>
          <Steps
            direction="vertical"
            size="small"
            style={{ marginTop: 16 }}
            items={checkItems(result, t)}
          />
          {result.issueTypes.length > 0 && (
            <Form layout="vertical" component="div">
              <Form.Item label={t('veracode.check.chooseType')} extra={t('veracode.check.chooseTypeHint')} style={{ marginBottom: 8 }}>
                <Select
                  value={result.issueType}
                  loading={testing}
                  // a new type is tested at once: the checks above then describe it
                  onChange={(issueType: string) => {
                    const next = { ...draft, issueType };
                    setDraft(next);
                    void test(next);
                  }}
                  options={result.issueTypes.map((o) => ({
                    value: o.name,
                    label: (
                      <Space size={4}>
                        {o.name}
                        {o.affectsVersion
                          ? <Tag color="success">{t('veracode.check.typeVersions', { count: o.versions })}</Tag>
                          : <Tag>{t('veracode.check.typeNoVersions')}</Tag>}
                      </Space>
                    ),
                  }))}
                />
              </Form.Item>
            </Form>
          )}
          {result.message && <Paragraph type="secondary" style={{ marginBottom: 0 }}>{result.message}</Paragraph>}
        </>
      )}
      {error && <Alert style={{ marginTop: 16 }} showIcon type="error" message={t('veracode.token.failed')} description={error} />}
    </Modal>
  );
}

type Translate = ReturnType<typeof useI18n>['t'];

/**
 * One step per check: passed ones are done, the failed one says what to fix in Jira, later ones wait — so a
 * missing Affects Version/s reads as that, not as "cannot create".
 */
function checkItems(result: JiraConnection, t: Translate): StepsProps['items'] {
  const failed = result.problem ? CHECKS.findIndex((c) => c.fails.includes(result.problem!)) : -1;
  const vars = { issueType: result.issueType, project: result.projectKey, versions: result.versions, status: result.status };
  return CHECKS.map((c, i) => {
    const status = failed === -1 || i < failed ? 'finish' : i === failed ? 'error' : 'wait';
    return {
      status,
      title: t(`veracode.check.${c.key}` as 'veracode.check.token', vars),
      description: status === 'error'
        ? t(`veracode.check.fix.${result.problem}` as 'veracode.check.fix.TOKEN_REJECTED', vars)
        : status === 'finish' && c.key === 'versions'
          ? t('veracode.check.versionsOk', vars)
          : undefined,
    };
  });
}
