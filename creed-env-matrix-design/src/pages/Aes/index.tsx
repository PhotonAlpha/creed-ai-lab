import { memo, useCallback, useEffect, useMemo, useState } from 'react';
import { PageContainer, ProCard } from '@ant-design/pro-components';
import {
  Alert,
  App,
  Button,
  Checkbox,
  Col,
  Empty,
  Form,
  Input,
  Modal,
  Popconfirm,
  Row,
  Select,
  Space,
  Table,
  Tag,
  Tooltip,
  Typography,
  theme,
} from 'antd';
import type { TableColumnsType } from 'antd';
import {
  ArrowLeftOutlined,
  ArrowRightOutlined,
  ClearOutlined,
  CodeOutlined,
  CopyOutlined,
  DeleteOutlined,
  MinusCircleOutlined,
  PlusOutlined,
  ReloadOutlined,
  SaveOutlined,
  UnlockOutlined,
} from '@ant-design/icons';
import { aesApi } from '../../api/aes';
import { ApiError } from '../../api/client';
import { useDimensions } from '../../hooks/useDimensions';
import { useI18n } from '../../locales';
import type { MessageKey } from '../../locales';
import type { AesBatchCryptoResult, AesKeyValueRow, AesRecord, AesRecordDecryptItem, AesRecordDecryptResult, AesServer } from '../../api/types';

const { Text } = Typography;

interface FormValues {
  items: AesKeyValueRow[];
}

/** Field order of a row — also the JSON shape the "Edit as JSON" dialog reads and writes. */
const ROW_FIELDS = ['iv', 'salt', 'randomKey', 'propertyKey', 'plainValue', 'encryptedValue'] as const;
type RowField = (typeof ROW_FIELDS)[number];
const KEY_FIELDS: readonly RowField[] = ['iv', 'salt', 'randomKey'];
const EMPTY_ROW: AesKeyValueRow = { iv: '', salt: '', randomKey: '', propertyKey: '', plainValue: '', encryptedValue: '' };
/** The batch endpoints' limit. */
const MAX_ROWS = 200;

const utf8Length = (text: string) => new TextEncoder().encode(text).length;
const serverKey = (s: Pick<AesServer, 'appSystem' | 'host' | 'ip'>) => `${s.appSystem}\u0000${s.host}\u0000${s.ip}`;

/** What a list is narrowed to: one app system (or all) and any number of env instances (none = all). */
interface Scope {
  appSystem?: string;
  envInstances: string[];
}
const NO_SCOPE: Scope = { envInstances: [] };
const sortedDistinct = (values: (string | undefined)[]) => [...new Set(values.filter((v): v is string => !!v))].sort();
/** The real config files' rule — randomkey + host + ip, no separator. The server derives the same. */
const secretKeyOf = (randomKey: string | null | undefined, s: Pick<AesServer, 'host' | 'ip'>) => `${randomKey ?? ''}${s.host}${s.ip}`;
const normalize = (row: Partial<AesKeyValueRow> | undefined): AesKeyValueRow => ({ ...EMPTY_ROW, ...row });

/**
 * Column spans of a "Keys and values" row, which is two lines: the two secrets besides the host (IV and
 * salt) on the first, everything else on the second, with the same
 * spans. Every field has its own label (vertical form layout); below `lg` each takes the full width.
 */
const SPANS: Record<RowField | 'secretKey' | 'actions', number> = {
  iv: 6, salt: 6,
  randomKey: 4, secretKey: 4, propertyKey: 4, plainValue: 5, encryptedValue: 5, actions: 2,
};
const LINE_1 = ['iv', 'salt'] as const;
/** The derived Secret Key sits right after the randomkey it starts with. */
const LINE_2 = ['randomKey', 'secretKey', 'propertyKey', 'plainValue', 'encryptedValue'] as const;
const HINTS: Partial<Record<(typeof LINE_1)[number] | (typeof LINE_2)[number], string>> = {
  iv: 'aes.form.ivHint', salt: 'aes.form.saltHint', randomKey: 'aes.form.randomKeyHint', secretKey: 'aes.form.secretKeyHint',
};

/** A field's label and, where it has one, its hint as the label's tooltip. */
const fieldLabel = (f: (typeof LINE_1)[number] | (typeof LINE_2)[number], t: ReturnType<typeof useI18n>['t']) => ({
  label: t(`aes.form.${f}`),
  tooltip: HINTS[f] ? t(HINTS[f] as MessageKey) : undefined,
});

/** Parses the JSON dialog's text into rows, or throws an Error whose message is shown under it. */
function parseRows(text: string, t: ReturnType<typeof useI18n>['t']): AesKeyValueRow[] {
  let parsed: unknown;
  try {
    parsed = JSON.parse(text);
  } catch (e) {
    throw new Error(t('aes.json.invalid', { reason: (e as Error).message }));
  }
  if (!Array.isArray(parsed) || parsed.length === 0) throw new Error(t('aes.json.notArray'));
  if (parsed.length > MAX_ROWS) throw new Error(t('aes.json.tooMany', { max: MAX_ROWS }));
  return parsed.map((item, index) => {
    if (item === null || typeof item !== 'object' || Array.isArray(item)) {
      throw new Error(t('aes.json.badItem', { index, reason: t('aes.json.notObject') }));
    }
    const row = { ...EMPTY_ROW };
    for (const [field, value] of Object.entries(item as Record<string, unknown>)) {
      // Unknown names are rejected rather than ignored: `secret_key` silently dropped would encrypt
      // with an empty key and look like it worked.
      if (field === 'secretKey') {
        throw new Error(t('aes.json.badItem', { index, reason: t('aes.json.secretKeyRemoved') }));
      }
      if (!(ROW_FIELDS as readonly string[]).includes(field)) {
        throw new Error(t('aes.json.badItem', {
          index, reason: t('aes.json.unknownField', { field, allowed: ROW_FIELDS.join(', ') }),
        }));
      }
      if (value == null) continue;
      if (typeof value !== 'string') {
        throw new Error(t('aes.json.badItem', { index, reason: t('aes.json.notString', { field }) }));
      }
      row[field as RowField] = value;
    }
    return row;
  });
}

