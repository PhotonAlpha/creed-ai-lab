import type { ReactNode } from 'react';
import { Alert, Button, Space, Typography } from 'antd';
import { ExportOutlined } from '@ant-design/icons';
import type { JiraDeployment } from '../../api/veracode';
import { useI18n } from '../../locales';

/** Cloud: one Atlassian account page for Jira and Confluence alike - the same API token works for both. */
const ATLASSIAN_API_TOKENS = 'https://id.atlassian.com/manage-profile/security/api-tokens';

const base = (url?: string | null) => (url ?? '').replace(/\/+$/, '');

/**
 * Where the user creates the token (asked for, 2026-11-11: a link out, then back to paste it). Data Center's page is
 * on the instance itself; without a base URL there is none to link to.
 */
export const tokenPageUrl = (product: 'jira' | 'confluence', deployment: JiraDeployment, baseUrl?: string | null) => {
  if (deployment === 'cloud') return ATLASSIAN_API_TOKENS;
  if (!base(baseUrl)) return null;
  return product === 'jira'
    ? `${base(baseUrl)}/secure/ViewProfile.jspa?selectedTab=com.atlassian.pats.pats-plugin:jira-user-personal-access-tokens`
    : `${base(baseUrl)}/plugins/personalaccesstokens/usertokens.action`;
};

/**
 * "No token yet?": what to click once there, and the link that opens the page in a new tab - the dialog stays open
 * with what was typed, so the user comes back and pastes.
 */
export function TokenHelp({ url, steps }: { url: string | null; steps: ReactNode }) {
  const { t } = useI18n();
  return (
    <Alert
      type="info"
      showIcon
      style={{ marginBottom: 16 }}
      message={t('veracode.tokenHelp.title')}
      description={(
        <Space direction="vertical" size={4}>
          <Typography.Text>{steps}</Typography.Text>
          {url && (
            <Button type="link" href={url} target="_blank" rel="noreferrer" icon={<ExportOutlined />} style={{ padding: 0 }}>
              {t('veracode.tokenHelp.open')}
            </Button>
          )}
        </Space>
      )}
    />
  );
}
