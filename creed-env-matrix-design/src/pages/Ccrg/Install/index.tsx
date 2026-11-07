import { useCallback, useEffect, useMemo, useState } from 'react';
import type { Key } from 'react';
import { PageContainer, ProCard } from '@ant-design/pro-components';
import { App, Button, Form, Input, Modal, Popconfirm, Select, Space, Table } from 'antd';
import type { TableColumnsType } from 'antd';
import { DownloadOutlined, ReloadOutlined, SearchOutlined } from '@ant-design/icons';
import { ccrgApi } from '../../../api/ccrg';
import type { InstalledInfoVo, SelectorVo, WalkPathInfoVo } from '../../../api/ccrg';
import { useI18n } from '../../../locales';
import { errorText, toOptions } from '../shared';

/**
 * Pull Config: the releases installed from Artifactory into the comparison tool's config folder,
 * one tree per project. Migrated from `ccrg-tool-ui/src/views/Install`.
 */

/** A release name is only unique within its project, so the key carries both. */
const rowKey = (row: InstalledInfoVo) => `${row.parentName ?? ''}/${row.name ?? ''}`;

const isProject = (row: InstalledInfoVo) => !!row.children?.length;

/** The `{projectName, releaseName}` pairs a row stands for: a project row means all its releases. */
function toUninstall(row: InstalledInfoVo): WalkPathInfoVo[] {
  if (isProject(row)) {
    return row.children!.map((c) => ({ projectName: c.parentName, releaseName: c.releaseName ?? c.name }));
  }
  return [{ projectName: row.parentName, releaseName: row.name }];
}

export function InstallPage() {
  const { t } = useI18n();
  const { message } = App.useApp();
  const [rows, setRows] = useState<InstalledInfoVo[]>([]);
  const [loading, setLoading] = useState(true);
  const [keyword, setKeyword] = useState('');
  const [applied, setApplied] = useState('');
  const [selected, setSelected] = useState<InstalledInfoVo[]>([]);
  const [selectedKeys, setSelectedKeys] = useState<Key[]>([]);
  const [installOpen, setInstallOpen] = useState(false);
  const [expanded, setExpanded] = useState<Key[]>([]);

  const load = useCallback(async () => {
    setLoading(true);
    try {
      const data = await ccrgApi.installed();
      setRows(data);
      // Controlled: antd's defaultExpandAllRows only applies to rows present on the first render,
      // and the first render has none — the list arrives afterwards.
      setExpanded(data.filter(isProject).map(rowKey));
    } catch (e) {
      message.error(errorText(e));
    } finally {
      setLoading(false);
    }
  }, [message]);

  useEffect(() => {
    void load();
  }, [load]);

  // The old page's search was a "not available yet" stub; filtering the project rows client-side is
  // what it was meant to do, and the list is one tree per project — small.
  const visible = useMemo(() => {
    const k = applied.trim().toLowerCase();
    return k ? rows.filter((r) => (r.name ?? '').toLowerCase().includes(k)) : rows;
  }, [rows, applied]);

  const uninstall = async (list: WalkPathInfoVo[]) => {
    if (list.length === 0) return;
    setLoading(true);
    try {
      await ccrgApi.uninstall(list);
      message.success(t('ccrg.install.uninstalled'));
      setSelected([]);
      setSelectedKeys([]);
      await load();
    } catch (e) {
      message.error(errorText(e));
      setLoading(false);
    }
  };

  const columns: TableColumnsType<InstalledInfoVo> = [
    { title: t('ccrg.install.col.name'), dataIndex: 'name' },
    { title: t('ccrg.install.col.updated'), dataIndex: 'lastUpdateTime', width: 260 },
    {
      title: t('ccrg.col.operation'),
      key: 'op',
      width: 160,
      render: (_, row) => (
        <Popconfirm title={t('ccrg.install.confirm')} onConfirm={() => uninstall(toUninstall(row))}>
          <Button type="link" danger size="small">
            {t('ccrg.install.uninstall')}
          </Button>
        </Popconfirm>
      ),
    },
  ];

  return (
    <PageContainer title={t('nav.ccrg.install')}>
      <Space direction="vertical" size="middle" style={{ width: '100%' }}>
        <ProCard bordered>
          <Form layout="inline" onFinish={() => setApplied(keyword)}>
            <Form.Item label={t('ccrg.field.project')}>
              <Input
                allowClear
                style={{ width: 240 }}
                placeholder={t('ccrg.install.searchPlaceholder')}
                value={keyword}
                onChange={(e) => setKeyword(e.target.value)}
              />
            </Form.Item>
            <Space wrap>
              <Button htmlType="submit" icon={<SearchOutlined />}>
                {t('ccrg.search')}
              </Button>
              <Button
                icon={<ReloadOutlined />}
                onClick={() => {
                  setKeyword('');
                  setApplied('');
                  void load();
                }}
              >
                {t('ccrg.reset')}
              </Button>
              <Button type="primary" icon={<DownloadOutlined />} onClick={() => setInstallOpen(true)}>
                {t('ccrg.install.install')}
              </Button>
            </Space>
          </Form>
        </ProCard>

        <ProCard bordered>
          <Table<InstalledInfoVo>
            rowKey={rowKey}
            loading={loading}
            columns={columns}
            dataSource={visible}
            pagination={false}
            expandable={{ expandedRowKeys: expanded, onExpandedRowsChange: (keys) => setExpanded([...keys]) }}
            // Project rows are tinted so the tree reads as groups, as the old page did.
            rowClassName={(row) => (isProject(row) ? 'ccrg-row--group' : '')}
            rowSelection={{
              selectedRowKeys: selectedKeys,
              onChange: (keys, rowsSelected) => {
                setSelectedKeys(keys);
                setSelected(rowsSelected);
              },
            }}
          />
          <Space style={{ marginTop: 16 }}>
            <Popconfirm
              title={t('ccrg.install.confirm')}
              disabled={selected.length === 0}
              onConfirm={() =>
                // Selection cascades, so the leaves are the whole answer — a project row would repeat them.
                uninstall(selected.filter((r) => !isProject(r)).map((r) => ({ projectName: r.parentName, releaseName: r.name })))
              }
            >
              <Button type="primary" danger disabled={selected.length === 0}>
                {t('ccrg.install.uninstallSelected')}
              </Button>
            </Popconfirm>
            <Button
              onClick={() => {
                setSelected([]);
                setSelectedKeys([]);
              }}
            >
              {t('ccrg.clearSelection')}
            </Button>
          </Space>
        </ProCard>
      </Space>

      <InstallModal open={installOpen} onClose={() => setInstallOpen(false)} onInstalled={load} />
    </PageContainer>
  );
}

