import { useCallback, useEffect, useMemo, useRef, useState } from 'react';
import { App, Button, Card, Input, Popconfirm, Space, Table, Tooltip, Typography, Upload } from 'antd';
import type { TableColumnsType } from 'antd';
import {
  CopyOutlined, DeleteOutlined, DownloadOutlined, PlusOutlined, SaveOutlined, UndoOutlined, UploadOutlined,
} from '@ant-design/icons';
import { applicationAliasApi, saveBlob } from '../../../api/veracode';
import type { ApplicationAlias } from '../../../api/veracode';
import { useI18n } from '../../../locales';

const { Paragraph } = Typography;

/** A row being edited; `key` only keeps React's rows apart while an app name is typed. */
interface Row extends ApplicationAlias {
  key: number;
}

const strip = (rows: ApplicationAlias[]): ApplicationAlias[] => rows.map(({ appName, alias, repositoryUrl }) => ({
  appName: appName.trim(), alias: alias?.trim() || null, repositoryUrl: repositoryUrl?.trim() || null,
}));

/** How app names are compared, as the backend does: trimmed, any case. */
const appKey = (appName: string) => appName.trim().toLowerCase();

/**
 * An exported file's aliases - the shape `GET` answers and `PUT` takes, so a file also goes in with curl.
 *
 * @throws Error not a JSON array of objects with an `appName` string
 */
const parseExport = (text: string): ApplicationAlias[] => {
  const json: unknown = JSON.parse(text);
  if (!Array.isArray(json)) throw new Error('not a JSON array');
  return json.map((item: unknown, i) => {
    const a = item as Partial<Record<keyof ApplicationAlias, unknown>> | null;
    if (!a || typeof a !== 'object' || typeof a.appName !== 'string' || !a.appName.trim()) {
      throw new Error(`item ${i + 1}: no appName`);
    }
    const str = (v: unknown) => (typeof v === 'string' ? v : null);
    return { appName: a.appName.trim(), alias: str(a.alias), repositoryUrl: str(a.repositoryUrl) };
  });
};

/**
 * Application aliases (asked for, 2026-11-13: moved here from the backend's `veracode.applications`): a Veracode
 * `app_name` shown, and labelled in Jira, under a name of its own, linked to its repository. Edited as one table and
 * saved as one; the backend checks it (app name required and once, an alias or a link, links http(s)).
 */
