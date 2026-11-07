import { useCallback, useEffect, useMemo, useState } from 'react';
import type { Key } from 'react';
import { PageContainer, ProCard } from '@ant-design/pro-components';
import { App, Button, Form, Input, InputNumber, Modal, Popconfirm, Select, Space, Table, Tag, Tooltip, TreeSelect, Upload } from 'antd';
import type { TableColumnsType } from 'antd';
import {
  CopyOutlined,
  DownloadOutlined,
  ExportOutlined,
  NodeExpandOutlined,
  PlusOutlined,
  ReloadOutlined,
  SearchOutlined,
  UploadOutlined,
} from '@ant-design/icons';
import { ccrgApi, saveFile } from '../../../api/ccrg';
import type { DictVO, ExtraTypeVO } from '../../../api/ccrg';
import { useI18n } from '../../../locales';
import { errorText } from '../shared';

/**
 * DictManagement (`/ccrg/dict`): the config-folder tree (project → release → country → app → …) the
 * comparison cascades walk, with node / zone tags, plus JSON export/import and a `data.sql` dump.
 * Migrated from `ccrg-tool-ui/src/views/system/dict`.
 */

type DictNode = DictVO & { id: number; level: number; children?: DictNode[] };

/** Rows whose parent is not in the list are roots — the same rule as the old `handleTree`. */
function toTree(list: DictVO[]): DictNode[] {
  const byParent = new Map<number, DictVO[]>();
  const ids = new Set(list.map((d) => d.id));
  list.forEach((d) => {
    const siblings = byParent.get(d.parentId) ?? [];
    siblings.push(d);
    byParent.set(d.parentId, siblings);
  });
  const build = (d: DictVO, level: number): DictNode => {
    const kids = (byParent.get(d.id!) ?? []).sort((a, b) => a.sort - b.sort).map((k) => build(k, level + 1));
    return { ...d, id: d.id!, level, children: kids.length ? kids : undefined };
  };
  return list
    .filter((d) => !ids.has(d.parentId))
    .sort((a, b) => a.sort - b.sort)
    .map((d) => build(d, 1));
}

const allKeys = (nodes: DictNode[]): Key[] => nodes.flatMap((n) => (n.children ? [n.id, ...allKeys(n.children)] : []));

function downloadJson(value: unknown) {
  saveFile({ blob: new Blob([JSON.stringify(value)], { type: 'application/json' }), filename: 'system_dict_config.json' });
}

