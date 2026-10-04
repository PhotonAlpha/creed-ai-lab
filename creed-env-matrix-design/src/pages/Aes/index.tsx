import { memo, useCallback, useEffect, useMemo, useRef, useState } from 'react';
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
import type { AesBatchCryptoResult, AesKeyValueRow, AesRecord, AesRecordDecryptResult, AesServer } from '../../api/types';

const { Text } = Typography;

interface FormValues {
  items: AesKeyValueRow[];
}

/** Field order of a row — also the JSON shape the "Edit as JSON" dialog reads and writes. */
const ROW_FIELDS = ['secretKey', 'iv', 'randomKey', 'propertyKey', 'plainValue', 'encryptedValue'] as const;
type RowField = (typeof ROW_FIELDS)[number];
const KEY_FIELDS: readonly RowField[] = ['secretKey', 'iv', 'randomKey'];
const EMPTY_ROW: AesKeyValueRow = { secretKey: '', iv: '', randomKey: '', propertyKey: '', plainValue: '', encryptedValue: '' };
/** The batch endpoints' limit. */
const MAX_ROWS = 200;

const utf8Length = (text: string) => new TextEncoder().encode(text).length;
const serverKey = (s: AesServer) => `${s.appSystem}\u0000${s.host}\u0000${s.ip}`;
const normalize = (row: Partial<AesKeyValueRow> | undefined): AesKeyValueRow => ({ ...EMPTY_ROW, ...row });

/**
 * Column spans of a "Keys and values" row; the header uses the same ones. Below `lg` every field
 * takes the full width and the header is hidden — each input then carries its name as placeholder.
 */