/**
 * AES encryption page.
 *
 * The Secret Key is not an input: it is `randomkey + host + ip`, so every server has its own and the
 * same value has a different ciphertext on each; the AES key is PBKDF2(Secret Key, salt). "Keys and
 * values" rows carry IV, salt, randomkey, property key and plain value; *Save* sends the plain values and the backend encrypts each once per
 * ticked server. The form's own encrypt/decrypt work against one **preview server** (a ticked one,
 * picked in the card header), and the derived Secret Key column shows that server's key. Each record
 * stores its randomkey, IV and salt (V9, by request), so the result list decrypts without input and
 * shows everything the ciphertext was made with, for comparing with the real config file — and so a
 * copy of the stored records decrypts on its own.
 *
 * Decrypted values in the result list are cleared whenever a key field changes, since they no longer
 * describe what the form would produce.
 */
export function AesPage() {
  const { t } = useI18n();
  const { message } = App.useApp();
  const { token } = theme.useToken();
  const [form] = Form.useForm<FormValues>();
  const { dimensions } = useDimensions();

  /**
   * Every server and every record, fetched once and narrowed in the browser: the two lists have
   * their own app-system / env-instance filters, and a record's env instance only exists on its
   * server's endpoint rows — so both need the full server list anyway (~700 rows).
   */
  const [allServers, setAllServers] = useState<AesServer[]>([]);
  const [serverScope, setServerScope] = useState<Scope>(NO_SCOPE);
  const [serversLoading, setServersLoading] = useState(false);
  const [checked, setChecked] = useState<string[]>([]);

  const [allRecords, setAllRecords] = useState<AesRecord[]>([]);
  const [recordScope, setRecordScope] = useState<Scope>(NO_SCOPE);
  const [recordsLoading, setRecordsLoading] = useState(false);
  const [selectedIds, setSelectedIds] = useState<number[]>([]);
  const [decrypted, setDecrypted] = useState<Record<number, AesRecordDecryptResult>>({});

  /** serverKey of the ticked server the form's encrypt/decrypt and Secret Key column use. */
  const [previewKey, setPreviewKey] = useState<string | undefined>();
  const [busy, setBusy] = useState<'encrypt' | 'decrypt' | 'save' | 'decryptRows' | 'delete' | null>(null);
  const [jsonOpen, setJsonOpen] = useState(false);
  const [jsonText, setJsonText] = useState('');
  const [jsonError, setJsonError] = useState<string | null>(null);

  const watchedItems = Form.useWatch('items', form) as AesKeyValueRow[] | undefined;
  const rowCount = watchedItems?.length ?? 1;
  /** Property keys present in the form, for the server list's "saved" tags. */
  const formPropertyKeys = (watchedItems ?? []).map((r) => r?.propertyKey?.trim()).filter(Boolean).sort().join('\n');

  const loadServers = useCallback(async () => {
    setServersLoading(true);
    try {
      // One row per serverKey: the list's identity, and the records', is (appSystem, host, ip). The
      // endpoint table could in principle give one address two env instances; the first one wins.
      const byKey = new Map<string, AesServer>();
      for (const s of await aesApi.servers()) if (!byKey.has(serverKey(s))) byKey.set(serverKey(s), s);
      setAllServers([...byKey.values()]);
    } catch (e) {
      message.error((e as Error).message);
    } finally {
      setServersLoading(false);
    }
  }, [message]);

  const loadRecords = useCallback(async () => {
    setRecordsLoading(true);
    try {
      setAllRecords(await aesApi.records());
    } catch (e) {
      message.error((e as Error).message);
    } finally {
      setRecordsLoading(false);
    }
  }, [message]);

  useEffect(() => {
    void loadServers();
    void loadRecords();
  }, [loadServers, loadRecords]);

  /** A record's env instance, read off its server's endpoint rows (records do not store one). */
  const envOf = useMemo(() => new Map(allServers.map((s) => [serverKey(s), s.envInstance])), [allServers]);

  const servers = useMemo(() => allServers.filter((s) => (!serverScope.appSystem || s.appSystem === serverScope.appSystem)
    && (serverScope.envInstances.length === 0 || serverScope.envInstances.includes(s.envInstance))), [allServers, serverScope]);
  const records = useMemo(() => allRecords.filter((r) => (!recordScope.appSystem || r.appSystem === recordScope.appSystem)
    && (recordScope.envInstances.length === 0 || recordScope.envInstances.includes(envOf.get(serverKey(r)) ?? ''))),
  [allRecords, recordScope, envOf]);

  /** Env-instance options: those present under the list's current app-system choice. */
  const serverEnvOptions = useMemo(() => sortedDistinct(allServers
    .filter((s) => !serverScope.appSystem || s.appSystem === serverScope.appSystem).map((s) => s.envInstance)),
  [allServers, serverScope.appSystem]);
  const recordEnvOptions = useMemo(() => sortedDistinct(allRecords
    .filter((r) => !recordScope.appSystem || r.appSystem === recordScope.appSystem).map((r) => envOf.get(serverKey(r)))),
  [allRecords, recordScope.appSystem, envOf]);

  // Keep only selected records that are still listed — Delete selected must never reach a row the
  // filter is hiding.
  useEffect(() => {
    const listed = new Set(records.map((r) => r.id));
    setSelectedIds((ids) => (ids.every((id) => listed.has(id)) ? ids : ids.filter((id) => listed.has(id))));
  }, [records]);

  // Keep only ticks that are still listed — a hidden server must not be saved to by accident.
  useEffect(() => {
    const listed = new Set(servers.map(serverKey));
    setChecked((keys) => keys.filter((k) => listed.has(k)));
  }, [servers]);

  // The preview server is always a ticked one: the first, unless one was picked (or loaded).
  const checkedServers = useMemo(() => servers.filter((s) => checked.includes(serverKey(s))), [servers, checked]);
  const previewServer = checkedServers.find((s) => serverKey(s) === previewKey) ?? checkedServers[0];

  type Cell = { name: (string | number)[]; errors: string[] };
  /** setFields with list paths — antd's FieldData type cannot express `['items', i, field]`. */
  const setCells = (cells: Cell[]) => form.setFields(cells as Parameters<typeof form.setFields>[0]);

  const rows = (): AesKeyValueRow[] => ((form.getFieldValue('items') ?? []) as AesKeyValueRow[]).map(normalize);

  /**
   * Shows a 400's `fields` on the matching cells. The server names batch positions
   * (`items[2].propertyKey`); `formIndex` maps a batch position back to its form row.
   */
  const report = (e: unknown, formIndex: (batchIndex: number) => number) => {
    const error = e as ApiError;
    const cells = (error.body?.fields ?? []).flatMap((f) => {
      const m = /^items\[(\d+)]\.(\w+)$/.exec(f.field);
      return m && (ROW_FIELDS as readonly string[]).includes(m[2])
        ? [{ name: ['items', formIndex(Number(m[1])), m[2]], errors: [f.message] }]
        : [];
    });
    if (cells.length) setCells(cells);
    else message.error(error.message);
  };

  /** Validates the given cells; resolves false (errors shown on the form) if any fails. */
  const validCells = async (cells: (string | number)[][]) => {
    try {
      await form.validateFields(cells);
      return true;
    } catch {
      return false;
    }
  };

  /**
   * Validates the given rows' cells and resolves the indexes of the rows that failed — those are
   * marked on the form and left out, the rest go ahead, since the rows are independent.
   */
  const invalidRows = async (indexes: number[], fieldNames: RowField[]) => {
    try {
      await form.validateFields(indexes.flatMap((i) => fieldNames.map((f) => ['items', i, f])));
      return new Set<number>();
    } catch (e) {
      const errorFields = (e as { errorFields?: { name: (string | number)[] }[] }).errorFields ?? [];
      return new Set(errorFields.map((f) => Number(f.name[1])));
    }
  };

  /**
   * Encrypt all / decrypt all: every row that has a source value, each with its own keys. Results
   * come back per row, so one row's bad IV marks that row and does not stop the others.
   */
  const runBatch = async (direction: 'encrypt' | 'decrypt') => {
    const source: RowField = direction === 'encrypt' ? 'plainValue' : 'encryptedValue';
    const target: RowField = direction === 'encrypt' ? 'encryptedValue' : 'plainValue';
    const all = rows();
    const candidates = all.flatMap((row, index) => (row[source] !== '' ? [{ row, index }] : []));
    if (candidates.length === 0) {
      message.warning(t(direction === 'encrypt' ? 'aes.rows.nothingToEncrypt' : 'aes.rows.nothingToDecrypt'));
      return;
    }
    if (!previewServer) {
      message.warning(t('aes.rows.needServer'));
      return;
    }
    const invalid = await invalidRows(candidates.map((c) => c.index), ['iv', 'salt']);
    const picked = candidates.filter((c) => !invalid.has(c.index));
    if (picked.length === 0) return; // every row is marked on the form

    setBusy(direction);
    try {
      const items = picked.map(({ row }) => ({
        iv: row.iv, salt: row.salt, randomKey: row.randomKey, host: previewServer.host, ip: previewServer.ip, value: row[source],
      }));
      const results: AesBatchCryptoResult[] = direction === 'encrypt'
        ? await aesApi.encryptBatch(items)
        : await aesApi.decryptBatch(items);
      const next = [...all];
      const cells: Cell[] = [];
      for (const result of results) {
        const index = picked[result.index].index;
        if (result.error) {
          cells.push({ name: ['items', index, result.field ?? source], errors: [result.message ?? result.error] });
        } else {
          next[index] = { ...next[index], [target]: result.value ?? '' };
          cells.push({ name: ['items', index, target], errors: [] }, { name: ['items', index, source], errors: [] });
        }
      }
      form.setFieldsValue({ items: next });
      setCells(cells);
      const failed = results.filter((r) => r.error).length + invalid.size;
      if (failed) message.warning(t('aes.rows.failed', { failed, total: candidates.length }));
      else message.success(t(direction === 'encrypt' ? 'aes.rows.encrypted' : 'aes.rows.decrypted', { count: results.length }));
    } catch (e) {
      report(e, (i) => picked[i]?.index ?? i);
    } finally {
      setBusy(null);
    }
  };

  /**
   * Every row with a property key or a plain value, encrypted by the backend for every ticked server
   * (each its own Secret Key), in one call.
   */
  const save = async () => {
    const all = rows();
    const picked = all.flatMap((row, index) => (row.propertyKey.trim() || row.plainValue !== '' ? [{ row, index }] : []));
    if (picked.length === 0) {
      message.warning(t('aes.rows.nothingToSave'));
      return;
    }
    // The plain value has no form rule — it is only required here, not while decrypting into it.
    const noPlain = picked.filter(({ row }) => row.plainValue === '');
    const fieldsValid = await validCells(picked.flatMap(({ index }) => (['propertyKey', 'iv', 'salt'] as const).map((f) => ['items', index, f])));
    if (noPlain.length) setCells(noPlain.map(({ index }) => ({ name: ['items', index, 'plainValue'], errors: [t('aes.form.required')] })));
    if (!fieldsValid || noPlain.length) return;

    // The server rejects a repeated property key too; catching it here marks the row before a round trip.
    const firstRow = new Map<string, number>();
    const repeats = picked.flatMap(({ row, index }) => {
      const key = row.propertyKey.trim();
      const first = firstRow.get(key);
      if (first === undefined) {
        firstRow.set(key, index);
        return [];
      }
      return [{ name: ['items', index, 'propertyKey'], errors: [t('aes.rows.duplicateKey', { row: first + 1 })] }];
    });
    if (repeats.length) {
      setCells(repeats);
      return;
    }

    const targets = servers.filter((s) => checked.includes(serverKey(s)));
    if (targets.length === 0) {
      message.warning(t('aes.save.noServers'));
      return;
    }
    setBusy('save');
    try {
      const result = await aesApi.saveBatch({
        items: picked.map(({ row }) => ({
          propertyKey: row.propertyKey, plainValue: row.plainValue, iv: row.iv, salt: row.salt, randomKey: row.randomKey,
        })),
        servers: targets,
      });
      message.success(t('aes.save.done', {
        items: picked.length, servers: targets.length, inserted: result.inserted, updated: result.updated,
      }));
      await loadRecords();
    } catch (e) {
      report(e, (i) => picked[i]?.index ?? i);
    } finally {
      setBusy(null);
    }
  };

  /**
   * Decrypts the ticked records. Each record's Secret Key comes from the record itself, and so do its
   * IV and salt when it was saved with them (V9). Only older records take them from the form row with
   * the same property key — or from the only row, the one-IV case.
   * A row with an IV but no salt is still used: the server answers that record with "salt: is
   * required", which says more than "no matching row".
   */
  const decryptSelected = async () => {
    const all = rows();
    const withIv = all.filter((r) => r.iv !== '');
    const items: AesRecordDecryptItem[] = [];
    const missing: AesRecordDecryptResult[] = [];
    for (const id of selectedIds) {
      const record = records.find((r) => r.id === id);
      if (!record) continue;
      // Saved with its IV and salt: nothing to supply. Older records take them from the form row with
      // the same property key, or from the only row.
      if (record.iv && record.salt) {
        items.push({ id });
        continue;
      }
      const source = all.find((r) => r.propertyKey.trim() === record.propertyKey && r.iv !== '')
        ?? (all.length === 1 && withIv.length === 1 ? withIv[0] : undefined);
      if (source) {
        items.push({ id, iv: source.iv, salt: source.salt });
      } else {
        missing.push({ id, plainValue: null, error: 'not_found', message: t('aes.records.noIv', { key: record.propertyKey }) });
      }
    }
    setBusy('decryptRows');
    try {
      const results = items.length ? await aesApi.decryptRecords(items) : [];
      const merged = [...results, ...missing];
      setDecrypted((prev) => ({ ...prev, ...Object.fromEntries(merged.map((r) => [r.id, r])) }));
      const failed = merged.filter((r) => r.error).length;
      if (failed) message.warning(t('aes.records.decryptPartial', { failed, total: merged.length }));
    } catch (e) {
      message.error((e as Error).message);
    } finally {
      setBusy(null);
    }
  };

  const remove = async (ids: number[]) => {
    setBusy('delete');
    try {
      await aesApi.remove(ids);
      message.success(t('aes.records.deleted', { count: ids.length }));
      setDecrypted((prev) => Object.fromEntries(Object.entries(prev).filter(([id]) => !ids.includes(Number(id)))));
      await loadRecords();
    } catch (e) {
      message.error((e as Error).message);
    } finally {
      setBusy(null);
    }
  };

  /**
   * Puts a saved record into the form — into the row with the same property key, else into the first
   * row with no property key and no values (keeping its IV and salt: a row holding only those is how a
   * check usually starts), else as a new row — and makes its server the preview server.
   */
  const load = (record: AesRecord) => {
    const all = rows();
    const loaded = {
      propertyKey: record.propertyKey,
      encryptedValue: record.encryptedValue,
      randomKey: record.randomKey ?? '',
      // A record saved with its IV/salt brings them along; an older one keeps whatever the row has.
      ...(record.iv ? { iv: record.iv } : {}),
      ...(record.salt ? { salt: record.salt } : {}),
      plainValue: '',
    };
    let index = all.findIndex((r) => r.propertyKey.trim() === record.propertyKey);
    if (index < 0) index = all.findIndex((r) => !r.propertyKey.trim() && r.plainValue === '' && r.encryptedValue === '');
    if (index < 0) {
      if (all.length >= MAX_ROWS) {
        message.warning(t('aes.json.tooMany', { max: MAX_ROWS }));
        return;
      }
      all.push({ ...EMPTY_ROW, ...loaded });
      index = all.length - 1;
    } else {
      all[index] = { ...all[index], ...loaded };
    }
    form.setFieldsValue({ items: all });
    setCells([{ name: ['items', index, 'encryptedValue'], errors: [] }]);
    // setFieldsValue does not fire onValuesChange, and the randomkey may just have changed.
    setDecrypted({});
    // The loaded ciphertext belongs to this server: make it the preview server, so Decrypt all in the
    // form uses this server's Secret Key.
    const key = serverKey(record);
    if (servers.some((s) => serverKey(s) === key)) {
      setChecked((keys) => (keys.includes(key) ? keys : [...keys, key]));
      setPreviewKey(key);
    }
  };

  const openJson = () => {
    setJsonText(JSON.stringify(rows(), null, 2));
    setJsonError(null);
    setJsonOpen(true);
  };

  const applyJson = (mode: 'replace' | 'append') => {
    try {
      const parsed = parseRows(jsonText, t);
      const next = mode === 'replace' ? parsed : [...rows(), ...parsed];
      if (next.length > MAX_ROWS) throw new Error(t('aes.json.tooMany', { max: MAX_ROWS }));
      form.setFieldsValue({ items: next });
      // Errors belonged to the old rows' positions.
      setCells(next.flatMap((_, i) => ROW_FIELDS.map((f) => ({ name: ['items', i, f], errors: [] }))));
      setDecrypted({});
      setJsonOpen(false);
    } catch (e) {
      setJsonError((e as Error).message);
    }
  };

  /**
   * Servers that already hold a value for any property key in the form. Memoised on the *content*:
   * most keystrokes leave the set unchanged, and a new Set object would re-render all of
   * ServerList's checkboxes for nothing. From *all* records: the result list's own filter must not
   * decide which servers the server list calls "saved".
   */
  const savedSignature = useMemo(() => {
    const keys = new Set(formPropertyKeys ? formPropertyKeys.split('\n') : []);
    return allRecords.filter((r) => keys.has(r.propertyKey)).map(serverKey).sort().join('\n');
  }, [allRecords, formPropertyKeys]);
  const savedForProperty = useMemo(() => new Set(savedSignature ? savedSignature.split('\n') : []), [savedSignature]);

  const allChecked = servers.length > 0 && checked.length === servers.length;

  const columns: TableColumnsType<AesRecord> = [
    {
      title: t('aes.records.server'),
      key: 'server',
      width: 240,
      render: (_, r) => (
        <Space direction="vertical" size={0}>
          <Text>{`${r.host}:${r.ip}`}</Text>
          <Space size={4}>
            <Tag bordered={false}>{r.appSystem}</Tag>
            {envOf.get(serverKey(r)) && <Tag bordered={false} color="blue">{envOf.get(serverKey(r))}</Tag>}
          </Space>
        </Space>
      ),
      sorter: (a, b) => a.host.localeCompare(b.host),
    },
    {
      title: t('aes.records.propertyKey'),
      dataIndex: 'propertyKey',
      width: 220,
      filters: [...new Set(records.map((r) => r.propertyKey))].sort().map((k) => ({ text: k, value: k })),
      onFilter: (value, r) => r.propertyKey === value,
      sorter: (a, b) => a.propertyKey.localeCompare(b.propertyKey),
    },
    {
      title: t('aes.records.decrypted'),
      key: 'decrypted',
      width: 220,
      render: (_, r) => {
        const result = decrypted[r.id];
        if (!result) return <Text type="secondary">—</Text>;
        if (result.error) return <Text type="danger">{result.message}</Text>;
        return <Text copyable={{ text: result.plainValue ?? '' }}>{result.plainValue}</Text>;
      },
    },
    {
      // Secret Key = randomkey + host + ip — one cell for both, since the randomkey is its first part.
      // Right after the plaintext: these are what a check against the real config file compares.
      title: t('aes.records.secretKey'),
      dataIndex: 'secretKey',
      width: 300,
      render: (value: string, r) => (
        <Space direction="vertical" size={0}>
          <Text code copyable={{ text: value }} ellipsis={{ tooltip: value }} style={{ maxWidth: 280 }}>{value}</Text>
          {/* Everything the ciphertext was made with, for comparing against the config being checked. */}
          <Text type="secondary" style={{ fontSize: token.fontSizeSM }}>
            {t('aes.records.randomKey')}: {r.randomKey ?? '—'}
          </Text>
          <Text type="secondary" style={{ fontSize: token.fontSizeSM }}>
            IV: {r.iv ?? '—'} · {t('aes.form.salt')}: {r.salt ?? '—'}
          </Text>
        </Space>
      ),
    },
    {
      title: t('aes.records.encrypted'),
      dataIndex: 'encryptedValue',
      width: 240,
      render: (value: string) => <Text code copyable={{ text: value }} ellipsis={{ tooltip: value }} style={{ maxWidth: 220 }}>{value}</Text>,
    },
    {
      title: t('aes.records.updated'),
      dataIndex: 'updatedAt',
      width: 170,
      render: (value: string) => new Date(value).toLocaleString(),
      sorter: (a, b) => a.updatedAt.localeCompare(b.updatedAt),
    },
    {
      title: t('aes.records.actions'),
      key: 'actions',
      width: 150,
      render: (_, r) => (
        <Space size={4}>
          <Button size="small" type="link" onClick={() => load(r)}>{t('aes.records.load')}</Button>
          <Popconfirm title={t('aes.records.deleteOne')} onConfirm={() => remove([r.id])}>
            <Button size="small" type="link" danger>{t('aes.records.delete')}</Button>
          </Popconfirm>
        </Space>
      ),
    },
  ];

  return (
    <PageContainer title={t('aes.title')} subTitle={t('aes.subtitle')}>
      <Space direction="vertical" size={16} style={{ width: '100%' }}>
        <ProCard
          title={t('aes.form.title')}
          subTitle={t('aes.rows.count', { count: rowCount })}
          bordered
          extra={(
            <Space wrap>
              <Text type="secondary">{t('aes.preview.label')}</Text>
              <Select
                style={{ width: 300 }}
                placeholder={t('aes.preview.none')}
                value={previewServer ? serverKey(previewServer) : undefined}
                onChange={setPreviewKey}
                options={checkedServers.map((s) => ({ value: serverKey(s), label: `${s.host}:${s.ip}` }))}
                notFoundContent={t('aes.preview.none')}
              />
              <Button icon={<CodeOutlined />} onClick={openJson}>{t('aes.action.json')}</Button>
            </Space>
          )}
        >
          <Form<FormValues>
            form={form}
            // Every field carries its own label. A single header cannot line up with rows that are two
            // lines tall: its second line ended up above the IV/salt inputs, its first line above nothing.
            layout="vertical"
            initialValues={{ items: [EMPTY_ROW] }}
            onValuesChange={(changed: Partial<FormValues>) => {
              // `changed.items` is sparse: only the edited row is present.
              if (changed.items?.some((row) => row && KEY_FIELDS.some((k) => k in row))) setDecrypted({});
            }}
          >
            <Form.List name="items">
              {(fields, { add, remove: removeRow }) => (
                <>
                  {fields.map(({ key, name }) => (
                    // One block per row: line 1 = IV + salt, line 2 = the rest. The divider keeps
                    // each row's two lines visibly together when there are many rows.
                    <div key={key} style={{ borderBottom: `1px dashed ${token.colorSplit}`, marginBottom: token.marginSM }}>
                    <Row gutter={8} align="top">
                      <Col xs={24} lg={SPANS.iv}>
                        <Form.Item
                          name={[name, 'iv']}
                          {...fieldLabel('iv', t)}
                          rules={[
                            { required: true, message: t('aes.form.required') },
                            {
                              validator: (_, value: string) => (!value || utf8Length(value) === 16
                                ? Promise.resolve()
                                : Promise.reject(new Error(t('aes.form.ivLength', { bytes: utf8Length(value) })))),
                            },
                          ]}
                        >
                          {/* Plain, not Password: shown so it can be compared with the config being checked. */}
                          <Input
                            autoComplete="off"
                            spellCheck={false}
                            placeholder={t('aes.form.iv')}
                            count={{ show: true, max: 16, strategy: utf8Length }}
                          />
                        </Form.Item>
                      </Col>
                      <Col xs={24} lg={SPANS.salt}>
                        <Form.Item name={[name, 'salt']} {...fieldLabel('salt', t)} rules={[{ required: true, message: t('aes.form.required') }]}>
                          {/* Plain like the IV — visible for checking. Neither is ever stored or sent in a URL. */}
                          <Input autoComplete="off" spellCheck={false} maxLength={256} placeholder={t('aes.form.salt')} />
                        </Form.Item>
                      </Col>
                    </Row>
                    <Row gutter={8} align="top">
                      <Col xs={24} lg={SPANS.randomKey}>
                        <Form.Item name={[name, 'randomKey']} {...fieldLabel('randomKey', t)}>
                          {/* Plain, not Password: it is shown in the result list anyway. */}
                          <Input autoComplete="off" spellCheck={false} placeholder={t('aes.form.randomKey')} />
                        </Form.Item>
                      </Col>
                      <Col xs={24} lg={SPANS.secretKey}>
                        <Form.Item shouldUpdate {...fieldLabel('secretKey', t)}>
                          {() => {
                            const randomKey = form.getFieldValue(['items', name, 'randomKey']) as string | undefined;
                            const value = previewServer ? secretKeyOf(randomKey, previewServer) : '';
                            return (
                              <Input
                                readOnly
                                variant="filled"
                                value={value}
                                placeholder={t('aes.preview.none')}
                                title={value}
                              />
                            );
                          }}
                        </Form.Item>
                      </Col>
                      <Col xs={24} lg={SPANS.propertyKey}>
                        <Form.Item name={[name, 'propertyKey']} {...fieldLabel('propertyKey', t)} rules={[{ required: true, message: t('aes.form.required') }]}>
                          <Input placeholder={t('aes.form.propertyKey')} spellCheck={false} />
                        </Form.Item>
                      </Col>
                      <Col xs={24} lg={SPANS.plainValue}>
                        <Form.Item name={[name, 'plainValue']} {...fieldLabel('plainValue', t)}>
                          <Input.TextArea autoSize={{ minRows: 1, maxRows: 4 }} spellCheck={false} placeholder={t('aes.form.plainValue')} />
                        </Form.Item>
                      </Col>
                      <Col xs={24} lg={SPANS.encryptedValue}>
                        <Form.Item name={[name, 'encryptedValue']} {...fieldLabel('encryptedValue', t)}>
                          <Input.TextArea autoSize={{ minRows: 1, maxRows: 4 }} spellCheck={false} placeholder={t('aes.form.encryptedValue')} />
                        </Form.Item>
                      </Col>
                      <Col xs={24} lg={SPANS.actions}>
                        {/* An empty label keeps the buttons on the inputs' line rather than the labels'. */}
                        <Form.Item label=" " colon={false}>
                        <Space size={0}>
                          <Tooltip title={t('aes.rows.duplicate')}>
                            <Button
                              type="text"
                              icon={<CopyOutlined />}
                              disabled={fields.length >= MAX_ROWS}
                              onClick={() => add(normalize(form.getFieldValue(['items', name])), name + 1)}
                            />
                          </Tooltip>
                          <Tooltip title={t('aes.rows.remove')}>
                            <Button
                              type="text"
                              danger
                              icon={<MinusCircleOutlined />}
                              disabled={fields.length === 1}
                              onClick={() => removeRow(name)}
                            />
                          </Tooltip>
                        </Space>
                        </Form.Item>
                      </Col>
                    </Row>
                    </div>
                  ))}
                  <Button
                    type="dashed"
                    block
                    icon={<PlusOutlined />}
                    disabled={fields.length >= MAX_ROWS}
                    onClick={() => add({ ...EMPTY_ROW })}
                    style={{ marginBottom: token.margin }}
                  >
                    {t('aes.rows.add')}
                  </Button>
                </>
              )}
            </Form.List>
            <Space wrap style={{ width: '100%', justifyContent: 'center' }}>
              <Button icon={<ArrowRightOutlined />} type="primary" loading={busy === 'encrypt'} onClick={() => runBatch('encrypt')}>
                {t('aes.action.encrypt')}
              </Button>
              <Button icon={<ArrowLeftOutlined />} loading={busy === 'decrypt'} onClick={() => runBatch('decrypt')}>
                {t('aes.action.decrypt')}
              </Button>
              <Button icon={<SaveOutlined />} loading={busy === 'save'} onClick={save}>
                {t('aes.action.save', { count: checked.length })}
              </Button>
              <Button
                icon={<ClearOutlined />}
                onClick={() => form.setFieldsValue({ items: rows().map((r) => ({ ...r, plainValue: '', encryptedValue: '' })) })}
              >
                {t('aes.action.clear')}
              </Button>
            </Space>
            <Text type="secondary" style={{ display: 'block', textAlign: 'center', marginTop: token.marginXS }}>
              {t('aes.form.algorithm')}
            </Text>
          </Form>
        </ProCard>

        <Modal
          open={jsonOpen}
          title={t('aes.json.title')}
          width={760}
          onCancel={() => setJsonOpen(false)}
          footer={[
            <Button key="cancel" onClick={() => setJsonOpen(false)}>{t('aes.json.cancel')}</Button>,
            <Button key="append" onClick={() => applyJson('append')}>{t('aes.json.append')}</Button>,
            <Button key="replace" type="primary" onClick={() => applyJson('replace')}>{t('aes.json.replace')}</Button>,
          ]}
        >
          <Space direction="vertical" size={8} style={{ width: '100%' }}>
            <Alert type="warning" showIcon message={t('aes.json.hint')} />
            <Input.TextArea
              value={jsonText}
              onChange={(e) => {
                setJsonText(e.target.value);
                setJsonError(null);
              }}
              autoSize={{ minRows: 12, maxRows: 24 }}
              spellCheck={false}
              status={jsonError ? 'error' : undefined}
              style={{ fontFamily: token.fontFamilyCode }}
            />
            {jsonError && <Text type="danger">{jsonError}</Text>}
          </Space>
        </Modal>

        <Row gutter={16}>
          <Col xs={24} lg={8}>
            <ProCard
              title={t('aes.servers.title')}
              bordered
            >
              <ScopeFilter scope={serverScope} onChange={setServerScope} apps={dimensions.appSystem} envs={serverEnvOptions} />
              <Checkbox
                indeterminate={checked.length > 0 && !allChecked}
                checked={allChecked}
                disabled={servers.length === 0}
                onChange={(e) => setChecked(e.target.checked ? servers.map(serverKey) : [])}
              >
                {t('aes.servers.selectAll', { checked: checked.length, total: servers.length })}
              </Checkbox>
              <div
                style={{
                  marginTop: token.marginXS,
                  maxHeight: 420,
                  overflowY: 'auto',
                  border: `1px solid ${token.colorBorderSecondary}`,
                  borderRadius: token.borderRadius,
                  padding: token.paddingXS,
                }}
              >
                <ServerList
                  servers={servers}
                  checked={checked}
                  onChange={setChecked}
                  saved={savedForProperty}
                  showAppSystem={!serverScope.appSystem}
                  emptyText={serversLoading ? t('aes.loading') : t('aes.servers.empty')}
                  savedLabel={t('aes.servers.hasValue')}
                />
              </div>
            </ProCard>
          </Col>
          <Col xs={24} lg={16}>
            <ProCard
              title={t('aes.records.title')}
              subTitle={t('aes.records.subtitle')}
              bordered
              extra={(
                <Space wrap>
                  <Button
                    icon={<UnlockOutlined />}
                    disabled={selectedIds.length === 0}
                    loading={busy === 'decryptRows'}
                    onClick={decryptSelected}
                  >
                    {t('aes.records.decryptSelected', { count: selectedIds.length })}
                  </Button>
                  <Popconfirm
                    title={t('aes.records.deleteSelectedConfirm', { count: selectedIds.length })}
                    disabled={selectedIds.length === 0}
                    onConfirm={() => remove(selectedIds)}
                  >
                    <Button danger icon={<DeleteOutlined />} disabled={selectedIds.length === 0} loading={busy === 'delete'}>
                      {t('aes.records.deleteSelected', { count: selectedIds.length })}
                    </Button>
                  </Popconfirm>
                  <Button icon={<ReloadOutlined />} onClick={() => loadRecords()} loading={recordsLoading}>
                    {t('aes.records.reload')}
                  </Button>
                </Space>
              )}
            >
              <ScopeFilter scope={recordScope} onChange={setRecordScope} apps={dimensions.appSystem} envs={recordEnvOptions} />
              <Table<AesRecord>
                rowKey="id"
                size="small"
                columns={columns}
                dataSource={records}
                loading={recordsLoading}
                scroll={{ x: 1540, y: 420 }}
                pagination={false}
                rowSelection={{ selectedRowKeys: selectedIds, onChange: (keys) => setSelectedIds(keys as number[]) }}
                locale={{ emptyText: <Empty image={Empty.PRESENTED_IMAGE_SIMPLE} description={t('aes.records.empty')} /> }}
              />
            </ProCard>
          </Col>
        </Row>
      </Space>
    </PageContainer>
  );
}

