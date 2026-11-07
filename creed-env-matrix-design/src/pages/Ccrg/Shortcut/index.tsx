import { useEffect, useState } from 'react';
import { PageContainer, ProCard } from '@ant-design/pro-components';
import { App, Button, Form, Select, Space, Table } from 'antd';
import type { TableColumnsType } from 'antd';
import { ExportOutlined, ReloadOutlined, SearchOutlined } from '@ant-design/icons';
import { ccrgApi } from '../../../api/ccrg';
import type { SelectorVo } from '../../../api/ccrg';
import { useI18n } from '../../../locales';
import { errorText, storage, toOptions } from '../shared';

/**
 * ShortcutGO: browse Artifactory builds by project → branch → release and jump straight to one.
 * `master` has no release level — its branch answer is already the build list.
 * Migrated from `ccrg-tool-ui/src/views/Shortcut`.
 */

const STORAGE_KEY = 'ccrg.shortcut';
const MASTER = 'master';

interface Picks {
  projectName?: string;
  branchName?: string;
  releaseName?: string;
  branchSelector: SelectorVo[];
  releaseSelector: SelectorVo[];
}

const EMPTY: Picks = { branchSelector: [], releaseSelector: [] };

export function ShortcutPage() {
  const { t } = useI18n();
  const { message } = App.useApp();
  const [projects, setProjects] = useState<SelectorVo[]>([]);
  const [picks, setPicks] = useState<Picks>(() => ({ ...EMPTY, ...storage.load<Picks>(STORAGE_KEY) }));
  const [rows, setRows] = useState<SelectorVo[]>([]);
  const [loading, setLoading] = useState(false);

  const remember = (next: Picks) => {
    setPicks(next);
    storage.save(STORAGE_KEY, next);
  };

  const guard = async (work: () => Promise<void>) => {
    setLoading(true);
    try {
      await work();
    } catch (e) {
      message.error(errorText(e));
    } finally {
      setLoading(false);
    }
  };

  /** The build list for the current picks, if they reach that far. */
  const loadBuilds = (p: Picks) =>
    guard(async () => {
      if (p.projectName && p.branchName && (p.branchName === MASTER || p.releaseName)) {
        setRows(await ccrgApi.shortcut(p.projectName, p.branchName, p.branchName === MASTER ? undefined : p.releaseName));
      } else {
        setRows([]);
      }
    });

  useEffect(() => {
    void guard(async () => setProjects(await ccrgApi.shortcut()));
    void loadBuilds(picks);
    // mount only: restores the remembered picks once
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, []);

  const onBranch = (base: Picks, branchName?: string) =>
    guard(async () => {
      const next: Picks = { ...base, branchName, releaseName: undefined, releaseSelector: [] };
      setRows([]);
      if (branchName && base.projectName) {
        const data = await ccrgApi.shortcut(base.projectName, branchName);
        if (branchName === MASTER) setRows(data);
        else next.releaseSelector = data;
      }
      remember(next);
    });

  const onProject = (projectName?: string) =>
    guard(async () => {
      setRows([]);
      if (!projectName) {
        remember({ ...EMPTY });
        return;
      }
      const branches = await ccrgApi.shortcut(projectName);
      // The first branch is picked straight away — almost every visit wants the default one.
      await onBranch({ ...EMPTY, projectName, branchSelector: branches }, branches[0]?.name);
    });

  const onRelease = (releaseName?: string) => {
    const next = { ...picks, releaseName };
    remember(next);
    void loadBuilds(next);
  };

  const go = async (row: SelectorVo) => {
    // Clipboard needs a secure context; on plain HTTP the jump still works, the name just is not copied.
    try {
      await navigator.clipboard.writeText(row.name ?? '');
      message.success(t('ccrg.shortcut.copied'));
    } catch {
      message.warning(t('ccrg.shortcut.copyFailed'));
    }
    if (row.uiPath) window.open(row.uiPath, '_blank', 'noopener');
  };

  const columns: TableColumnsType<SelectorVo> = [
    { title: 'ID', dataIndex: 'id', width: 80 },
    { title: t('ccrg.col.name'), dataIndex: 'name' },
    { title: t('ccrg.shortcut.col.path'), dataIndex: 'repositoryPath', ellipsis: true },
    { title: t('ccrg.shortcut.col.created'), dataIndex: 'created', width: 220 },
    {
      title: t('ccrg.col.operation'),
      key: 'op',
      width: 110,
      render: (_, row) => (
        <Button type="primary" size="small" icon={<ExportOutlined />} onClick={() => go(row)}>
          GO
        </Button>
      ),
    },
  ];

  return (
    <PageContainer title={t('nav.ccrg.shortcut')}>
      <Space direction="vertical" size="middle" style={{ width: '100%' }}>
        <ProCard bordered>
          <Form layout="vertical" style={{ maxWidth: 600 }} disabled={loading}>
            <Form.Item label={t('ccrg.field.project')} required>
              <Select showSearch allowClear value={picks.projectName} options={toOptions(projects)} onChange={onProject} />
            </Form.Item>
            <Form.Item label={t('ccrg.field.branch')} required>
              <Select
                showSearch
                allowClear
                value={picks.branchName}
                options={toOptions(picks.branchSelector)}
                onChange={(b?: string) => onBranch(picks, b)}
              />
            </Form.Item>
            {picks.branchName !== MASTER && (
              <Form.Item label={t('ccrg.field.release')}>
                <Select
                  showSearch
                  allowClear
                  value={picks.releaseName}
                  options={toOptions(picks.releaseSelector)}
                  onChange={onRelease}
                />
              </Form.Item>
            )}
          </Form>
          <Space>
            <Button type="primary" icon={<SearchOutlined />} onClick={() => loadBuilds(picks)}>
              {t('ccrg.search')}
            </Button>
            <Button
              icon={<ReloadOutlined />}
              onClick={() => {
                remember({ ...EMPTY });
                setRows([]);
              }}
            >
              {t('ccrg.reset')}
            </Button>
          </Space>
        </ProCard>

        <ProCard bordered>
          <Table<SelectorVo> rowKey={(r) => `${r.id}/${r.name}`} loading={loading} columns={columns} dataSource={rows} size="middle" />
        </ProCard>
      </Space>
    </PageContainer>
  );
}