const SPANS: Record<RowField | 'actions', number> = {
  secretKey: 3, iv: 3, randomKey: 3, propertyKey: 4, plainValue: 4, encryptedValue: 5, actions: 2,
};

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
 * "Keys and values" is an array of complete, independent rows — each its own Secret Key, IV,
 * randomkey, property key, plain and encrypted value — edited as rows or as a JSON array. The
 * Secret Key and IV are form state only: sent with each request and never stored. A save keeps each
 * row's ciphertext and randomkey, once per ticked server; the randomkey alone decrypts nothing.
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

  const [appSystem, setAppSystem] = useState<string | undefined>();
  const [servers, setServers] = useState<AesServer[]>([]);
  const [serversLoading, setServersLoading] = useState(false);
  const [checked, setChecked] = useState<string[]>([]);

  const [records, setRecords] = useState<AesRecord[]>([]);
  const [recordsLoading, setRecordsLoading] = useState(false);
  const [selectedIds, setSelectedIds] = useState<number[]>([]);
  const [decrypted, setDecrypted] = useState<Record<number, AesRecordDecryptResult>>({});

  const [busy, setBusy] = useState<'encrypt' | 'decrypt' | 'save' | 'decryptRows' | 'delete' | null>(null);
  const [jsonOpen, setJsonOpen] = useState(false);
  const [jsonText, setJsonText] = useState('');
  const [jsonError, setJsonError] = useState<string | null>(null);

  const watchedItems = Form.useWatch('items', form) as AesKeyValueRow[] | undefined;
  const rowCount = watchedItems?.length ?? 1;
  /** Property keys present in the form, for the server list's "saved" tags. */
  const formPropertyKeys = (watchedItems ?? []).map((r) => r?.propertyKey?.trim()).filter(Boolean).sort().join('\n');

  // Both lists follow the app filter; a request id guards against a slow response for the previous
  // filter landing after the current one.
  const serversRequest = useRef(0);
  const loadServers = useCallback(async (app?: string) => {
    const id = ++serversRequest.current;
    setServersLoading(true);
    try {
      const rows = await aesApi.servers(app);
      if (id === serversRequest.current) setServers(rows);
    } catch (e) {
      message.error((e as Error).message);
    } finally {
      if (id === serversRequest.current) setServersLoading(false);
    }
  }, [message]);

  const recordsRequest = useRef(0);
  const loadRecords = useCallback(async (app?: string) => {
    const id = ++recordsRequest.current;
    setRecordsLoading(true);
    try {
      const rows = await aesApi.records(app);
      if (id === recordsRequest.current) {
        setRecords(rows);
        setSelectedIds((ids) => ids.filter((x) => rows.some((r) => r.id === x)));
      }
    } catch (e) {
      message.error((e as Error).message);
    } finally {
      if (id === recordsRequest.current) setRecordsLoading(false);
    }
  }, [message]);

  useEffect(() => {
    void loadServers(appSystem);
    void loadRecords(appSystem);
  }, [appSystem, loadServers, loadRecords]);

  // Keep only ticks that are still listed — a hidden server must not be saved to by accident.
  useEffect(() => {
    const listed = new Set(servers.map(serverKey));
    setChecked((keys) => keys.filter((k) => listed.has(k)));
  }, [servers]);

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
    const invalid = await invalidRows(candidates.map((c) => c.index), ['secretKey', 'iv']);
    const picked = candidates.filter((c) => !invalid.has(c.index));
    if (picked.length === 0) return; // every row is marked on the form

    setBusy(direction);
    try {
      const items = picked.map(({ row }) => ({ secretKey: row.secretKey, iv: row.iv, randomKey: row.randomKey, value: row[source] }));
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

  /** Every row with a property key or an encrypted value, to every ticked server, in one call. */
  const save = async () => {
    const all = rows();
    const picked = all.flatMap((row, index) => (row.propertyKey.trim() || row.encryptedValue.trim() ? [{ row, index }] : []));
    if (picked.length === 0) {
      message.warning(t('aes.rows.nothingToSave'));
      return;
    }
    if (!(await validCells(picked.flatMap(({ index }) => [['items', index, 'propertyKey'], ['items', index, 'encryptedValue']])))) return;

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
        items: picked.map(({ row }) => ({ propertyKey: row.propertyKey, encryptedValue: row.encryptedValue, randomKey: row.randomKey })),
        servers: targets,
      });
      message.success(t('aes.save.done', {
        items: picked.length, servers: targets.length, inserted: result.inserted, updated: result.updated,
      }));
      await loadRecords(appSystem);
    } catch (e) {
      report(e, (i) => picked[i]?.index ?? i);
    } finally {
      setBusy(null);
    }
  };

  /**
   * Decrypts the ticked records, each with the keys of the form row that has the same property key.
   * With a single form row, that row's keys are used for every record — the one-set-of-keys case.
   */
  const decryptSelected = async () => {
    const all = rows();
    const withSecret = all.filter((r) => r.secretKey !== '');
    const items: { id: number; secretKey: string; iv: string; randomKey: string }[] = [];
    const missing: AesRecordDecryptResult[] = [];
    for (const id of selectedIds) {
      const record = records.find((r) => r.id === id);
      if (!record) continue;
      const keys = all.find((r) => r.propertyKey.trim() === record.propertyKey && r.secretKey !== '')
        ?? (all.length === 1 && withSecret.length === 1 ? withSecret[0] : undefined);
      if (keys) {
        items.push({ id, secretKey: keys.secretKey, iv: keys.iv, randomKey: keys.randomKey });
      } else {
        missing.push({ id, plainValue: null, error: 'not_found', message: t('aes.records.noKeys', { key: record.propertyKey }) });
      }
    }
    setBusy('decryptRows');
    try {
      const results = items.length ? await aesApi.decryptRecords(items) : [];
      const all2 = [...results, ...missing];
      setDecrypted((prev) => ({ ...prev, ...Object.fromEntries(all2.map((r) => [r.id, r])) }));
      const failed = all2.filter((r) => r.error).length;
      if (failed) message.warning(t('aes.records.decryptPartial', { failed, total: all2.length }));
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
      await loadRecords(appSystem);
    } catch (e) {
      message.error((e as Error).message);
    } finally {
      setBusy(null);
    }
  };

  /**
   * Puts a saved record into the form — into the row with the same property key, else into a single
   * blank row, else as a new row — and ticks its server.
   */
  const load = (record: AesRecord) => {
    const all = rows();
    const loaded = { propertyKey: record.propertyKey, encryptedValue: record.encryptedValue, randomKey: record.randomKey ?? '', plainValue: '' };
    let index = all.findIndex((r) => r.propertyKey.trim() === record.propertyKey);
    if (index < 0 && all.length === 1 && ROW_FIELDS.every((f) => all[0][f] === '')) index = 0;
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
    const key = serverKey(record);
    if (servers.some((s) => serverKey(s) === key)) {
      setChecked((keys) => (keys.includes(key) ? keys : [...keys, key]));
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
   * ServerList's checkboxes for nothing.
   */
  const savedSignature = useMemo(() => {
    const keys = new Set(formPropertyKeys ? formPropertyKeys.split('\n') : []);
    return records.filter((r) => keys.has(r.propertyKey)).map(serverKey).sort().join('\n');
  }, [records, formPropertyKeys]);
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
          <Tag bordered={false}>{r.appSystem}</Tag>
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
      title: t('aes.records.encrypted'),
      dataIndex: 'encryptedValue',
      width: 240,
      render: (value: string) => <Text code copyable={{ text: value }} ellipsis={{ tooltip: value }} style={{ maxWidth: 220 }}>{value}</Text>,
    },
    {
      title: t('aes.records.randomKey'),
      dataIndex: 'randomKey',
      width: 150,
      render: (value: string | null) => (value
        ? <Text code copyable={{ text: value }} ellipsis={{ tooltip: value }} style={{ maxWidth: 130 }}>{value}</Text>
        : <Text type="secondary">—</Text>),
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
          extra={<Button icon={<CodeOutlined />} onClick={openJson}>{t('aes.action.json')}</Button>}
        >
          <Form<FormValues>
            form={form}
            initialValues={{ items: [EMPTY_ROW] }}
            onValuesChange={(changed: Partial<FormValues>) => {
              // `changed.items` is sparse: only the edited row is present.
              if (changed.items?.some((row) => row && KEY_FIELDS.some((k) => k in row))) setDecrypted({});
            }}
          >
            <Row gutter={8} style={{ marginBottom: token.marginXS }}>
              {ROW_FIELDS.map((f) => (
                <Col key={f} xs={0} lg={SPANS[f]}>
                  <Text strong>{t(`aes.form.${f}`)}</Text>
                  {f === 'iv' && (
                    <Tooltip title={t('aes.form.ivHint')}><Text type="secondary"> ⓘ</Text></Tooltip>
                  )}
                  {f === 'randomKey' && (
                    <Tooltip title={t('aes.form.randomKeyHint')}><Text type="secondary"> ⓘ</Text></Tooltip>
                  )}
                </Col>
              ))}
            </Row>
            <Form.List name="items">
              {(fields, { add, remove: removeRow }) => (
                <>
                  {fields.map(({ key, name }) => (
                    <Row key={key} gutter={8} align="top">
                      <Col xs={24} lg={SPANS.secretKey}>
                        <Form.Item name={[name, 'secretKey']} rules={[{ required: true, message: t('aes.form.required') }]}>
                          <Input.Password autoComplete="off" placeholder={t('aes.form.secretKey')} />
                        </Form.Item>
                      </Col>
                      <Col xs={24} lg={SPANS.iv}>
                        <Form.Item
                          name={[name, 'iv']}
                          rules={[
                            { required: true, message: t('aes.form.required') },
                            {
                              validator: (_, value: string) => (!value || utf8Length(value) === 16
                                ? Promise.resolve()
                                : Promise.reject(new Error(t('aes.form.ivLength', { bytes: utf8Length(value) })))),
                            },
                          ]}
                        >
                          <Input.Password
                            autoComplete="off"
                            placeholder={t('aes.form.iv')}
                            count={{ show: true, max: 16, strategy: utf8Length }}
                          />
                        </Form.Item>
                      </Col>
                      <Col xs={24} lg={SPANS.randomKey}>
                        <Form.Item name={[name, 'randomKey']}>
                          {/* Plain, not Password: it is shown in the result list anyway. */}
                          <Input autoComplete="off" spellCheck={false} placeholder={t('aes.form.randomKey')} />
                        </Form.Item>
                      </Col>
                      <Col xs={24} lg={SPANS.propertyKey}>
                        <Form.Item name={[name, 'propertyKey']} rules={[{ required: true, message: t('aes.form.required') }]}>
                          <Input placeholder={t('aes.form.propertyKey')} spellCheck={false} />
                        </Form.Item>
                      </Col>
                      <Col xs={24} lg={SPANS.plainValue}>
                        <Form.Item name={[name, 'plainValue']}>
                          <Input.TextArea autoSize={{ minRows: 1, maxRows: 4 }} spellCheck={false} placeholder={t('aes.form.plainValue')} />
                        </Form.Item>
                      </Col>
                      <Col xs={24} lg={SPANS.encryptedValue}>
                        <Form.Item name={[name, 'encryptedValue']} rules={[{ required: true, message: t('aes.form.required') }]}>
                          <Input.TextArea autoSize={{ minRows: 1, maxRows: 4 }} spellCheck={false} placeholder={t('aes.form.encryptedValue')} />
                        </Form.Item>
                      </Col>
                      <Col xs={24} lg={SPANS.actions}>
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
                      </Col>
                    </Row>
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
              extra={(
                <Select
                  allowClear
                  showSearch
                  style={{ width: 180 }}
                  placeholder={t('aes.servers.allApps')}
                  value={appSystem}
                  onChange={setAppSystem}
                  options={dimensions.appSystem.map((a) => ({ label: a, value: a }))}
                />
              )}
            >
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
                  showAppSystem={!appSystem}
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
                  <Button icon={<ReloadOutlined />} onClick={() => loadRecords(appSystem)} loading={recordsLoading}>
                    {t('aes.records.reload')}
                  </Button>
                </Space>
              )}
            >
              <Table<AesRecord>
                rowKey="id"
                size="small"
                columns={columns}
                dataSource={records}
                loading={recordsLoading}
                scroll={{ x: 1390, y: 420 }}
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
            {showAppSystem && <Tag bordered={false} style={{ marginInlineStart: token.marginXS }}>{s.appSystem}</Tag>}
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