export function DictPage() {
  const { t } = useI18n();
  const { message } = App.useApp();
  const [list, setList] = useState<DictVO[]>([]);
  const [loading, setLoading] = useState(true);
  const [name, setName] = useState('');
  const [nodes, setNodes] = useState<ExtraTypeVO[]>([]);
  const [zones, setZones] = useState<ExtraTypeVO[]>([]);
  const [expanded, setExpanded] = useState<Key[]>([]);
  const [editing, setEditing] = useState<{ id?: number } | null>(null);

  const tree = useMemo(() => toTree(list), [list]);

  const load = useCallback(
    async (keyword?: string) => {
      setLoading(true);
      try {
        setList(await ccrgApi.dictList(keyword || undefined));
      } catch (e) {
        message.error(errorText(e));
      } finally {
        setLoading(false);
      }
    },
    [message],
  );

  useEffect(() => {
    Promise.all([ccrgApi.dictExtraTypes('NODE'), ccrgApi.dictExtraTypes('ZONE')])
      .then(([n, z]) => {
        setNodes(n);
        setZones(z);
      })
      .catch((e) => message.error(errorText(e)));
    void load();
  }, [load, message]);

  const act = async (work: () => Promise<unknown>, done: string) => {
    setLoading(true);
    try {
      await work();
      message.success(done);
      await load(name);
    } catch (e) {
      message.error(errorText(e));
      setLoading(false);
    }
  };

  /**
   * Export keeps the import's shape: a level-2 row goes out wrapped in its project (with only itself as
   * child), so re-importing it lands under the right parent. `triggeredLevel` 0 = the whole tree.
   */
  const exportRow = (row: DictNode) => {
    if (row.level > 1) {
      const parent = tree.find((p) => p.id === row.parentId);
      downloadJson({ triggeredLevel: row.level, treeData: [{ ...parent, children: [row] }] });
    } else {
      downloadJson({ triggeredLevel: row.level, treeData: [row] });
    }
    message.success(t('ccrg.downloaded'));
  };

  const downloadSql = async () => {
    try {
      saveFile(await ccrgApi.dictDownloadSql());
      message.success(t('ccrg.downloaded'));
    } catch (e) {
      message.error(errorText(e));
    }
  };

  const extraName = (all: ExtraTypeVO[], id?: number) => all.find((x) => x.id === id)?.val;

  const columns: TableColumnsType<DictNode> = [
    { title: t('ccrg.col.name'), dataIndex: 'name' },
    { title: t('ccrg.dict.node'), dataIndex: 'nodeId', width: 140, render: (id?: number) => extraName(nodes, id) },
    { title: t('ccrg.dict.zone'), dataIndex: 'zoneId', width: 140, render: (id?: number) => extraName(zones, id) },
    { title: t('ccrg.dict.sort'), dataIndex: 'sort', width: 80 },
    {
      title: t('ccrg.dict.status'),
      dataIndex: 'status',
      width: 110,
      render: (s: number) => (s === 0 ? <Tag color="processing">{t('ccrg.dict.enabled')}</Tag> : <Tag>{t('ccrg.dict.disabled')}</Tag>),
    },
    {
      title: t('ccrg.col.operation'),
      key: 'op',
      width: 280,
      render: (_, row) => (
        <Space size={0}>
          <Button type="link" size="small" onClick={() => setEditing({ id: row.id })}>
            {t('ccrg.edit')}
          </Button>
          <Popconfirm title={t('ccrg.dict.deleteConfirm')} onConfirm={() => act(() => ccrgApi.dictDelete(row.id), t('ccrg.dict.deleted'))}>
            <Button type="link" size="small" danger>
              {t('ccrg.delete')}
            </Button>
          </Popconfirm>
          <Tooltip title={t('ccrg.dict.copy')}>
            <Button type="link" size="small" icon={<CopyOutlined />} onClick={() => act(() => ccrgApi.dictCopy(row.id), t('ccrg.dict.copied'))} />
          </Tooltip>
          {row.level <= 2 && (
            <Button type="link" size="small" onClick={() => exportRow(row)}>
              {t('ccrg.dict.export')}
            </Button>
          )}
        </Space>
      ),
    },
  ];

  return (
    <PageContainer title={t('nav.ccrg.dict')}>
      <Space direction="vertical" size="middle" style={{ width: '100%' }}>
        <ProCard bordered>
          <Form layout="inline" onFinish={() => load(name)}>
            <Form.Item label={t('ccrg.col.name')}>
              <Input allowClear style={{ width: 240 }} value={name} onChange={(e) => setName(e.target.value)} />
            </Form.Item>
            <Space wrap>
              <Button htmlType="submit" icon={<SearchOutlined />}>
                {t('ccrg.search')}
              </Button>
              <Button
                icon={<ReloadOutlined />}
                onClick={() => {
                  setName('');
                  void load();
                }}
              >
                {t('ccrg.reset')}
              </Button>
              <Button type="primary" icon={<PlusOutlined />} onClick={() => setEditing({})}>
                {t('ccrg.add')}
              </Button>
              <Button icon={<NodeExpandOutlined />} onClick={() => setExpanded((e) => (e.length ? [] : allKeys(tree)))}>
                {t('ccrg.dict.expandAll')}
              </Button>
              <Button icon={<DownloadOutlined />} onClick={downloadSql}>
                {t('ccrg.dict.exportSql')}
              </Button>
              <Button icon={<ExportOutlined />} onClick={() => downloadJson({ triggeredLevel: 0, treeData: tree })}>
                {t('ccrg.dict.exportAll')}
              </Button>
              <Upload
                accept="application/json,.json"
                showUploadList={false}
                beforeUpload={(file) => {
                  if (file.type !== 'application/json' && !file.name.endsWith('.json')) {
                    message.error(t('ccrg.dict.jsonOnly'));
                  } else {
                    void act(async () => {
                      if (!(await ccrgApi.dictImport(file))) throw new Error(t('ccrg.dict.importFailed'));
                    }, t('ccrg.dict.imported'));
                  }
                  return false; // uploaded through the API wrapper, not antd's XHR
                }}
              >
                <Button type="primary" icon={<UploadOutlined />}>
                  {t('ccrg.dict.import')}
                </Button>
              </Upload>
            </Space>
          </Form>
        </ProCard>

        <ProCard bordered>
          <Table<DictNode>
            rowKey="id"
            size="middle"
            loading={loading}
            columns={columns}
            dataSource={tree}
            pagination={false}
            expandable={{ expandedRowKeys: expanded, onExpandedRowsChange: (keys) => setExpanded([...keys]) }}
          />
        </ProCard>
      </Space>

      <DictFormModal
        target={editing}
        nodes={nodes}
        zones={zones}
        onClose={() => setEditing(null)}
        onSaved={() => {
          setEditing(null);
          void load(name);
        }}
      />
    </PageContainer>
  );
}