interface ServerListProps {
  servers: AesServer[];
  checked: string[];
  onChange: (keys: string[]) => void;
  saved: Set<string>;
  showAppSystem: boolean;
  emptyText: string;
  savedLabel: string;
}

/**
 * The server checkboxes, memoised. An unfiltered list is ~700 rows, and re-rendering them on every
 * keystroke in the form above took >100 ms per character in dev mode.
 */
const ServerList = memo(function ServerList({
  servers, checked, onChange, saved, showAppSystem, emptyText, savedLabel,
}: ServerListProps) {
  const { token } = theme.useToken();
  if (servers.length === 0) {
    return <Empty image={Empty.PRESENTED_IMAGE_SIMPLE} description={emptyText} />;
  }
  return (
    <Checkbox.Group value={checked} onChange={(values) => onChange(values as string[])} style={{ width: '100%' }}>
      <Space direction="vertical" size={2} style={{ width: '100%' }}>
        {servers.map((s) => (
          <Checkbox key={serverKey(s)} value={serverKey(s)}>
            <Text>{`${s.host}:${s.ip}`}</Text>
            {/* The env instance always — an app-system filter does not make it redundant. The app
                system only when the list spans several. */}
            {showAppSystem && <Tag bordered={false} style={{ marginInlineStart: token.marginXS }}>{s.appSystem}</Tag>}
            {s.envInstance && (
              <Tag bordered={false} color="blue" style={{ marginInlineStart: showAppSystem ? token.marginXXS : token.marginXS }}>
                {s.envInstance}
              </Tag>
            )}
            {saved.has(serverKey(s)) && (
              <Tag color="processing" bordered={false} style={{ marginInlineStart: token.marginXXS }}>
                {savedLabel}
              </Tag>
            )}
          </Checkbox>
        ))}
      </Space>
    </Checkbox.Group>
  );
});

interface ScopeFilterProps {
  scope: Scope;
  onChange: (scope: Scope) => void;
  apps: string[];
  envs: string[];
}

/** App system (one or all) + env instances (any), the same control on both lists. */
function ScopeFilter({ scope, onChange, apps, envs }: ScopeFilterProps) {
  const { t } = useI18n();
  const { token } = theme.useToken();
  return (
    <Space wrap style={{ marginBottom: token.marginSM }}>
      <Select
        allowClear
        showSearch
        style={{ width: 170 }}
        placeholder={t('aes.servers.allApps')}
        value={scope.appSystem}
        onChange={(appSystem?: string) => onChange({ ...scope, appSystem })}
        options={apps.map((a) => ({ label: a, value: a }))}
      />
      <Select
        mode="multiple"
        allowClear
        style={{ minWidth: 200 }}
        maxTagCount="responsive"
        placeholder={t('aes.filter.allEnvs')}
        value={scope.envInstances}
        onChange={(envInstances: string[]) => onChange({ ...scope, envInstances })}
        options={envs.map((e) => ({ label: e, value: e }))}
      />
    </Space>
  );
}
