import { useEffect, useMemo, useState } from 'react';
import { Alert, Button, Input, Space, Table, Tag, Tooltip, Typography } from 'antd';
import type { TableColumnsType } from 'antd';
import { FileSearchOutlined, KeyOutlined, PlayCircleOutlined } from '@ant-design/icons';
import { confluenceAuthComplete, veracodeApi } from '../../api/veracode';
import type { ConfluenceAttachment, ConfluenceAuth, ConfluenceConnection, ConfluencePageAttachments } from '../../api/veracode';
import { ApiError } from '../../api/client';
import { ConfluenceTokenModal } from './ConfluenceTokenModal';
import { confluenceTokenStore, emptyConfluenceAuth } from './confluenceToken';
import { useI18n } from '../../locales';

const { Text } = Typography;

interface Props {
  baseUrl: string;
  /** The deployment the token dialog preselects when nothing is saved. */
  cloud: boolean;
  mock?: boolean;
  /** An analysis is running: nothing new can start. */
  busy: boolean;
  /** Restored from the page cache, and reported back so a reload keeps it. */
  pageId: string;
  onPageIdChange: (pageId: string) => void;
  /** The page runs the analysis (same request guard, confirmation and result handling as an upload). */
  onAnalyze: (pageId: string, attachmentIds: string[], label: string, auth: ConfluenceAuth) => void;
}

