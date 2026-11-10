import { App, Typography } from 'antd';
import { RedoOutlined } from '@ant-design/icons';
import type { IssueRef } from '../../api/veracode';
import type { useI18n } from '../../locales';

const { Text } = Typography;

/** Red, unlike create (blue) and add label (orange): filing again what Jira already holds is the exception. */
export const RECREATE_BUTTON = { color: 'danger', icon: <RedoOutlined /> } as const;

type Modal = ReturnType<typeof App.useApp>['modal'];

/**
 * Asks before a finding whose Jira issues are all finished (`veracode.jira.closed-statuses`: Closed / Done) is filed
 * again (asked for, 2026-11-10): the closed issues are listed, so the user can tell a regression from a duplicate.
 * The server re-checks - an issue reopened meanwhile answers ALREADY_EXISTS.
 */
export function confirmRecreate(modal: Modal, t: ReturnType<typeof useI18n>['t'], issues: IssueRef[], project: string, onOk: () => Promise<void>) {
  modal.confirm({
    title: t('veracode.recreate.title'),
    width: 600,
    okText: t('veracode.recreate.ok'),
    okButtonProps: { danger: true },
    content: (
      <>
        <div>{t('veracode.recreate.closed', { count: issues.length })}</div>
        <ul style={{ paddingInlineStart: 18, margin: '8px 0' }}>
          {issues.map((i) => (
            <li key={i.key}>
              <a href={i.url} target="_blank" rel="noreferrer">{i.key}</a> <Text type="secondary">{i.status ?? '—'}</Text>
              {' — '}{i.summary}
            </li>
          ))}
        </ul>
        <Text type="warning">{t('veracode.recreate.warning', { project })}</Text>
      </>
    ),
    onOk,
  });
}
