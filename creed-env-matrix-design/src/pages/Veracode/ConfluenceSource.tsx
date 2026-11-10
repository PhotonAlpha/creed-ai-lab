import { useEffect, useMemo, useState } from 'react';
import { Alert, Button, Checkbox, Input, Select, Space, Table, Tag, Tooltip, Typography } from 'antd';
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

/** A report date in the file name, `dd_MMM_yyyy` (`09_Nov_2026`), any case - how the scans are named on the page. */
const DATED = /(?<!\d)(\d{2})_(jan|feb|mar|apr|may|jun|jul|aug|sep|oct|nov|dec)_(\d{4})(?!\d)/i;
const MONTHS = ['jan', 'feb', 'mar', 'apr', 'may', 'jun', 'jul', 'aug', 'sep', 'oct', 'nov', 'dec'];
/** The report date in a name as `{ key: 'yyyy-MM-dd', label: '09_Nov_2026' }`; the key sorts and compares, case aside. */
const reportDate = (title: string) => {
  const m = DATED.exec(title);
  if (!m) return null;
  const month = MONTHS.indexOf(m[2].toLowerCase());
  return {
    key: `${m[3]}-${String(month + 1).padStart(2, '0')}-${m[1]}`,
    label: `${m[1]}_${m[2][0].toUpperCase()}${m[2].slice(1).toLowerCase()}_${m[3]}`,
  };
};
/** How many attachments are listed at most; 0 = all. The newest first, so the limit keeps the latest. */
const LIMITS = [50, 100, 200, 0];
const newestFirst = (a: ConfluenceAttachment, b: ConfluenceAttachment) => (b.created ?? '').localeCompare(a.created ?? '');

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
  // the default filter (asked for): a dated report name and a .zip - the page also holds PDFs of the same scans
  const [datedOnly, setDatedOnly] = useState(true);
  const [zipOnly, setZipOnly] = useState(true);
  const [limit, setLimit] = useState(50);
  /** One report date (`yyyy-MM-dd`) or '' for any: a page keeps every run (`03_Nov_2026`, `09_Nov_2026`, ...). */
  const [date, setDate] = useState('');

  /** The dates the page's names hold, newest first, with how many attachments carry each. */
  const dates = useMemo(() => {
    const counts = new Map<string, { label: string; count: number }>();
    for (const a of listing?.attachments ?? []) {
      const d = reportDate(a.title);
      if (!d || (zipOnly && !a.title.toLowerCase().endsWith('.zip'))) continue;
      const seen = counts.get(d.key);
      counts.set(d.key, { label: seen?.label ?? d.label, count: (seen?.count ?? 0) + 1 });
    }
    return [...counts].sort(([a], [b]) => b.localeCompare(a)).map(([key, v]) => ({ key, ...v }));
  }, [listing, zipOnly]);

  const matching = useMemo(() => (listing?.attachments ?? [])
    .filter((a) => {
      if (zipOnly && !a.title.toLowerCase().endsWith('.zip')) return false;
      if (!datedOnly) return true;
      const d = reportDate(a.title);
      return !!d && (!date || d.key === date);
    })
    .sort(newestFirst), [listing, datedOnly, zipOnly, date]);
  const visible = useMemo(() => (limit > 0 ? matching.slice(0, limit) : matching), [matching, limit]);
  /** Only what is listed can be analysed: a filter change drops what it hides from the selection. */
  const chosenIds = useMemo(() => {
    const shown = new Set(visible.map((a) => a.id));
    return selected.filter((id) => shown.has(id));
  }, [visible, selected]);

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
      // the latest run is what is usually analysed: older dated reports stay one pick away
      setDate(result.attachments
        .filter((a) => !zipOnly || a.title.toLowerCase().endsWith('.zip'))
        .map((a) => reportDate(a.title)?.key ?? '')
        .reduce((a, b) => (b > a ? b : a), ''));
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
    if (!listing || chosenIds.length === 0) return;
    const chosen = listing.attachments.filter((a) => chosenIds.includes(a.id));
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
          <Space style={{ marginTop: 8 }} wrap>
            <Tooltip title={t('veracode.confluence.filter.datedHint')}>
              <Checkbox checked={datedOnly} onChange={(e) => setDatedOnly(e.target.checked)}>
                {t('veracode.confluence.filter.dated')}
              </Checkbox>
            </Tooltip>
            {datedOnly && (
              <Select
                size="small"
                value={date}
                onChange={setDate}
                showSearch
                optionFilterProp="label"
                style={{ width: 200 }}
                options={[
                  { value: '', label: t('veracode.confluence.filter.anyDate') },
                  ...dates.map((d) => ({ value: d.key, label: `${d.label} (${d.count})` })),
                ]}
              />
            )}
            <Checkbox checked={zipOnly} onChange={(e) => setZipOnly(e.target.checked)}>
              {t('veracode.confluence.filter.zip')}
            </Checkbox>
            <Text type="secondary">{t('veracode.confluence.filter.limit')}</Text>
            <Select
              size="small"
              value={limit}
              onChange={setLimit}
              style={{ width: 100 }}
              options={LIMITS.map((n) => ({ value: n, label: n === 0 ? t('veracode.confluence.filter.all') : n }))}
            />
            <Text type="secondary">
              {t('veracode.confluence.filter.shown', { shown: visible.length, matching: matching.length, total: listing.attachments.length })}
            </Text>
          </Space>
          <Table<ConfluenceAttachment>
            rowKey="id"
            size="small"
            style={{ marginTop: 8 }}
            columns={columns}
            dataSource={visible}
            pagination={false}
            scroll={{ x: 900, y: 320 }}
            locale={{ emptyText: t(listing.attachments.length === 0 ? 'veracode.confluence.empty' : 'veracode.confluence.filter.none') }}
            rowSelection={{
              selectedRowKeys: chosenIds,
              onChange: (keys) => setSelected(keys as string[]),
              getCheckboxProps: (a) => ({ disabled: !a.supported }),
            }}
          />
          <Button
            type="primary"
            icon={<PlayCircleOutlined />}
            style={{ marginTop: 12 }}
            loading={busy}
            disabled={chosenIds.length === 0}
            onClick={analyze}
          >
            {t('veracode.confluence.analyze', { count: chosenIds.length })}
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
