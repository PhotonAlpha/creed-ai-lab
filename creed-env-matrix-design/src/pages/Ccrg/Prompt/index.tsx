import { useCallback, useEffect, useRef, useState } from 'react';
import type { Key } from 'react';
import { PageContainer, ProCard } from '@ant-design/pro-components';
import { App, Button, Col, Form, Popconfirm, Radio, Row, Select, Space, Switch, Table, Tooltip, Typography } from 'antd';
import type { TableColumnsType } from 'antd';
import {
  DeleteOutlined,
  EyeOutlined,
  FileExcelOutlined,
  Html5Outlined,
  PlusOutlined,
  ReloadOutlined,
  SaveOutlined,
} from '@ant-design/icons';
import { useNavigate } from 'react-router-dom';
import { ccrgApi } from '../../../api/ccrg';
import type { ConfluenceContent, PromptCombinationRequest, SelectorVo } from '../../../api/ccrg';
import { useI18n } from '../../../locales';
import { CONFLUENCE_BASE, GuidelineBanner, errorText, storage, toOptions, useProgressOverlay } from '../shared';

/**
 * GenReport (`/ccrg/report`): compare two releases of one project / country / app and publish the
 * result as a Confluence page (xlsx or HTML), then list, open or delete the generated pages.
 * Migrated from `ccrg-tool-ui/src/views/Prompt`.
 */

const STORAGE_KEY = 'ccrg.prompt';

interface Combination {
  compareAll: boolean;
  projectName: string;
  baselineRelease: string;
  comparisonRelease: string;
  countryCode: string;
  app: string;
  baselineValue: string;
  comparisonValue: string;
  releaseSelector: SelectorVo[];
  countryCodeSelector: SelectorVo[];
  appSelector: SelectorVo[];
  /** value -> instance matrix; the picked value's matrix is what the report runs on. */
  baselineInstanceMatrix?: Record<string, unknown>;
  comparisonInstanceMatrix?: Record<string, unknown>;
}

const emptyCombination = (): Combination => ({
  compareAll: true,
  projectName: '',
  baselineRelease: '',
  comparisonRelease: '',
  countryCode: '',
  app: '',
  baselineValue: '',
  comparisonValue: '',
  releaseSelector: [],
  countryCodeSelector: [],
  appSelector: [],
});

function restore(): Combination[] {
  const saved = storage.load<{ batch?: Partial<Combination>[] }>(STORAGE_KEY);
  const list = saved?.batch;
  return Array.isArray(list) && list.length > 0 ? list.map((c) => ({ ...emptyCombination(), ...c })) : [emptyCombination()];
}

/**
 * Releases are named `<prefix>_<line>_...`: once one side is picked, the other side only offers the
 * same release line — comparing across lines is never what anyone means.
 */
function releasesFor(c: Combination, side: 'baseline' | 'comparison'): SelectorVo[] {
  const other = side === 'baseline' ? c.comparisonRelease : c.baselineRelease;
  if (!other) return c.releaseSelector;
  const prefix = other.split('_').slice(0, 2).join('_');
  return c.releaseSelector.filter((s) => s.name?.startsWith(prefix));
}

const hasValues = (c: Combination) => !!c.baselineInstanceMatrix || !!c.comparisonInstanceMatrix;

function missing(c: Combination): boolean {
  const base = !c.projectName || !c.baselineRelease || !c.comparisonRelease || !c.countryCode || !c.app;
  return base || (hasValues(c) && (!c.baselineValue || !c.comparisonValue));
}

/** `YYYY-MM-DD…` created `daysAgo` days before today, in local time. */
function isDaysAgo(created: string | undefined, daysAgo: number): boolean {
  if (!created || created.length < 10) return false;
  const d = new Date();
  d.setDate(d.getDate() - daysAgo);
  const local = `${d.getFullYear()}-${String(d.getMonth() + 1).padStart(2, '0')}-${String(d.getDate()).padStart(2, '0')}`;
  return created.slice(0, 10) === local;
}