/** A pasted page link works too: `...viewpage.action?pageId=123`, or Cloud's `/pages/123/Title`. */
export const pageIdOf = (input: string) => {
  const text = input.trim();
  return /[?&]pageId=(\d+)/.exec(text)?.[1] ?? /\/pages\/(\d+)(?:[/?#]|$)/.exec(text)?.[1] ?? text;
};

const size = (bytes: number | null) => (bytes == null ? '—'
  : bytes >= 1024 * 1024 ? `${(bytes / 1024 / 1024).toFixed(1)} MB`
    : bytes >= 1024 ? `${(bytes / 1024).toFixed(1)} KB` : `${bytes} B`);

/**
 * Reports from a Confluence page instead of an upload: the page's attachments are listed with the user's own
 * Confluence token, the user ticks `.xml` / `.zip` ones, and the backend downloads and analyses them.
 */
export function ConfluenceSource({ baseUrl, cloud, mock, busy, pageId, onPageIdChange, onAnalyze }: Props) {
  const { t } = useI18n();
  const [token, setToken] = useState(() => confluenceTokenStore.load());
  const auth = useMemo(() => token.auth ?? emptyConfluenceAuth(cloud ? 'cloud' : 'dataCenter'), [token.auth, cloud]);
  const hasToken = confluenceAuthComplete(auth);
  const [connection, setConnection] = useState<ConfluenceConnection | null>(null);
  const [tokenOpen, setTokenOpen] = useState(false);
  const [listing, setListing] = useState<ConfluencePageAttachments | null>(null);
  const [listingLoading, setListingLoading] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [selected, setSelected] = useState<string[]>([]);

  /** A saved token is tested once, so the button can say whose it is. */
  useEffect(() => {
    if (!token.auth || !confluenceAuthComplete(token.auth)) return;
    veracodeApi.testConfluence(token.auth).then(setConnection).catch(() => setConnection(null));
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, []);

  const save = (value: ConfluenceAuth, remember: boolean, tested: ConfluenceConnection | null) => {
    confluenceTokenStore.save(value, remember);
    setToken({ auth: value, remember });
    setConnection(tested);
    setTokenOpen(false);
    if (!tested) veracodeApi.testConfluence(value).then(setConnection).catch(() => setConnection(null));
  };

  const clear = () => {
    confluenceTokenStore.clear();
    setToken({ auth: null, remember: false });
    setConnection(null);
    setTokenOpen(false);
  };

  const list = async () => {
    if (!hasToken) {
      setTokenOpen(true);
      return;
    }
    const id = pageIdOf(pageId);
    onPageIdChange(id);
    setListingLoading(true);
    setError(null);
    try {
      const result = await veracodeApi.confluenceAttachments(id, auth);
      setListing(result);
      // everything analysable is ticked: usually what a report page holds
      setSelected(result.attachments.filter((a) => a.supported).map((a) => a.id));
    } catch (e) {
      setListing(null);
      setSelected([]);
      if (e instanceof ApiError && e.body?.error === 'confluence_token_required') setTokenOpen(true);
      setError((e as Error).message);
    } finally {
      setListingLoading(false);
    }
  };

  const analyze = () => {
    if (!listing || selected.length === 0) return;
    const chosen = listing.attachments.filter((a) => selected.includes(a.id));
    const label = chosen.length === 1 ? chosen[0].title : t('veracode.upload.files', { count: chosen.length });
    onAnalyze(listing.pageId, chosen.map((a) => a.id), label, auth);
  };

  const columns: TableColumnsType<ConfluenceAttachment> = [
    {
      title: t('veracode.confluence.col.title'),
      dataIndex: 'title',
      render: (title: string, a) => (
        <Space size={4}>
          <Text>{title}</Text>
          {!a.supported && <Tooltip title={t('veracode.confluence.unsupported')}><Tag>{t('veracode.confluence.notReport')}</Tag></Tooltip>}
        </Space>
      ),
    },
    { title: t('veracode.confluence.col.type'), dataIndex: 'mediaType', width: 200, render: (v: string | null) => v ?? '—' },
    { title: t('veracode.confluence.col.size'), dataIndex: 'size', width: 110, align: 'right', render: size },
    { title: t('veracode.confluence.col.version'), dataIndex: 'version', width: 80, align: 'right' },
    {
      title: t('veracode.confluence.col.created'),
      dataIndex: 'created',
      width: 200,
      render: (v: string | null, a) => (v ? `${new Date(v).toLocaleString()}${a.author ? ` · ${a.author}` : ''}` : '—'),
    },
  ];

  return (
    <>
      <Space wrap>
        <Tooltip title={t('veracode.confluence.pageHint')}>
          <Input
            value={pageId}
            onChange={(e) => onPageIdChange(e.target.value)}
            onPressEnter={() => void list()}
            placeholder={t('veracode.confluence.pageId')}
            allowClear
            style={{ width: 340 }}
          />
        </Tooltip>
        <Button icon={<FileSearchOutlined />} loading={listingLoading} disabled={!pageId.trim()} onClick={() => void list()}>
          {t('veracode.confluence.list')}
        </Button>
        <Tooltip title={connection?.message ?? t('veracode.confluence.token.buttonHint')}>
          <Button icon={<KeyOutlined />} onClick={() => setTokenOpen(true)}>
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
        {mock && <Text type="secondary">{t('veracode.confluence.mockHint')}</Text>}
      </Space>
      {error && <Alert style={{ marginTop: 12 }} type="error" showIcon message={error} />}
      {listing && (
        <>
          <Space style={{ marginTop: 12 }} wrap>
            <Text>{t('veracode.confluence.page')}</Text>
            <a href={listing.url} target="_blank" rel="noreferrer">{listing.title}</a>
            {listing.spaceKey && <Tag>{listing.spaceKey}</Tag>}
          </Space>
          <Table<ConfluenceAttachment>
            rowKey="id"
            size="small"
            style={{ marginTop: 8 }}
            columns={columns}
            dataSource={listing.attachments}
            pagination={false}
            scroll={{ x: 900, y: 320 }}
            locale={{ emptyText: t('veracode.confluence.empty') }}
            rowSelection={{
              selectedRowKeys: selected,
              onChange: (keys) => setSelected(keys as string[]),
              getCheckboxProps: (a) => ({ disabled: !a.supported }),
            }}
          />
          <Button
            type="primary"
            icon={<PlayCircleOutlined />}
            style={{ marginTop: 12 }}
            loading={busy}
            disabled={selected.length === 0}
            onClick={analyze}
          >
            {t('veracode.confluence.analyze', { count: selected.length })}
          </Button>
        </>
      )}
      <ConfluenceTokenModal
        open={tokenOpen}
        auth={auth}
        saved={!!token.auth}
        remember={token.remember}
        baseUrl={baseUrl}
        mock={mock}
        connection={connection}
        onSave={save}
        onClear={clear}
        onCancel={() => setTokenOpen(false)}
      />
    </>
  );
}
