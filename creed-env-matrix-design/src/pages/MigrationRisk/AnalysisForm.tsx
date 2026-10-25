import { useCallback, useEffect, useMemo, useRef, useState } from 'react';
import type { FormInstance } from 'antd';
import { AutoComplete, Button, Col, Form, Input, Row, Segmented, Space, Tooltip, Typography } from 'antd';
import { ExperimentOutlined, SearchOutlined } from '@ant-design/icons';
import { migrationRiskApi } from '../../api/migrationRisk';
import type { BaselineSuggestion, MigrationRiskSettings, RefsView, ReleaseView } from '../../api/migrationRisk';
import { useI18n } from '../../locales';
import { fieldErrors, FORM_FIELDS } from './constants';
import type { FormValues } from './constants';

const { Text } = Typography;

export const EMPTY_FORM: FormValues = {
  mode: 'GIT',
  release: '',
  repo1Path: '',
  baseline: '',
  repo1Head: 'HEAD',
  repo2Path: '',
  repo2Head: 'HEAD',
  baselinePath: '',
};

type RepoKey = 'repo1' | 'repo2';

interface Props {
  form: FormInstance<FormValues>;
  settings: MigrationRiskSettings | null;
  releases: ReleaseView[];
  loading: boolean;
  onAnalyze: (values: FormValues) => void;
  onDemo: (mode: FormValues['mode']) => void;
}

/**
 * Git mode: two repositories, each with its own branch picker (branches, remote branches, tags), and the
 * baseline picked from repo1 — pre-filled with the merge-base of the two selected branches when they share
 * history. Folder mode: three plain directories. Either way, a release name saves the result for review.
 */