interface InstallValues {
  projectName: string;
  releaseName: string;
  buildNo: string;
}

/** Project → release → build: each pick loads the next level from Artifactory and clears the rest. */
function InstallModal({ open, onClose, onInstalled }: { open: boolean; onClose: () => void; onInstalled: () => void }) {
  const { t } = useI18n();
  const { message } = App.useApp();
  const [form] = Form.useForm<InstallValues>();
  const [busy, setBusy] = useState(false);
  const [projects, setProjects] = useState<SelectorVo[]>([]);
  const [releases, setReleases] = useState<SelectorVo[]>([]);
  const [builds, setBuilds] = useState<SelectorVo[]>([]);

  useEffect(() => {
    if (!open) return;
    form.resetFields();
    setReleases([]);
    setBuilds([]);
    setBusy(true);
    ccrgApi
      .projects()
      .then(setProjects)
      .catch((e) => message.error(errorText(e)))
      .finally(() => setBusy(false));
  }, [open, form, message]);

  const step = async (fetch: () => Promise<SelectorVo[]>, set: (s: SelectorVo[]) => void) => {
    setBusy(true);
    try {
      set(await fetch());
    } catch (e) {
      message.error(errorText(e));
    } finally {
      setBusy(false);
    }
  };

  const submit = async () => {
    const values = await form.validateFields();
    setBusy(true);
    try {
      await ccrgApi.install(values);
      message.success(t('ccrg.install.installed'));
      onClose();
      onInstalled();
    } catch (e) {
      message.error(errorText(e));
    } finally {
      setBusy(false);
    }
  };

  const required = [{ required: true, message: t('ccrg.required') }];
  return (
    <Modal
      open={open}
      title={t('ccrg.install.title')}
      onCancel={onClose}
      onOk={submit}
      okText={t('ccrg.submit')}
      confirmLoading={busy}
      destroyOnHidden
    >
      <Form form={form} layout="vertical" disabled={busy}>
        <Form.Item name="projectName" label={t('ccrg.field.project')} rules={required}>
          <Select
            showSearch
            allowClear
            options={toOptions(projects)}
            onChange={(projectName?: string) => {
              form.setFieldsValue({ releaseName: undefined, buildNo: undefined });
              setReleases([]);
              setBuilds([]);
              if (projectName) void step(() => ccrgApi.releases({ projectName }), setReleases);
            }}
          />
        </Form.Item>
        <Form.Item name="releaseName" label={t('ccrg.field.release')} rules={required}>
          <Select
            showSearch
            allowClear
            options={toOptions(releases)}
            onChange={(releaseName?: string) => {
              form.setFieldsValue({ buildNo: undefined });
              setBuilds([]);
              const projectName = form.getFieldValue('projectName') as string;
              if (releaseName) void step(() => ccrgApi.builds({ projectName, releaseName }), setBuilds);
            }}
          />
        </Form.Item>
        <Form.Item name="buildNo" label={t('ccrg.field.buildNo')} rules={required}>
          <Select showSearch allowClear options={toOptions(builds)} />
        </Form.Item>
      </Form>
    </Modal>
  );
}