export function ApplicationAliasesPanel() {
  const { t } = useI18n();
  const { message } = App.useApp();
  const [saved, setSaved] = useState<ApplicationAlias[]>([]);
  const [rows, setRows] = useState<Row[]>([]);
  const [loading, setLoading] = useState(false);
  const [saving, setSaving] = useState(false);
  const nextKey = useRef(0);

  const toRows = useCallback((aliases: ApplicationAlias[]) => aliases.map((a) => ({ ...a, key: nextKey.current++ })), []);

  const load = useCallback(() => {
    setLoading(true);
    applicationAliasApi.list()
      .then((aliases) => {
        setSaved(aliases);
        setRows(toRows(aliases));
      })
      .catch((e: Error) => message.error(e.message))
      .finally(() => setLoading(false));
  }, [message, toRows]);

  useEffect(load, [load]);

  const dirty = useMemo(() => JSON.stringify(strip(rows)) !== JSON.stringify(strip(saved)), [rows, saved]);

  /** App names entered more than once - the backend refuses those, so they are marked before saving. */
  const twice = useMemo(() => {
    const counts = new Map<string, number>();
    rows.forEach((r) => {
      if (r.appName.trim()) counts.set(appKey(r.appName), (counts.get(appKey(r.appName)) ?? 0) + 1);
    });
    return new Set([...counts].filter(([, n]) => n > 1).map(([k]) => k));
  }, [rows]);

  const edit = (key: number, field: keyof ApplicationAlias, value: string) =>
    setRows((current) => current.map((r) => (r.key === key ? { ...r, [field]: value } : r)));

  /** The row again, right under it - its app name then shows as a duplicate until changed. */
  const duplicate = (row: Row) => setRows((current) => {
    const at = current.findIndex((r) => r.key === row.key);
    return [...current.slice(0, at + 1), { ...row, key: nextKey.current++ }, ...current.slice(at + 1)];
  });

  /** What is saved (not unsaved edits), as `application-aliases-<date>.json`. */
  const exportJson = () => {
    const blob = new Blob([`${JSON.stringify(strip(saved), null, 2)}\n`], { type: 'application/json' });
    saveBlob({ blob, filename: `application-aliases-${new Date().toISOString().slice(0, 10)}.json` });
    if (dirty) message.info(t('veracode.alias.exportedSaved'));
  };

  /**
   * An exported file merged into the table being edited, by app name: a name it has takes the file's alias and link,
   * a new one is added, the others stay. Nothing is stored until Save - the table shows what will be.
   */
  const importJson = async (file: File) => {
    try {
      const imported = parseExport(await file.text());
      let updated = 0;
      const next = [...rows];
      const added: Row[] = [];
      imported.forEach((a) => {
        const at = next.findIndex((r) => appKey(r.appName) === appKey(a.appName));
        if (at >= 0) {
          next[at] = { ...next[at], alias: a.alias, repositoryUrl: a.repositoryUrl };
          updated += 1;
        } else {
          added.push({ ...a, key: nextKey.current++ });
        }
      });
      setRows([...next, ...added]);
      message.success(t('veracode.alias.imported', { file: file.name, updated, added: added.length }));
    } catch (e) {
      message.error(t('veracode.alias.importFailed', { file: file.name, reason: (e as Error).message }));
    }
    return false;
  };

  const save = async () => {
    setSaving(true);
    try {
      const stored = await applicationAliasApi.save(strip(rows));
      setSaved(stored);
      setRows(toRows(stored));
      message.success(t('veracode.alias.saved', { count: stored.length }));
    } catch (e) {
      // the backend names the row: "row 2: 'A' is listed twice"
      message.error((e as Error).message);
    } finally {
      setSaving(false);
    }
  };

  const columns: TableColumnsType<Row> = [
    {
      title: t('veracode.alias.appName'),
      dataIndex: 'appName',
      width: '34%',
      render: (_, r) => (
        <Input
          value={r.appName}
          status={!r.appName.trim() || twice.has(appKey(r.appName)) ? 'error' : undefined}
          placeholder="GBT-T1-Creed-CCS"
          onChange={(e) => edit(r.key, 'appName', e.target.value)}
        />
      ),
    },
    {
      title: t('veracode.alias.alias'),
      dataIndex: 'alias',
      width: '24%',
      render: (_, r) => (
        <Input
          value={r.alias ?? ''}
          placeholder={r.appName.trim() || 'Creed-CCS'}
          onChange={(e) => edit(r.key, 'alias', e.target.value)}
        />
      ),
    },
    {
      title: t('veracode.alias.repositoryUrl'),
      dataIndex: 'repositoryUrl',
      render: (_, r) => (
        <Input
          value={r.repositoryUrl ?? ''}
          placeholder="https://git.example.com/projects/CCS/browse"
          onChange={(e) => edit(r.key, 'repositoryUrl', e.target.value)}
        />
      ),
    },
    {
      key: 'actions',
      width: 88,
      render: (_, r) => (
        <Space size={0}>
          <Tooltip title={t('veracode.alias.copy')}>
            <Button type="text" icon={<CopyOutlined />} aria-label={t('veracode.alias.copy')} onClick={() => duplicate(r)} />
          </Tooltip>
          <Popconfirm
            title={t('veracode.alias.removeConfirm', { name: r.appName.trim() || '—' })}
            onConfirm={() => setRows((current) => current.filter((x) => x.key !== r.key))}
          >
            <Button type="text" danger icon={<DeleteOutlined />} aria-label={t('veracode.alias.remove')} />
          </Popconfirm>
        </Space>
      ),
    },
  ];

  return (
    <Card
      title={t('veracode.alias.title')}
      extra={(
        <Space wrap>
          <Upload accept=".json,application/json" showUploadList={false} beforeUpload={(f) => importJson(f)}>
            <Tooltip title={t('veracode.alias.importHint')}>
              <Button icon={<UploadOutlined />} disabled={loading || saving}>{t('veracode.alias.import')}</Button>
            </Tooltip>
          </Upload>
          <Tooltip title={t('veracode.alias.exportHint')}>
            <Button icon={<DownloadOutlined />} disabled={loading || !saved.length} onClick={exportJson}>
              {t('veracode.alias.export')}
            </Button>
          </Tooltip>
          <Button icon={<UndoOutlined />} disabled={!dirty || saving} onClick={() => setRows(toRows(saved))}>
            {t('veracode.alias.reset')}
          </Button>
          <Button type="primary" icon={<SaveOutlined />} disabled={!dirty} loading={saving} onClick={() => void save()}>
            {t('veracode.alias.save')}
          </Button>
        </Space>
      )}
    >
      <Paragraph type="secondary">{t('veracode.alias.hint')}</Paragraph>
      <Table<Row>
        rowKey="key"
        size="small"
        loading={loading}
        columns={columns}
        dataSource={rows}
        pagination={false}
        scroll={{ x: 720 }}
        locale={{ emptyText: t('veracode.alias.none') }}
        footer={() => (
          <Button
            type="dashed"
            block
            icon={<PlusOutlined />}
            onClick={() => setRows((current) => [...current, { key: nextKey.current++, appName: '', alias: '', repositoryUrl: '' }])}
          >
            {t('veracode.alias.add')}
          </Button>
        )}
      />
    </Card>
  );
}