export function PromptPage() {
  const { t } = useI18n();
  const { message } = App.useApp();
  const navigate = useNavigate();
  const [batch, setBatch] = useState<Combination[]>(restore);
  const [projects, setProjects] = useState<SelectorVo[]>([]);
  const [busy, setBusy] = useState(false);
  const [showErrors, setShowErrors] = useState(false);
  const [overlay, runWithProgress] = useProgressOverlay();
  const bottomRef = useRef<HTMLDivElement>(null);

  // generated pages
  const [pages, setPages] = useState<ConfluenceContent[]>([]);
  const [total, setTotal] = useState(0);
  const [page, setPage] = useState({ current: 1, pageSize: 10 });
  const [pagesLoading, setPagesLoading] = useState(false);
  const [selectedKeys, setSelectedKeys] = useState<Key[]>([]);

  const loadPages = useCallback(async () => {
    setPagesLoading(true);
    try {
      const { list, total: count } = await ccrgApi.listReports(page.current, page.pageSize);
      setPages(list ?? []);
      setTotal(count ?? 0);
    } catch (e) {
      message.error(errorText(e));
    } finally {
      setPagesLoading(false);
    }
  }, [page, message]);

  useEffect(() => {
    void loadPages();
  }, [loadPages]);

  useEffect(() => {
    ccrgApi
      .projects()
      .then(setProjects)
      .catch((e) => message.error(errorText(e)));
  }, [message]);

  const mutate = useCallback((b: number, fn: (c: Combination) => void) => {
    setBatch((prev) => {
      const next = structuredClone(prev);
      fn(next[b]);
      return next;
    });
  }, []);

  const fetching = async <T,>(work: () => Promise<T>): Promise<T | undefined> => {
    setBusy(true);
    try {
      return await work();
    } catch (e) {
      message.error(errorText(e));
      return undefined;
    } finally {
      setBusy(false);
    }
  };

  const clearValues = (c: Combination) => {
    c.baselineInstanceMatrix = undefined;
    c.comparisonInstanceMatrix = undefined;
    c.baselineValue = '';
    c.comparisonValue = '';
  };

  // --- cascade: project -> releases -> country -> app -> instance values ---

  const onProject = async (b: number, projectName: string) => {
    mutate(b, (c) => {
      Object.assign(c, { ...emptyCombination(), compareAll: c.compareAll, projectName });
    });
    if (!projectName) return;
    const data = await fetching(() => ccrgApi.pathWalk({ dictType: 'COMMON', projectName }));
    if (data) mutate(b, (c) => (c.releaseSelector = data));
  };

  const onApp = async (b: number, c: Combination, app: string) => {
    mutate(b, (d) => {
      d.app = app;
      clearValues(d);
    });
    // Instance values need both releases; the second release pick comes back here.
    if (!app || !c.baselineRelease || !c.comparisonRelease) return;
    const data = await fetching(() =>
      ccrgApi.listInstances({
        projectName: c.projectName,
        baselineRelease: c.baselineRelease,
        comparisonRelease: c.comparisonRelease,
        countryCode: c.countryCode,
        app,
      }),
    );
    if (!data) return;
    mutate(b, (d) => {
      d.baselineInstanceMatrix = data.baselineInstanceMatrix ?? {};
      d.comparisonInstanceMatrix = data.comparisonInstanceMatrix ?? {};
    });
  };

  const onCountry = async (b: number, c: Combination, countryCode: string) => {
    mutate(b, (d) => {
      d.countryCode = countryCode;
      d.app = '';
      d.appSelector = [];
      clearValues(d);
    });
    if (!countryCode) return;
    const data = await fetching(() =>
      ccrgApi.pathWalk({ dictType: 'APP', projectName: c.projectName, releaseName: c.baselineRelease, countryCode }),
    );
    if (!data) return;
    mutate(b, (d) => (d.appSelector = data));
    // A single application is the usual case: pick it and go straight on to the instance values.
    if (data.length === 1 && data[0].name) await onApp(b, { ...c, countryCode }, data[0].name);
  };

  const onRelease = async (b: number, c: Combination, side: 'baseline' | 'comparison', releaseName: string) => {
    const next = { ...c, [side === 'baseline' ? 'baselineRelease' : 'comparisonRelease']: releaseName };
    mutate(b, (d) => {
      Object.assign(d, next);
      if (!next.baselineRelease && !next.comparisonRelease) {
        d.countryCode = '';
        d.countryCodeSelector = [];
      }
    });
    if (!releaseName) return;
    const data = await fetching(() => ccrgApi.pathWalk({ dictType: 'COMMON', projectName: c.projectName, releaseName }));
    if (!data) return;
    mutate(b, (d) => (d.countryCodeSelector = data));
    const preset = data.find((s) => s.selected)?.name;
    if (preset) await onCountry(b, next, preset);
    else if (next.app) await onApp(b, next, next.app);
  };

  const reloadReleases = async (b: number) => {
    const projectName = batch[b].projectName;
    if (!projectName) return;
    const data = await fetching(() => ccrgApi.pathWalk({ dictType: 'COMMON', projectName }));
    if (data) {
      mutate(b, (c) => (c.releaseSelector = data));
      message.success(t('ccrg.compare.reloaded'));
    }
  };

  const addCopy = (b: number) => {
    setBatch((prev) => [...prev, structuredClone(prev[b])]);
    requestAnimationFrame(() => bottomRef.current?.scrollIntoView({ behavior: 'smooth', block: 'end' }));
  };

  const save = () => {
    if (storage.save(STORAGE_KEY, { batch })) message.success(t('ccrg.saved'));
    else message.error(t('ccrg.saveFailed'));
  };

  const generate = async (type: 'xlsx' | 'html') => {
    if (batch.some(missing)) {
      setShowErrors(true);
      message.warning(t('ccrg.incomplete'));
      return;
    }
    storage.save(STORAGE_KEY, { batch });
    const request: PromptCombinationRequest[] = batch.map((c) => ({
      compareAll: c.compareAll,
      baseRelease: c.baselineRelease,
      projectName: c.projectName,
      baselineRelease: c.baselineRelease,
      comparisonRelease: c.comparisonRelease,
      countryCode: c.countryCode,
      app: c.app,
      baselineInstanceMatrix: c.baselineInstanceMatrix?.[c.baselineValue],
      comparisonInstanceMatrix: c.comparisonInstanceMatrix?.[c.comparisonValue],
      baselineValue: c.baselineValue,
      comparisonValue: c.comparisonValue,
    }));
    try {
      await runWithProgress((connectionId) => ccrgApi.generative(connectionId!, request, type), true);
      message.success(t('ccrg.prompt.generated'));
    } catch (e) {
      message.error(errorText(e));
    } finally {
      void loadPages();
    }
  };

  const deleteSelected = async () => {
    setPagesLoading(true);
    try {
      await ccrgApi.deleteReports(selectedKeys.map(String));
      message.success(t('ccrg.prompt.deleted'));
      setSelectedKeys([]);
      await loadPages();
    } catch (e) {
      message.error(errorText(e));
      setPagesLoading(false);
    }
  };

  const view = (row: ConfluenceContent) => {
    const tiny = row._links?.tinyui;
    if (!tiny || !CONFLUENCE_BASE) {
      message.error(t('ccrg.prompt.noLink', { title: row.title }));
      return;
    }
    window.open(`${CONFLUENCE_BASE.replace(/\/$/, '')}${tiny}`, '_blank', 'noopener');
  };

  const columns: TableColumnsType<ConfluenceContent> = [
    { title: '#', key: 'index', width: 50, render: (_, __, i) => (page.current - 1) * page.pageSize + i + 1 },
    { title: 'ID', dataIndex: 'id', width: 120 },
    { title: t('ccrg.prompt.col.date'), dataIndex: 'createdDate', width: 200, render: (v?: string) => v || 'NA' },
    { title: t('ccrg.prompt.col.title'), dataIndex: 'title' },
    { title: t('ccrg.prompt.col.link'), key: 'link', ellipsis: true, render: (_, row) => row._links?.webui },
    {
      title: t('ccrg.prompt.col.view'),
      key: 'view',
      width: 70,
      render: (_, row) => <Button type="link" icon={<EyeOutlined />} onClick={() => view(row)} />,
    },
  ];

  const err = (bad: boolean) => (showErrors && bad ? 'error' : undefined);

  return (
    <PageContainer title={t('nav.ccrg.report')} subTitle={t('ccrg.prompt.subtitle')}>
      {overlay}
      <Space direction="vertical" size="middle" style={{ width: '100%' }}>
        <GuidelineBanner />

        <ProCard
          bordered
          title={t('ccrg.prompt.pages')}
          extra={
            <Button icon={<PlusOutlined />} onClick={() => navigate('/ccrg/install')}>
              {t('ccrg.compare.installConfigs')}
            </Button>
          }
        >
          <Table<ConfluenceContent>
            rowKey="id"
            size="middle"
            loading={pagesLoading}
            columns={columns}
            dataSource={pages}
            // Today's pages green, yesterday's amber: what a run just produced stands out.
            rowClassName={(row) => (isDaysAgo(row.createdDate, 0) ? 'ccrg-row--today' : isDaysAgo(row.createdDate, 1) ? 'ccrg-row--yesterday' : '')}
            rowSelection={{ selectedRowKeys: selectedKeys, onChange: setSelectedKeys }}
            pagination={{
              ...page,
              total,
              showSizeChanger: true,
              pageSizeOptions: [5, 10, 20],
              showTotal: (n) => t('ccrg.total', { total: n }),
              onChange: (current, pageSize) => setPage({ current, pageSize }),
            }}
          />
          <Space wrap>
            {/* Disabled in the old page as well: the merge endpoint exists but is not finished. */}
            <Tooltip title={t('ccrg.prompt.mergeDisabled')}>
              <Button type="primary" disabled>
                {t('ccrg.prompt.merge')}
              </Button>
            </Tooltip>
            <Button onClick={() => setSelectedKeys([])}>{t('ccrg.clearSelection')}</Button>
            <Button icon={<ReloadOutlined />} onClick={() => loadPages()}>
              {t('ccrg.refresh')}
            </Button>
            <Popconfirm title={t('ccrg.prompt.deleteConfirm')} disabled={selectedKeys.length === 0} onConfirm={deleteSelected}>
              <Button danger icon={<DeleteOutlined />} disabled={selectedKeys.length === 0}>
                {t('ccrg.delete')}
              </Button>
            </Popconfirm>
          </Space>
        </ProCard>

        {batch.map((c, b) => (
          <ProCard
            key={b}
            bordered
            title={t('ccrg.compare.combination', { index: b + 1 })}
            extra={
              <Space>
                <Tooltip title={t('ccrg.compare.addCopy')}>
                  <Button icon={<PlusOutlined />} onClick={() => addCopy(b)} />
                </Tooltip>
                <Tooltip title={t('ccrg.compare.remove')}>
                  <Button
                    danger
                    icon={<DeleteOutlined />}
                    disabled={batch.length <= 1}
                    onClick={() => setBatch((prev) => prev.filter((_, i) => i !== b))}
                  />
                </Tooltip>
              </Space>
            }
          >
            <Row align="middle" style={{ marginBottom: 12 }}>
              <Col flex="auto">
                <Space>
                  {t('ccrg.compare.compareAll')}
                  <Switch checked={c.compareAll} checkedChildren="ON" unCheckedChildren="OFF" onChange={(v) => mutate(b, (d) => (d.compareAll = v))} />
                </Space>
              </Col>
              <Col>
                <Button size="small" danger icon={<ReloadOutlined />} disabled={!c.projectName} onClick={() => reloadReleases(b)}>
                  {t('ccrg.compare.reloadReleases')}
                </Button>
              </Col>
            </Row>

            <Form layout="horizontal" labelCol={{ flex: '120px' }} labelAlign="left" disabled={busy}>
              <Form.Item label={t('ccrg.field.project')} required validateStatus={err(!c.projectName)}>
                <Select
                  showSearch
                  allowClear
                  style={{ maxWidth: 360 }}
                  value={c.projectName || undefined}
                  options={toOptions(projects)}
                  onChange={(v?: string) => onProject(b, v ?? '')}
                />
              </Form.Item>
              <Form.Item label={t('ccrg.field.release')} required>
                <Space wrap align="center">
                  <Select
                    showSearch
                    allowClear
                    style={{ width: 300 }}
                    placeholder={t('ccrg.prompt.baseline')}
                    status={err(!c.baselineRelease)}
                    value={c.baselineRelease || undefined}
                    options={toOptions(releasesFor(c, 'baseline'))}
                    onChange={(v?: string) => onRelease(b, c, 'baseline', v ?? '')}
                  />
                  <Typography.Text type="secondary">VS</Typography.Text>
                  <Select
                    showSearch
                    allowClear
                    style={{ width: 300 }}
                    placeholder={t('ccrg.prompt.comparison')}
                    status={err(!c.comparisonRelease)}
                    value={c.comparisonRelease || undefined}
                    options={toOptions(releasesFor(c, 'comparison'))}
                    onChange={(v?: string) => onRelease(b, c, 'comparison', v ?? '')}
                  />
                </Space>
              </Form.Item>
              <Form.Item label={t('ccrg.field.country')} required validateStatus={err(!c.countryCode)}>
                <Select
                  showSearch
                  allowClear
                  style={{ maxWidth: 360 }}
                  value={c.countryCode || undefined}
                  options={toOptions(c.countryCodeSelector)}
                  onChange={(v?: string) => onCountry(b, c, v ?? '')}
                />
              </Form.Item>
              <Form.Item label={t('ccrg.field.application')} required validateStatus={err(!c.app)}>
                <Select
                  showSearch
                  allowClear
                  style={{ maxWidth: 360 }}
                  value={c.app || undefined}
                  options={toOptions(c.appSelector)}
                  onChange={(v?: string) => onApp(b, c, v ?? '')}
                />
              </Form.Item>
              {hasValues(c) && (
                <Form.Item label={t('ccrg.prompt.values')} required>
                  <Row gutter={24} wrap={false} align="top">
                    <Col>
                      <Radio.Group
                        value={c.baselineValue || undefined}
                        onChange={(e) => mutate(b, (d) => (d.baselineValue = e.target.value as string))}
                      >
                        <Space direction="vertical">
                          {Object.keys(c.baselineInstanceMatrix ?? {}).map((v) => (
                            <Radio.Button key={v} value={v}>
                              {v}
                            </Radio.Button>
                          ))}
                        </Space>
                      </Radio.Group>
                    </Col>
                    <Col>
                      <Typography.Text type="secondary">VS</Typography.Text>
                    </Col>
                    <Col>
                      <Radio.Group
                        value={c.comparisonValue || undefined}
                        onChange={(e) => mutate(b, (d) => (d.comparisonValue = e.target.value as string))}
                      >
                        <Space direction="vertical">
                          {Object.keys(c.comparisonInstanceMatrix ?? {}).map((v) => (
                            <Radio.Button key={v} value={v}>
                              {v}
                            </Radio.Button>
                          ))}
                        </Space>
                      </Radio.Group>
                    </Col>
                  </Row>
                  {showErrors && (!c.baselineValue || !c.comparisonValue) && (
                    <Typography.Text type="danger">{t('ccrg.prompt.pickValues')}</Typography.Text>
                  )}
                </Form.Item>
              )}
            </Form>
          </ProCard>
        ))}

        <div ref={bottomRef} style={{ textAlign: 'center' }}>
          <Space wrap>
            <Button icon={<SaveOutlined />} onClick={save}>
              {t('ccrg.saveConfig')}
            </Button>
            <Button icon={<FileExcelOutlined />} onClick={() => generate('xlsx')}>
              {t('ccrg.generateXlsx')}
            </Button>
            <Button type="primary" icon={<Html5Outlined />} onClick={() => generate('html')}>
              {t('ccrg.generateHtml')}
            </Button>
          </Space>
        </div>
      </Space>
    </PageContainer>
  );
}
