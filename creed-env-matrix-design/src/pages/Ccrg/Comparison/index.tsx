import { useCallback, useEffect, useRef, useState } from 'react';
import { PageContainer, ProCard } from '@ant-design/pro-components';
import { App, Button, Col, Form, Input, Modal, Row, Select, Space, Switch, Tooltip, Typography } from 'antd';
import {
  CopyOutlined,
  DeleteOutlined,
  FileExcelOutlined,
  Html5Outlined,
  ImportOutlined,
  PlusOutlined,
  ReloadOutlined,
  SaveOutlined,
  UndoOutlined,
} from '@ant-design/icons';
import { useNavigate } from 'react-router-dom';
import { ccrgApi, saveFile } from '../../../api/ccrg';
import type { SelectorVo } from '../../../api/ccrg';
import { useI18n } from '../../../locales';
import type { MessageKey } from '../../../locales';
import { GuidelineBanner, errorText, storage, toOptions, useProgressOverlay } from '../shared';
import {
  REQUIRED,
  clearBelow,
  emptyCombination,
  isComplete,
  isMissing,
  normalize,
  sameRelease,
  toRequest,
  walk,
} from './model';
import type { Combination, ComparisonMode, Source } from './model';

/**
 * Batch Export (`/ccrg/batch`) and Crossover (`/ccrg/crossover`): migrated from
 * `ccrg-tool-ui/src/views/Batch` and `views/Crossover`, which were the same 1200-line file with five
 * lines changed. `model.ts` says what the mode changes.
 *
 * Each mode saves to its own key — the old pages shared one, so saving on one page overwrote the other.
 */

const FIELD_LABEL: Record<string, MessageKey> = {
  projectName: 'ccrg.field.project',
  releaseName: 'ccrg.field.releaseFolder',
  countryCode: 'ccrg.field.country',
  app: 'ccrg.field.application',
  environment: 'ccrg.field.environment',
  instance: 'ccrg.field.instance',
  zone: 'ccrg.field.zone',
};

