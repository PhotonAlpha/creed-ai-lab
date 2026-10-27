import { useCallback, useEffect, useRef, useState } from 'react';
import type { FormInstance } from 'antd';
import { AutoComplete, Button, Col, Form, Input, Row, Segmented, Space, Switch, Tooltip, Typography } from 'antd';
import { ExperimentOutlined, SearchOutlined } from '@ant-design/icons';
import { migrationRiskApi } from '../../api/migrationRisk';
import type { MigrationRiskSettings, RefsView, ReleaseView } from '../../api/migrationRisk';
import { useI18n } from '../../locales';
import { fieldErrors, FORM_FIELDS } from './constants';
import type { FormValues } from './constants';

const { Text } = Typography;

export const EMPTY_FORM: FormValues = {
  mode: 'GIT',
  release: '',
  repo1Path: '',
  repo1Head: 'HEAD',
  repo2Path: '',
  repo2Head: 'HEAD',
  repo2bPath: '',
  repo2bHead: 'HEAD',
  skipTests: false,
};

type RepoKey = 'repo1' | 'repo2' | 'repo2b';

interface Props {
  form: FormInstance<FormValues>;
  settings: MigrationRiskSettings | null;
  releases: ReleaseView[];
  loading: boolean;
  onAnalyze: (values: FormValues) => void;
  onDemo: (mode: FormValues['mode']) => void;
}

/**
 * repo1 HEAD compared with repo2 directly, no baseline. Git mode: each repository with its own branch picker
 * (branches, remote branches, tags). Folder mode: plain directories. Either way, an optional repo2B (the migration
 * split repo1 in two) is merged with repo2, test code can be skipped, and a release name saves the result for review.
 */
export function AnalysisForm({ form, settings, releases, loading, onAnalyze, onDemo }: Props) {
  const { t } = useI18n();
  const mode = Form.useWatch('mode', form) ?? 'GIT';
  const repo2b = !!Form.useWatch('repo2bPath', form)?.trim();
  const [refs, setRefs] = useState<Record<RepoKey, RefsView | null>>({ repo1: null, repo2: null, repo2b: null });
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
      void loadRefs('repo2b');
    }
  }, [settings, loadRefs]);

  const onValuesChange = (changed: Partial<FormValues>) => {
    if ('mode' in changed) {
      form.setFields(FORM_FIELDS.map((name) => ({ name, errors: [] })));
    }
    if ('repo1Path' in changed || 'repo1Head' in changed || 'mode' in changed) schedule('repo1', () => void loadRefs('repo1'));
    if ('repo2Path' in changed || 'repo2Head' in changed || 'mode' in changed) schedule('repo2', () => void loadRefs('repo2'));
    if ('repo2bPath' in changed || 'repo2bHead' in changed || 'mode' in changed) schedule('repo2b', () => void loadRefs('repo2b'));
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

  const required = [{ required: true, whitespace: true }];

  return (
    <Form<FormValues> form={form} layout="vertical" initialValues={EMPTY_FORM}
      onValuesChange={onValuesChange} onFinish={onAnalyze}>
      <Row gutter={16} align="bottom">
        <Col xs={24} lg={8}>
          <Form.Item name="mode" label={t('migrationRisk.mode')}>
            <Segmented<FormValues['mode']>
              options={[
                { value: 'GIT', label: t('migrationRisk.mode.git') },
                { value: 'DIRECTORY', label: t('migrationRisk.mode.folders') },
              ]}
            />
          </Form.Item>
        </Col>
        <Col xs={24} lg={12}>
          <Form.Item name="release" label={t('migrationRisk.release')} tooltip={t('migrationRisk.releaseHint')}
            rules={[{ max: 120 }]}>
            <AutoComplete allowClear placeholder={t('migrationRisk.releasePlaceholder')} filterOption={filter}
              options={releases.map((r) => ({ value: r.name, label: r.name }))} />
          </Form.Item>
        </Col>
        <Col xs={24} lg={4}>
          <Form.Item name="skipTests" label={t('migrationRisk.skipTests')} tooltip={t('migrationRisk.skipTestsHint')}
            valuePropName="checked">
            <Switch />
          </Form.Item>
        </Col>
      </Row>

      {mode === 'GIT' ? (
        <Row gutter={16}>
          <Col xs={24} lg={8}>
            <Form.Item name="repo1Path" label={t('migrationRisk.repo1Path')} tooltip={t('migrationRisk.repo1PathHint')}
              rules={required}>
              <Input placeholder={mode === 'GIT' ? '/work/repo1 · https://github.com/acme/repo1.git' : '/work/repo1'} allowClear />
            </Form.Item>
          </Col>
          <Col xs={24} lg={8}>
            <Form.Item name="repo2Path" label={t('migrationRisk.repo2Path')} tooltip={t('migrationRisk.repo2PathHint')}
              rules={required}>
              <Input placeholder={mode === 'GIT' ? '/work/repo2 · https://…/repo2.git' : '/work/repo2'} allowClear />
            </Form.Item>
          </Col>
          <Col xs={24} lg={8}>
            <Form.Item name="repo2bPath" label={t('migrationRisk.repo2bPath')} tooltip={t('migrationRisk.repo2bPathHint')}>
              <Input placeholder="/work/repo2b" allowClear />
            </Form.Item>
          </Col>
          <Col xs={24} lg={8}>
            <Form.Item name="repo1Head" label={t('migrationRisk.repo1Branch')} tooltip={t('migrationRisk.repo1BranchHint')}
            extra={headExtra(refs.repo1)}>
              <AutoComplete options={refOptions(refs.repo1)} filterOption={filter} placeholder="HEAD"
                popupMatchSelectWidth={360} />
            </Form.Item>
          </Col>
          <Col xs={24} lg={8}>
            <Form.Item name="repo2Head" label={t('migrationRisk.repo2Branch')} tooltip={t('migrationRisk.repo2BranchHint')}
            extra={headExtra(refs.repo2)}>
              <AutoComplete options={refOptions(refs.repo2)} filterOption={filter} placeholder="HEAD"
                popupMatchSelectWidth={360} />
            </Form.Item>
          </Col>
          {repo2b && (
            <Col xs={24} lg={8}>
              <Form.Item name="repo2bHead" label={t('migrationRisk.repo2bBranch')} extra={headExtra(refs.repo2b)}>
                <AutoComplete options={refOptions(refs.repo2b)} filterOption={filter} placeholder="HEAD"
                  popupMatchSelectWidth={360} />
              </Form.Item>
            </Col>
          )}
        </Row>
      ) : (
        <Row gutter={16}>
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
          <Col xs={24} lg={8}>
            <Form.Item name="repo2bPath" label={t('migrationRisk.folder.repo2b')} tooltip={t('migrationRisk.repo2bPathHint')}>
              <Input placeholder="/work/repo2b" allowClear />
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
            {mode === 'GIT' && settings.allowedHosts?.length > 0
              && ` · ${t('migrationRisk.remoteHosts', { hosts: settings.allowedHosts.join(', ') })}`}
          </Text>
        )}
      </Space>
    </Form>
  );
}