export function AnalysisForm({ form, settings, releases, loading, onAnalyze, onDemo }: Props) {
  const { t } = useI18n();
  const mode = Form.useWatch('mode', form) ?? 'GIT';
  const [refs, setRefs] = useState<Record<RepoKey, RefsView | null>>({ repo1: null, repo2: null });
  const [suggestion, setSuggestion] = useState<BaselineSuggestion | null>(null);
  const requestIds = useRef<Record<string, number>>({});
  const timers = useRef<Record<string, ReturnType<typeof setTimeout>>>({});

  /** Last-call-wins per key: a slow answer for a path the user already changed is dropped. */
  const latest = (key: string) => {
    const id = (requestIds.current[key] ?? 0) + 1;
    requestIds.current[key] = id;
    return () => requestIds.current[key] === id;
  };

  const loadRefs = useCallback(async (repo: RepoKey) => {
    const isLatest = latest(repo);
    const path = form.getFieldValue(`${repo}Path`)?.trim();
    const head = form.getFieldValue(`${repo}Head`)?.trim();
    if (!path || form.getFieldValue('mode') !== 'GIT') {
      setRefs((r) => ({ ...r, [repo]: null }));
      return;
    }
    try {
      const result = await migrationRiskApi.refs(path, head);
      if (!isLatest()) return;
      setRefs((r) => ({ ...r, [repo]: result }));
      form.setFields([{ name: `${repo}Path`, errors: [] }, { name: `${repo}Head`, errors: [] }]);
    } catch (e) {
      if (!isLatest()) return;
      setRefs((r) => ({ ...r, [repo]: null }));
      form.setFields(fieldErrors(e, { path: `${repo}Path`, head: `${repo}Head` }));
    }
  }, [form]);

  const loadSuggestion = useCallback(async () => {
    const isLatest = latest('suggestion');
    const v = form.getFieldsValue();
    if (v.mode !== 'GIT' || !v.repo1Path?.trim() || !v.repo2Path?.trim()) {
      setSuggestion(null);
      return;
    }
    try {
      const result = await migrationRiskApi.baselineSuggestion(v);
      if (!isLatest()) return;
      setSuggestion(result);
      if (result.baseline && !form.getFieldValue('baseline')) {
        form.setFieldValue('baseline', result.baseline.shortId);
      }
    } catch {
      if (isLatest()) setSuggestion(null); // /refs already marks the bad input
    }
  }, [form]);

  const schedule = (key: string, fn: () => void) => {
    clearTimeout(timers.current[key]);
    timers.current[key] = setTimeout(fn, 500);
  };

  useEffect(() => () => Object.values(timers.current).forEach(clearTimeout), []);

  /** Initial values set from outside (demo, a reopened run) do not fire onValuesChange. */
  useEffect(() => {
    if (settings) {
      void loadRefs('repo1');
      void loadRefs('repo2');
      void loadSuggestion();
    }
  }, [settings, loadRefs, loadSuggestion]);

  const onValuesChange = (changed: Partial<FormValues>, all: FormValues) => {
    const demo = settings?.demo;
    // The demo's baseline tag means nothing in another repository; left in place it made every real analysis
    // fail with "unknown revision 'migration-baseline'".
    if (demo && 'repo1Path' in changed && all.baseline === demo.baseline && all.repo1Path !== demo.repo1Path) {
      form.setFieldValue('baseline', '');
    }
    if ('mode' in changed) {
      form.setFields(FORM_FIELDS.map((name) => ({ name, errors: [] })));
    }
    if ('repo1Path' in changed || 'repo1Head' in changed || 'mode' in changed) schedule('repo1', () => void loadRefs('repo1'));
    if ('repo2Path' in changed || 'repo2Head' in changed || 'mode' in changed) schedule('repo2', () => void loadRefs('repo2'));
    if (['repo1Path', 'repo1Head', 'repo2Path', 'repo2Head', 'mode'].some((k) => k in changed)) {
      schedule('suggestion', () => void loadSuggestion());
    }
  };

  const refOptions = (r: RefsView | null) => {
    if (!r) return [];
    const plain = (names: string[]) => names.map((n) => ({ value: n, label: n }));
    return [
      { label: t('migrationRisk.refs.branches'), options: plain(r.branches) },
      { label: t('migrationRisk.refs.remoteBranches'), options: plain(r.remoteBranches) },
      { label: t('migrationRisk.refs.tags'), options: plain(r.tags) },
    ].filter((g) => g.options.length > 0);
  };

  const baselineOptions = useMemo(() => {
    const r = refs.repo1;
    if (!r) return [];
    const s = suggestion?.baseline;
    const groups = [
      { label: t('migrationRisk.refs.suggested'), options: s ? [{ value: s.shortId, label: `${s.shortId}  ${s.message}` }] : [] },
      { label: t('migrationRisk.refs.tags'), options: r.tags.map((n) => ({ value: n, label: n })) },
      { label: t('migrationRisk.refs.branches'), options: [...r.branches, ...r.remoteBranches].map((n) => ({ value: n, label: n })) },
      { label: t('migrationRisk.refs.commits'), options: r.recentCommits.map((c) => ({ value: c.shortId, label: `${c.shortId}  ${c.message}` })) },
    ];
    return groups.filter((g) => g.options.length > 0);
  }, [refs.repo1, suggestion, t]);

  const filter = (input: string, option?: { label?: unknown }) =>
    !input || String(option?.label ?? '').toLowerCase().includes(input.toLowerCase());

  const headExtra = (r: RefsView | null) => r && (
    <Text type="secondary">
      {r.currentBranch
        ? t('migrationRisk.refs.current', { branch: r.currentBranch })
        : t('migrationRisk.refs.detached')}
      {' · '}{r.head.shortId} {r.head.message}
    </Text>
  );

  const baselineExtra = suggestion?.reason === 'MERGE_BASE' && suggestion.baseline ? (
    <Space size={4} wrap>
      <Text type="secondary">
        {t('migrationRisk.refs.mergeBase', { commit: `${suggestion.baseline.shortId} ${suggestion.baseline.message}` })}
      </Text>
      <Button type="link" size="small" style={{ padding: 0 }}
        onClick={() => form.setFields([{ name: 'baseline', value: suggestion.baseline!.shortId, errors: [] }])}>
        {t('migrationRisk.refs.use')}
      </Button>
    </Space>
  ) : suggestion?.reason === 'NO_SHARED_HISTORY'
    ? <Text type="secondary">{t('migrationRisk.refs.noSharedHistory')}</Text>
    : null;

  const required = [{ required: true, whitespace: true }];

  return (
    <Form<FormValues> form={form} layout="vertical" initialValues={EMPTY_FORM}
      onValuesChange={onValuesChange} onFinish={onAnalyze}>
      <Row gutter={16} align="bottom">
        <Col xs={24} lg={10}>
          <Form.Item name="mode" label={t('migrationRisk.mode')}>
            <Segmented<FormValues['mode']>
              options={[
                { value: 'GIT', label: t('migrationRisk.mode.git') },
                { value: 'DIRECTORY', label: t('migrationRisk.mode.folders') },
              ]}
            />
          </Form.Item>
        </Col>
        <Col xs={24} lg={14}>
          <Form.Item name="release" label={t('migrationRisk.release')} tooltip={t('migrationRisk.releaseHint')}
            rules={[{ max: 120 }]}>
            <AutoComplete allowClear placeholder={t('migrationRisk.releasePlaceholder')} filterOption={filter}
              options={releases.map((r) => ({ value: r.name, label: r.name }))} />
          </Form.Item>
        </Col>
      </Row>

      {mode === 'GIT' ? (
        <Row gutter={16}>
          <Col xs={24} lg={12}>
            <Form.Item name="repo1Path" label={t('migrationRisk.repo1Path')} tooltip={t('migrationRisk.repo1PathHint')}
              rules={required}>
              <Input placeholder="/work/repo1" allowClear />
            </Form.Item>
          </Col>
          <Col xs={24} lg={12}>
            <Form.Item name="repo2Path" label={t('migrationRisk.repo2Path')} tooltip={t('migrationRisk.repo2PathHint')}
              rules={required}>
              <Input placeholder="/work/repo2" allowClear />
            </Form.Item>
          </Col>
          <Col xs={24} lg={6}>
            <Form.Item name="repo1Head" label={t('migrationRisk.repo1Branch')} tooltip={t('migrationRisk.repo1BranchHint')}
            extra={headExtra(refs.repo1)}>
              <AutoComplete options={refOptions(refs.repo1)} filterOption={filter} placeholder="HEAD"
                popupMatchSelectWidth={360} />
            </Form.Item>
          </Col>
          <Col xs={24} lg={6}>
            <Form.Item name="baseline" label={t('migrationRisk.baseline')} tooltip={t('migrationRisk.baselineHint')}
              rules={required} extra={baselineExtra}>
              <AutoComplete options={baselineOptions} filterOption={filter} allowClear placeholder="v1.8.0 / a1b2c3d"
                popupMatchSelectWidth={420} />
            </Form.Item>
          </Col>
          <Col xs={24} lg={6}>
            <Form.Item name="repo2Head" label={t('migrationRisk.repo2Branch')} tooltip={t('migrationRisk.repo2BranchHint')}
            extra={headExtra(refs.repo2)}>
              <AutoComplete options={refOptions(refs.repo2)} filterOption={filter} placeholder="HEAD"
                popupMatchSelectWidth={360} />
            </Form.Item>
          </Col>
        </Row>
      ) : (
        <Row gutter={16}>
          <Col xs={24} lg={8}>
            <Form.Item name="baselinePath" label={t('migrationRisk.folder.baseline')}
              tooltip={t('migrationRisk.folder.baselineHint')} rules={required}>
              <Input placeholder="/work/repo1-at-migration-start" allowClear />
            </Form.Item>
          </Col>
          <Col xs={24} lg={8}>
            <Form.Item name="repo1Path" label={t('migrationRisk.folder.repo1')} rules={required}>
              <Input placeholder="/work/repo1" allowClear />
            </Form.Item>
          </Col>
          <Col xs={24} lg={8}>
            <Form.Item name="repo2Path" label={t('migrationRisk.folder.repo2')} rules={required}>
              <Input placeholder="/work/repo2" allowClear />
            </Form.Item>
          </Col>
          <Col span={24}>
            <Text type="secondary" style={{ display: 'block', marginTop: -8, marginBottom: 12 }}>
              {t('migrationRisk.folder.hint')}
            </Text>
          </Col>
        </Row>
      )}

      <Space wrap>
        <Button type="primary" htmlType="submit" icon={<SearchOutlined />} loading={loading}>
          {t('migrationRisk.analyze')}
        </Button>
        {settings?.demo && (
          <Tooltip title={t('migrationRisk.demoHint')}>
            <Button icon={<ExperimentOutlined />} onClick={() => onDemo(mode)} disabled={loading}>
              {t('migrationRisk.useDemo')}
            </Button>
          </Tooltip>
        )}
        {settings && (
          <Text type="secondary" style={{ fontSize: 12 }}>
            {t('migrationRisk.allowedRoots', { roots: settings.allowedRoots.join(', ') })}
          </Text>
        )}
      </Space>
    </Form>
  );
}