interface TreeOption {
  value: number;
  title: string;
  children?: TreeOption[];
}

const toOptions = (nodes: DictNode[]): TreeOption[] =>
  nodes.map((n) => ({ value: n.id, title: n.name, children: n.children ? toOptions(n.children) : undefined }));

/** Create (`target = {}`) or edit (`target.id`). The parent picker offers only enabled rows, under a synthetic "Top level" 0. */
function DictFormModal({
  target,
  nodes,
  zones,
  onClose,
  onSaved,
}: {
  target: { id?: number } | null;
  nodes: ExtraTypeVO[];
  zones: ExtraTypeVO[];
  onClose: () => void;
  onSaved: () => void;
}) {
  const { t } = useI18n();
  const { message } = App.useApp();
  const [form] = Form.useForm<DictVO>();
  const [busy, setBusy] = useState(false);
  const [parents, setParents] = useState<TreeOption[]>([]);
  const open = target !== null;

  useEffect(() => {
    if (!target) return;
    form.resetFields();
    form.setFieldsValue({ parentId: undefined, sort: 0, status: 0 });
    setBusy(true);
    Promise.all([ccrgApi.dictSimple(), target.id != null ? ccrgApi.dictGet(target.id) : Promise.resolve(undefined)])
      .then(([simple, current]) => {
        setParents([{ value: 0, title: t('ccrg.dict.topLevel'), children: toOptions(toTree(simple)) }]);
        if (current) form.setFieldsValue(current);
      })
      .catch((e) => message.error(errorText(e)))
      .finally(() => setBusy(false));
  }, [target, form, message, t]);

  const submit = async () => {
    const values = await form.validateFields();
    setBusy(true);
    try {
      if (target?.id != null) await ccrgApi.dictUpdate({ ...values, id: target.id });
      else await ccrgApi.dictCreate(values);
      message.success(t('ccrg.saved'));
      onSaved();
    } catch (e) {
      message.error(errorText(e));
    } finally {
      setBusy(false);
    }
  };

  const required = [{ required: true, message: t('ccrg.required') }];
  const extraOptions = (all: ExtraTypeVO[]) => all.map((x) => ({ label: x.val, value: x.id }));
  return (
    <Modal
      open={open}
      title={target?.id != null ? t('ccrg.edit') : t('ccrg.add')}
      onCancel={onClose}
      onOk={submit}
      okText={t('ccrg.submit')}
      confirmLoading={busy}
      destroyOnHidden
    >
      <Form form={form} layout="horizontal" labelCol={{ flex: '120px' }} disabled={busy}>
        <Form.Item name="parentId" label={t('ccrg.dict.parent')} rules={required}>
          <TreeSelect showSearch treeNodeFilterProp="title" treeDefaultExpandAll treeData={parents} />
        </Form.Item>
        <Form.Item name="name" label={t('ccrg.col.name')} rules={[...required, { max: 30 }]}>
          <Input />
        </Form.Item>
        <Form.Item name="sort" label={t('ccrg.dict.sort')} rules={required}>
          <InputNumber min={0} />
        </Form.Item>
        <Form.Item name="nodeId" label={t('ccrg.dict.node')}>
          <Select allowClear options={extraOptions(nodes)} />
        </Form.Item>
        <Form.Item name="zoneId" label={t('ccrg.dict.zone')}>
          <Select allowClear options={extraOptions(zones)} />
        </Form.Item>
        <Form.Item name="status" label={t('ccrg.dict.status')} rules={required}>
          <Select
            options={[
              { label: t('ccrg.dict.enabled'), value: 0 },
              { label: t('ccrg.dict.disabled'), value: 1 },
            ]}
          />
        </Form.Item>
      </Form>
    </Modal>
  );
}
