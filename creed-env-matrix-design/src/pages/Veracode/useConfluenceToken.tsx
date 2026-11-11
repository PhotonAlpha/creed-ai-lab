import { useEffect, useMemo, useState } from 'react';
import { Button, Tag, Tooltip } from 'antd';
import { KeyOutlined } from '@ant-design/icons';
import { confluenceAuthComplete, veracodeApi } from '../../api/veracode';
import type { ConfluenceAuth, ConfluenceConnection } from '../../api/veracode';
import { ConfluenceTokenModal } from './ConfluenceTokenModal';
import { confluenceTokenStore, emptyConfluenceAuth } from './confluenceToken';
import { useI18n } from '../../locales';

/**
 * The user's Confluence sign-in, held by the page rather than the Confluence tab (asked for, 2026-11-12): its button
 * sits beside the source card's title, as the Jira token beside its own, and the tab only reads it. A saved token is
 * tested once, so the button can say whose it is.
 */
export function useConfluenceToken({ baseUrl, cloud, mock }: { baseUrl?: string | null; cloud: boolean; mock?: boolean }) {
  const { t } = useI18n();
  const [token, setToken] = useState(() => confluenceTokenStore.load());
  const auth = useMemo(() => token.auth ?? emptyConfluenceAuth(cloud ? 'cloud' : 'dataCenter'), [token.auth, cloud]);
  const hasToken = confluenceAuthComplete(auth);
  const [connection, setConnection] = useState<ConfluenceConnection | null>(null);
  const [open, setOpen] = useState(false);

  useEffect(() => {
    if (!token.auth || !confluenceAuthComplete(token.auth)) return;
    veracodeApi.testConfluence(token.auth).then(setConnection).catch(() => setConnection(null));
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, []);

  const save = (value: ConfluenceAuth, remember: boolean, tested: ConfluenceConnection | null) => {
    confluenceTokenStore.save(value, remember);
    setToken({ auth: value, remember });
    setConnection(tested);
    setOpen(false);
    if (!tested) veracodeApi.testConfluence(value).then(setConnection).catch(() => setConnection(null));
  };

  const clear = () => {
    confluenceTokenStore.clear();
    setToken({ auth: null, remember: false });
    setConnection(null);
    setOpen(false);
  };

  const button = (
    <Tooltip title={connection?.message ?? t('veracode.confluence.token.buttonHint')}>
      <Button size="small" icon={<KeyOutlined />} onClick={() => setOpen(true)}>
        {t('veracode.confluence.token.button')}
        {!hasToken
          ? <Tag style={{ marginInlineEnd: 0 }}>{t('veracode.token.unset')}</Tag>
          : connection?.ok
            ? <Tag color="success" style={{ marginInlineEnd: 0 }}>{connection.user ?? t('veracode.token.valid')}</Tag>
            : connection
              ? <Tag color="error" style={{ marginInlineEnd: 0 }}>{t('veracode.token.bad')}</Tag>
              : <Tag color="processing" style={{ marginInlineEnd: 0 }}>{t('veracode.token.set')}</Tag>}
      </Button>
    </Tooltip>
  );

  const modal = (
    <ConfluenceTokenModal
      open={open}
      auth={auth}
      saved={!!token.auth}
      remember={token.remember}
      baseUrl={baseUrl}
      mock={mock}
      connection={connection}
      onSave={save}
      onClear={clear}
      onCancel={() => setOpen(false)}
    />
  );

  return { auth, hasToken, connection, openDialog: () => setOpen(true), button, modal };
}