export function ComparisonPage({ mode }: { mode: ComparisonMode }) {
  const { t } = useI18n();
  const { message } = App.useApp();
  const navigate = useNavigate();
  const storageKey = `ccrg.${mode}`;

  const [batch, setBatch] = useState<Combination[]>(() => {
    try {
      return normalize(storage.load(storageKey));
    } catch {
      return [emptyCombination()];
    }
  });
  const [projects, setProjects] = useState<SelectorVo[]>([]);
  const [loading, setLoading] = useState(false);
  const [showErrors, setShowErrors] = useState(false);
  const [templateOpen, setTemplateOpen] = useState(false);
  const [overlay, runWithProgress] = useProgressOverlay();
  const bottomRef = useRef<HTMLDivElement>(null);

  useEffect(() => {
    ccrgApi
      .projects()
      .then(setProjects)
      .catch((e) => message.error(errorText(e)));
  }, [message]);

  /** Every edit goes through a structured clone, so async answers never mutate a rendered state. */
  const mutate = useCallback((fn: (draft: Combination[]) => void) => {
    setBatch((prev) => {
      const next = structuredClone(prev);
      fn(next);
      return next;
    });
  }, []);

  const fetching = async <T,>(work: () => Promise<T>): Promise<T | undefined> => {
    setLoading(true);
    try {
      return await work();
    } catch (e) {
      message.error(errorText(e));
      return undefined;
    } finally {
      setLoading(false);
    }
  };

  // --- the cascade. Each handler takes the source as it was when picked, since state is async. ---

  const onProject = async (b: number, idx: number, projectName: string) => {
    mutate((d) => {
      const c = d[b];
      const targets = mode === 'batch' ? c.routes : [c.routes[idx]];
      targets.forEach((r) => {
        r.projectName = projectName;
        clearBelow(r, 'project');
        if (!projectName) r.releaseSelector = [];
      });
      if (mode === 'batch') {
        c.disableProject = !!projectName;
        c.disableCountry = false;
        c.disableApp = false;
      }
    });
    if (!projectName) return;
    const data = await fetching(() => ccrgApi.pathWalk({ dictType: 'COMMON', projectName }));
    if (!data) return;
    mutate((d) => {
      const targets = mode === 'batch' ? d[b].routes : [d[b].routes[idx]];
      targets.forEach((r) => (r.releaseSelector = data));
    });
  };

  const onCountry = async (b: number, idx: number, source: Source, countryCode: string) => {
    mutate((d) => {
      const r = d[b].routes[idx];
      r.countryCode = countryCode;
      clearBelow(r, 'country');
      d[b].disableApp = false;
    });
    if (!countryCode) return;
    const data = await fetching(() => ccrgApi.pathWalk(walk({ ...source, countryCode }, 'APP', 2)));
    if (!data) return;
    mutate((d) => {
      const c = d[b];
      if (sameRelease(c)) {
        c.routes.forEach((r) => {
          r.appSelector = data;
          r.countryCode = countryCode;
        });
        c.disableCountry = true;
      } else {
        c.routes[idx].appSelector = data;
        c.disableCountry = false;
      }
      if (mode === 'batch') c.disableProject = true;
    });
  };

  const onRelease = async (b: number, idx: number, source: Source, releaseName: string) => {
    mutate((d) => {
      const r = d[b].routes[idx];
      r.releaseName = releaseName;
      if (!releaseName) {
        clearBelow(r, 'release');
        r.countrySelector = [];
      }
    });
    if (!releaseName) return;
    const data = await fetching(() => ccrgApi.pathWalk(walk({ ...source, releaseName }, 'COMMON', 1)));
    if (!data) return;
    // batch: the backend's default country is picked, and a still-valid country survives the change.
    // crossover: the old page always started the source over below the release.
    const preset = mode === 'batch' ? data.find((s) => s.selected)?.name : undefined;
    const keeps = mode === 'batch' && !preset && data.some((s) => s.name === source.countryCode);
    mutate((d) => {
      const c = d[b];
      const r = c.routes[idx];
      r.countrySelector = data;
      if (!keeps) clearBelow(r, 'release');
      if (mode === 'batch') {
        c.disableProject = true;
        c.disableCountry = false;
        c.disableApp = false;
      }
    });
    if (preset) await onCountry(b, idx, { ...source, releaseName }, preset);
  };

  const onApp = async (b: number, idx: number, source: Source, app: string) => {
    mutate((d) => {
      d[b].routes[idx].app = app;
    });
    if (!app) return;
    const data = await fetching(() => ccrgApi.pathWalk(walk({ ...source, app }, 'ENVIRONMENT', 3)));
    if (!data) return;
    const keepsEnv = data.some((s) => s.name === source.environment);
    mutate((d) => {
      const c = d[b];
      if (sameRelease(c)) {
        c.routes.forEach((r) => {
          r.environmentSelector = data;
          r.app = app;
        });
        c.disableCountry = true;
        c.disableApp = true;
      } else {
        c.routes[idx].environmentSelector = data;
        c.disableCountry = false;
        c.disableApp = false;
      }
      if (mode === 'batch') c.disableProject = true;
      if (!keepsEnv) c.routes.forEach((r) => clearBelow(r, 'app'));
    });
  };

  const onEnvironment = async (b: number, idx: number, source: Source, environment: string) => {
    mutate((d) => {
      d[b].routes[idx].environment = environment;
    });
    if (!environment) return;
    const data = await fetching(() => ccrgApi.pathWalk(walk({ ...source, environment }, 'INSTANCE', 4)));
    if (!data) return;
    const keeps = data.some((s) => s.name === source.instance);
    mutate((d) => {
      const r = d[b].routes[idx];
      r.instanceSelector = data;
      if (!keeps) clearBelow(r, 'environment');
    });
  };

  const onInstance = async (b: number, idx: number, source: Source, instance: string) => {
    mutate((d) => {
      d[b].routes[idx].instance = instance;
    });
    if (!instance) return;
    const data = await fetching(() => ccrgApi.pathWalk(walk({ ...source, instance }, 'ZONE', 5)));
    if (!data) return;
    mutate((d) => {
      d[b].routes[idx].zoneSelector = data;
    });
  };

  /** "Reload Release Folder" — a stub in the old page; re-reads the release list of each source's project. */
  const reloadReleases = async (b: number) => {
    const routes = batch[b].routes;
    const lists = await fetching(() =>
      Promise.all(routes.map((r) => (r.projectName ? ccrgApi.pathWalk({ dictType: 'COMMON', projectName: r.projectName }) : []))),
    );
    if (!lists) return;
    mutate((d) => d[b].routes.forEach((r, i) => (r.releaseSelector = lists[i])));
    message.success(t('ccrg.compare.reloaded'));
  };

  // --- toolbar ---

  const copyApplication = () => {
    const app = batch[0]?.routes[0]?.app;
    if (!app) return;
    mutate((d) => d.forEach((c) => c.routes.forEach((r) => (r.app = app))));
  };

  const addCopy = (b: number) => {
    mutate((d) => {
      d.push(structuredClone(d[b]));
    });
    requestAnimationFrame(() => bottomRef.current?.scrollIntoView({ behavior: 'smooth', block: 'end' }));
  };

  const save = () => {
    if (storage.save(storageKey, batch)) message.success(t('ccrg.saved'));
    else message.error(t('ccrg.saveFailed'));
  };

  const generate = async (kind: 'xlsx' | 'html') => {
    if (!isComplete(batch)) {
      setShowErrors(true);
      message.warning(t('ccrg.incomplete'));
      return;
    }
    // The HTML report streams progress over SSE; the xlsx endpoint takes no connection id.
    try {
      const file = await runWithProgress(
        (connectionId) => (kind === 'xlsx' ? ccrgApi.batchXlsx(toRequest(batch)) : ccrgApi.batchHtml(toRequest(batch, connectionId))),
        kind === 'html',
      );
      saveFile(file);
      message.success(t('ccrg.downloaded'));
    } catch (e) {
      message.error(t('ccrg.downloadFailed', { message: errorText(e) }));
    }
  };

  const errorOf = (s: Source, field: keyof Source) => (showErrors && isMissing(s, field) ? 'error' : undefined);

  return (
    <PageContainer title={t(mode === 'batch' ? 'nav.ccrg.batch' : 'nav.ccrg.crossover')} subTitle={t(`ccrg.compare.${mode}Hint`)}>
      {overlay}
      <Space direction="vertical" size="middle" style={{ width: '100%' }}>
        <GuidelineBanner />
        <ProCard bordered>
          <Space wrap>
            <Button icon={<PlusOutlined />} onClick={() => navigate('/ccrg/install')}>
              {t('ccrg.compare.installConfigs')}
            </Button>
            <Button icon={<CopyOutlined />} onClick={copyApplication} disabled={!batch[0]?.routes[0]?.app}>
              {t('ccrg.compare.copyApplication')}
            </Button>
            <Button icon={<ImportOutlined />} onClick={() => setTemplateOpen(true)}>
              {t('ccrg.compare.template')}
            </Button>
            <Tooltip title={t('ccrg.compare.resetHint')}>
              <Button icon={<UndoOutlined />} onClick={() => setBatch((prev) => prev.slice(0, 1))}>
                {t('ccrg.reset')}
              </Button>
            </Tooltip>
          </Space>
        </ProCard>

        {batch.map((combo, b) => (
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
                  <Button danger icon={<DeleteOutlined />} disabled={batch.length <= 1} onClick={() => mutate((d) => d.splice(b, 1))} />
                </Tooltip>
              </Space>
            }
          >
            <Row gutter={16} align="middle" style={{ marginBottom: 12 }}>
              <Col flex="none">
                <Space>
                  {t('ccrg.compare.compareAll')}
                  <Switch
                    checked={combo.compareAll}
                    checkedChildren="ON"
                    unCheckedChildren="OFF"
                    onChange={(v) => mutate((d) => (d[b].compareAll = v))}
                  />
                </Space>
              </Col>
              {!combo.compareAll && (
                <Col flex="300px">
                  <Form.Item
                    label={t('ccrg.compare.baseRelease')}
                    required
                    validateStatus={showErrors && !combo.baseRelease ? 'error' : undefined}
                    style={{ marginBottom: 0 }}
                  >
                    <Select
                      showSearch
                      allowClear
                      value={combo.baseRelease || undefined}
                      options={toOptions(combo.routes[0].releaseSelector)}
                      onChange={(v?: string) => mutate((d) => (d[b].baseRelease = v ?? ''))}
                    />
                  </Form.Item>
                </Col>
              )}
              <Col flex="auto" style={{ textAlign: 'right' }}>
                <Button
                  size="small"
                  danger
                  icon={<ReloadOutlined />}
                  disabled={!combo.routes[0].projectName}
                  onClick={() => reloadReleases(b)}
                >
                  {t('ccrg.compare.reloadReleases')}
                </Button>
              </Col>
            </Row>

            <Row gutter={[16, 16]}>
              {combo.routes.map((source, idx) => {
                const locked = (flag: boolean) => idx !== 0 && flag;
                const select = (
                  field: keyof Source,
                  selector: SelectorVo[],
                  onChange: (v: string) => void,
                  disabled = false,
                ) => (
                  <Form.Item label={t(FIELD_LABEL[field])} required validateStatus={errorOf(source, field)}>
                    <Select
                      showSearch
                      allowClear
                      disabled={disabled || loading}
                      value={(source[field] as string) || undefined}
                      options={toOptions(selector)}
                      onChange={(v?: string) => onChange(v ?? '')}
                    />
                  </Form.Item>
                );
                return (
                  <Col key={idx} xs={24} lg={12}>
                    <ProCard bordered type="inner" title={t('ccrg.compare.source', { index: idx + 1 })}>
                      <Form layout="horizontal" labelCol={{ flex: '120px' }} labelAlign="left">
                        {select('projectName', projects, (v) => onProject(b, idx, v), locked(combo.disableProject))}
                        {select('releaseName', source.releaseSelector, (v) => onRelease(b, idx, source, v))}
                        {select('countryCode', source.countrySelector, (v) => onCountry(b, idx, source, v), locked(combo.disableCountry))}
                        {select('app', source.appSelector, (v) => onApp(b, idx, source, v), locked(combo.disableApp))}
                        {select('environment', source.environmentSelector, (v) => onEnvironment(b, idx, source, v))}
                        {select('instance', source.instanceSelector, (v) => onInstance(b, idx, source, v))}
                        <Form.Item label={t('ccrg.field.zone')} required validateStatus={errorOf(source, 'zone')}>
                          <Select
                            mode="multiple"
                            allowClear
                            disabled={loading}
                            value={source.zone}
                            options={toOptions(source.zoneSelector)}
                            onChange={(v: string[]) => mutate((d) => (d[b].routes[idx].zone = v))}
                          />
                        </Form.Item>
                      </Form>
                    </ProCard>
                  </Col>
                );
              })}
            </Row>
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
          {showErrors && !isComplete(batch) && (
            <Typography.Paragraph type="danger" style={{ marginTop: 8 }}>
              {t('ccrg.compare.missing', { fields: REQUIRED.map((f) => t(FIELD_LABEL[f])).join(' / ') })}
            </Typography.Paragraph>
          )}
        </div>
      </Space>

      <TemplateModal
        open={templateOpen}
        current={batch}
        onClose={() => setTemplateOpen(false)}
        onApply={(next) => {
          setBatch(next);
          setTemplateOpen(false);
          message.success(t('ccrg.compare.templateApplied', { count: next.length }));
        }}
      />
    </PageContainer>
  );
}

/**
 * "Create From Template" — an empty dialog in the old page. Here it is the form as JSON: copy it out to
 * share a set of combinations, paste one in to replace the form (selectors included, so the dropdowns
 * work without re-walking).
 */
function TemplateModal({
  open,
  current,
  onClose,
  onApply,
}: {
  open: boolean;
  current: Combination[];
  onClose: () => void;
  onApply: (batch: Combination[]) => void;
}) {
  const { t } = useI18n();
  const [text, setText] = useState('');
  const [error, setError] = useState<string>();

  useEffect(() => {
    if (open) {
      setText(JSON.stringify({ batch: current }, null, 2));
      setError(undefined);
    }
    // only when opening: re-seeding while typing would discard the edit
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [open]);

  const apply = () => {
    try {
      onApply(normalize(JSON.parse(text)));
    } catch (e) {
      setError(errorText(e));
    }
  };

  return (
    <Modal open={open} width={720} title={t('ccrg.compare.template')} onCancel={onClose} onOk={apply} okText={t('ccrg.compare.applyTemplate')}>
      <p>{t('ccrg.compare.templateHint')}</p>
      <Input.TextArea
        rows={18}
        spellCheck={false}
        value={text}
        status={error ? 'error' : undefined}
        onChange={(e) => setText(e.target.value)}
        style={{ fontFamily: 'monospace' }}
      />
      {error && <Typography.Text type="danger">{error}</Typography.Text>}
    </Modal>
  );
}
