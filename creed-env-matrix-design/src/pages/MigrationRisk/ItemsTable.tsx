import { useMemo, useState } from 'react';
import { App, Button, Input, Modal, Radio, Segmented, Select, Space, Table, Tag, Tooltip, Typography } from 'antd';
import type { TableColumnsType } from 'antd';
import { CheckOutlined, DiffOutlined, EditOutlined, WarningOutlined } from '@ant-design/icons';
import { RISK_CATEGORIES, diffUrl, migrationRiskApi } from '../../api/migrationRisk';
import type {
  Location,
  MigrationRiskAnalysis,
  ReviewStatus,
  RiskCategory,
  RiskItem,
  RiskLevel,
} from '../../api/migrationRisk';
import { ApiError } from '../../api/client';
import { useI18n } from '../../locales';
import type { MessageKey } from '../../locales';
import { LEVEL_COLOR, REVIEW_COLOR, STATE_COLOR } from './constants';

const { Text, Paragraph } = Typography;

type LevelFilter = 'ALL' | RiskLevel;
type ReviewFilter = 'ALL' | ReviewStatus;
type CodeFilter = 'ALL' | 'MAIN' | 'TEST';

/** How many detail lines a row shows before "N more". */
const DETAIL_LINES = 3;

/** `+ added`, `- removed`, `~ changed`, `L12: matched line` — coloured like a diff so the column scans at a glance. */
function DetailLine({ line }: { line: string }) {
  const type = line.startsWith('+ ') ? 'success' : line.startsWith('- ') ? 'danger'
    : line.startsWith('~ ') ? 'warning' : undefined;
  return (
    <Paragraph style={{ margin: 0, fontSize: 12, fontFamily: 'monospace' }} ellipsis={{ rows: 1, tooltip: line }}>
      <Text type={type} style={{ fontFamily: 'monospace', fontSize: 12 }}>{line}</Text>
    </Paragraph>
  );
}

const where = (l: Location | null) => (l ? (l.line != null ? `${l.path}:${l.line}` : l.path) : null);

/** A file record's findings; a run saved before file records has one finding per row. */
const partsOf = (i: RiskItem) => (i.parts?.length ? i.parts : [i]);

const matchesKeyword = (item: RiskItem, keyword: string) => {
  if (!keyword) return true;
  const k = keyword.toLowerCase();
  return [item.element, item.module, item.upstream?.path, item.target?.path, item.review?.note,
    ...partsOf(item).flatMap((p) => [p.element, p.target?.path, p.target?.element, ...p.details, ...p.evidence])]
    .some((v) => v?.toLowerCase().includes(k));
};

/** `com.acme.shop.order.OrderService#cancel(String)` -> `OrderService#cancel(String)`; keys and paths unchanged. */
const shortName = (name: string | null) => (name && name.includes('#')
  ? name.slice(name.lastIndexOf('.', name.indexOf('#')) + 1) : name);

/** A part's name next to its file: the method / key, not the path again. */
const partName = (p: RiskItem, file: RiskItem) => (p.element === file.element ? null : p.element);

/**
 * Every file has a diff: Java as RefactoringMiner's AST diff, anything else (yml, properties, .vm / .ftl ...) as a
 * line diff on the same page. Only a row without a repo1 path (none today) has nothing to open.
 */
const hasDiff = (i: RiskItem) => i.upstream != null;

const REVIEWER_KEY = 'migration-risk.reviewer';
const storedReviewer = () => {
  try {
    return localStorage.getItem(REVIEWER_KEY) ?? '';
  } catch {
    return '';
  }
};

interface Props {
  analysis: MigrationRiskAnalysis | null;
  loading: boolean;
  /** Replaces the given items (by id) in the analysis on screen. */
  onReviewed: (items: RiskItem[]) => void;
}

export function ItemsTable({ analysis, loading, onReviewed }: Props) {
  const { t } = useI18n();
  const { message } = App.useApp();
  const saved = analysis?.analysisId != null;

  const [levelFilter, setLevelFilter] = useState<LevelFilter>('ALL');
  const [reviewFilter, setReviewFilter] = useState<ReviewFilter>('ALL');
  const [categories, setCategories] = useState<RiskCategory[]>([]);
  const [modules, setModules] = useState<string[]>([]);
  const [codeFilter, setCodeFilter] = useState<CodeFilter>('ALL');
  const [keyword, setKeyword] = useState('');
  const [selected, setSelected] = useState<string[]>([]);
  const [reviewer, setReviewer] = useState(storedReviewer);
  const [saving, setSaving] = useState(false);
  /** One page-level modal for notes (a modal per row loses its trigger state on re-render — see the skill). */
  const [editing, setEditing] = useState<{ items: RiskItem[]; status: ReviewStatus; note: string } | null>(null);

  const rememberReviewer = (name: string) => {
    setReviewer(name);
    try {
      localStorage.setItem(REVIEWER_KEY, name);
    } catch {
      // private mode: the name just is not remembered
    }
  };

  /** Modules present in this run, for the filter; runs saved before tags have none. */
  const moduleOptions = useMemo(
    () => [...new Set((analysis?.items ?? []).map((i) => i.module).filter((m): m is string => !!m))].sort(),
    [analysis],
  );
  const hasTests = (analysis?.items ?? []).some((i) => i.test);

  const rows = useMemo(
    () => (analysis?.items ?? [])
      .filter((i) => levelFilter === 'ALL' || i.level === levelFilter)
      .filter((i) => reviewFilter === 'ALL' || i.review?.status === reviewFilter)
      .filter((i) => categories.length === 0 || partsOf(i).some((p) => categories.includes(p.category)))
      .filter((i) => modules.length === 0 || (i.module != null && modules.includes(i.module)))
      .filter((i) => codeFilter === 'ALL' || (codeFilter === 'TEST') === i.test)
      .filter((i) => matchesKeyword(i, keyword.trim())),
    [analysis, levelFilter, reviewFilter, categories, modules, codeFilter, keyword],
  );

  const submit = async (items: RiskItem[], status: ReviewStatus, note?: string) => {
    const ids = items.map((i) => i.review?.itemId).filter((id): id is number => id != null);
    if (ids.length === 0) return;
    setSaving(true);
    try {
      const body = { status, note: note || undefined, reviewer: reviewer.trim() || undefined };
      const updated = ids.length === 1
        ? [await migrationRiskApi.review(ids[0], body)]
        : await migrationRiskApi.reviewAll(ids, body);
      onReviewed(updated);
      setSelected([]);
      setEditing(null);
      message.success(t('migrationRisk.review.saved', { count: updated.length }));
    } catch (e) {
      message.error(e instanceof ApiError && e.status === 409 ? t('migrationRisk.review.conflict') : (e as Error).message);
    } finally {
      setSaving(false);
    }
  };

  const categoryLabel = (c: RiskCategory) => t(`migrationRisk.category.${c}` as MessageKey);
  const categoryHint = (c: RiskCategory) => t(`migrationRisk.categoryHint.${c}` as MessageKey);

  const columns: TableColumnsType<RiskItem> = [
    {
      title: t('migrationRisk.col.level'),
      dataIndex: 'level',
      width: 80,
      render: (l: RiskLevel) => <Tag color={LEVEL_COLOR[l]}>{t(`migrationRisk.level.${l}` as MessageKey)}</Tag>,
    },
    {
      title: t('migrationRisk.col.category'),
      dataIndex: 'category',
      width: 150,
      render: (c: RiskCategory, i) => {
        const others = [...new Set(partsOf(i).map((p) => p.category))].filter((x) => x !== c);
        return (
          <Space size={4} wrap>
            <Tooltip title={categoryHint(c)}><Text>{categoryLabel(c)}</Text></Tooltip>
            {others.length > 0 && (
              <Tooltip title={others.map(categoryLabel).join(' / ')}><Tag>+{others.length}</Tag></Tooltip>
            )}
          </Space>
        );
      },
    },
    {
      title: t('migrationRisk.col.file'),
      dataIndex: 'element',
      width: 380,
      render: (element: string, i) => (
        <Space direction="vertical" size={2}>
          <Paragraph style={{ margin: 0 }} ellipsis={{ rows: 2, tooltip: element }}>
            <Text code>{element}</Text>
          </Paragraph>
          {(i.module || i.test || i.parts?.length > 0) && (
            <Space size={4} wrap>
              {i.parts?.length > 0 && <Tag>{t('migrationRisk.parts.count', { count: i.parts.length })}</Tag>}
              {i.module && (
                <Tooltip title={t('migrationRisk.filter.module')}>
                  <Tag color="geekblue" style={{ cursor: 'pointer' }}
                    onClick={() => setModules([i.module!])}>{i.module}</Tag>
                </Tooltip>
              )}
              {i.test && <Tag color="purple">{t('migrationRisk.tag.test')}</Tag>}
            </Space>
          )}
        </Space>
      ),
    },
    {
      title: t('migrationRisk.col.change'),
      dataIndex: 'change',
      width: 100,
      render: (c: RiskItem['change']) => <Tag>{t(`migrationRisk.change.${c}` as MessageKey)}</Tag>,
    },
    {
      title: t('migrationRisk.col.details'),
      dataIndex: 'details',
      width: 420,
      render: (_: string[], i) => {
        // One line per finding in the file: level, category, and the method / key (or its first data line).
        const parts = partsOf(i);
        const rest = parts.length - DETAIL_LINES;
        return (
          <div style={{ maxWidth: 400 }}>
            {parts.slice(0, DETAIL_LINES).map((p) => (
              <Paragraph key={p.id} style={{ margin: 0, fontSize: 12 }}
                ellipsis={{ rows: 1, tooltip: [partName(p, i), ...p.details].filter(Boolean).join('\n') }}>
                <Tag color={LEVEL_COLOR[p.level]} style={{ fontSize: 11, lineHeight: '16px', marginInlineEnd: 4 }}>
                  {categoryLabel(p.category)}
                </Tag>
                <Text style={{ fontFamily: 'monospace', fontSize: 12 }}>
                  {shortName(partName(p, i)) ?? p.details[0] ?? p.evidence[0] ?? ''}
                </Text>
              </Paragraph>
            ))}
            {rest > 0 && (
              <Tooltip title={<div style={{ whiteSpace: 'pre-wrap' }}>{parts.slice(DETAIL_LINES)
                .map((p) => `${categoryLabel(p.category)}  ${shortName(partName(p, i)) ?? p.details[0] ?? ''}`).join('\n')}</div>}>
                <Text type="secondary" style={{ fontSize: 12 }}>{t('migrationRisk.details.more', { count: rest })}</Text>
              </Tooltip>
            )}
          </div>
        );
      },
    },
    {
      title: t('migrationRisk.col.target'),
      key: 'target',
      width: 300,
      render: (_, i) => (
        <Space direction="vertical" size={2}>
          <Space size={4}>
            {i.targetState && (
              <Tag color={STATE_COLOR[i.targetState]}>{t(`migrationRisk.state.${i.targetState}` as MessageKey)}</Tag>
            )}
            {i.target?.repo && <Tag color="cyan">{i.target.repo}</Tag>}
          </Space>
          {where(i.target) && (
            <Paragraph style={{ margin: 0 }} ellipsis={{ rows: 2, tooltip: i.target?.element ?? where(i.target) }}>
              <Text type="secondary" style={{ fontSize: 12 }}>{where(i.target)}</Text>
            </Paragraph>
          )}
        </Space>
      ),
    },
    {
      title: t('migrationRisk.col.diff'),
      key: 'diff',
      width: 160,
      render: (_, i) => analysis && hasDiff(i) && (
        <Space direction="vertical" size={4}>
          <Tooltip title={t('migrationRisk.diff.a')}>
            <Button size="small" icon={<DiffOutlined />} href={diffUrl(analysis.source, 'A', i.upstream?.path)}
              target="_blank">{t('migrationRisk.diff.openA')}</Button>
          </Tooltip>
          {/* No counterpart in repo2 = nothing to put next to repo1's code. */}
          <Tooltip title={t('migrationRisk.diff.b')}>
            <Button size="small" icon={<DiffOutlined />} disabled={!i.target}
              href={diffUrl(analysis.source, 'B', i.target?.path ?? i.upstream?.path)} target="_blank">
              {t('migrationRisk.diff.openB')}
            </Button>
          </Tooltip>
        </Space>
      ),
    },
    ...(saved ? [{
      title: t('migrationRisk.col.review'),
      key: 'review',
      width: 250,
      fixed: 'right' as const,
      render: (_: unknown, i: RiskItem) => i.review && (
        <Space direction="vertical" size={2}>
          <Space size={4} wrap>
            <Tooltip title={i.review.carriedOverFrom != null ? t('migrationRisk.review.carried') : undefined}>
              <Tag color={REVIEW_COLOR[i.review.status]}>
                {t(`migrationRisk.review.${i.review.status}` as MessageKey)}
                {i.review.carriedOverFrom != null && ' ↻'}
              </Tag>
            </Tooltip>
            <Tooltip title={t('migrationRisk.review.markSafe')}>
              <Button size="small" type="text" icon={<CheckOutlined />} loading={saving}
                disabled={i.review.status === 'SAFE'} onClick={() => void submit([i], 'SAFE', i.review?.note ?? undefined)} />
            </Tooltip>
            <Tooltip title={t('migrationRisk.review.markAttention')}>
              <Button size="small" type="text" danger icon={<WarningOutlined />}
                onClick={() => setEditing({ items: [i], status: 'ATTENTION', note: i.review?.note ?? '' })} />
            </Tooltip>
            <Tooltip title={t('migrationRisk.review.edit')}>
              <Button size="small" type="text" icon={<EditOutlined />}
                onClick={() => setEditing({ items: [i], status: i.review!.status, note: i.review?.note ?? '' })} />
            </Tooltip>
          </Space>
          {i.review.note && (
            <Paragraph style={{ margin: 0, fontSize: 12 }} ellipsis={{ rows: 2, tooltip: i.review.note }}>
              {i.review.note}
            </Paragraph>
          )}
          {i.review.reviewer && (
            <Text type="secondary" style={{ fontSize: 12 }}>
              {i.review.reviewer}{i.review.reviewedAt && ` · ${new Date(i.review.reviewedAt).toLocaleString()}`}
            </Text>
          )}
        </Space>
      ),
    }] : []),
  ];

  const selectedItems = (analysis?.items ?? []).filter((i) => selected.includes(i.id));

  return (
    <Space direction="vertical" size="middle" style={{ width: '100%' }}>
      <Space wrap>
        <Segmented<LevelFilter>
          value={levelFilter}
          onChange={setLevelFilter}
          options={[
            { value: 'ALL', label: t('migrationRisk.filter.all') },
            { value: 'HIGH', label: t('migrationRisk.level.HIGH') },
            { value: 'MEDIUM', label: t('migrationRisk.level.MEDIUM') },
            { value: 'LOW', label: t('migrationRisk.level.LOW') },
          ]}
        />
        {saved && (
          <Segmented<ReviewFilter>
            value={reviewFilter}
            onChange={setReviewFilter}
            options={[
              { value: 'ALL', label: t('migrationRisk.filter.allReviews') },
              { value: 'PENDING', label: t('migrationRisk.review.PENDING') },
              { value: 'SAFE', label: t('migrationRisk.review.SAFE') },
              { value: 'ATTENTION', label: t('migrationRisk.review.ATTENTION') },
            ]}
          />
        )}
        <Select<RiskCategory[]>
          mode="multiple"
          allowClear
          maxTagCount="responsive"
          value={categories}
          onChange={setCategories}
          placeholder={t('migrationRisk.filter.category')}
          style={{ minWidth: 220 }}
          options={RISK_CATEGORIES.map((c) => ({ value: c, label: categoryLabel(c) }))}
        />
        {moduleOptions.length > 0 && (
          <Select<string[]>
            mode="multiple"
            allowClear
            maxTagCount="responsive"
            value={modules}
            onChange={setModules}
            placeholder={t('migrationRisk.filter.module')}
            style={{ minWidth: 180 }}
            options={moduleOptions.map((m) => ({ value: m, label: m }))}
          />
        )}
        {hasTests && (
          <Segmented<CodeFilter>
            value={codeFilter}
            onChange={setCodeFilter}
            options={[
              { value: 'ALL', label: t('migrationRisk.filter.allCode') },
              { value: 'MAIN', label: t('migrationRisk.filter.mainOnly') },
              { value: 'TEST', label: t('migrationRisk.filter.testOnly') },
            ]}
          />
        )}
        <Input.Search
          allowClear
          placeholder={t('migrationRisk.search')}
          onSearch={setKeyword}
          onChange={(e) => !e.target.value && setKeyword('')}
          style={{ width: 240 }}
        />
      </Space>
      {saved && (
        <Space wrap>
          <Input
            value={reviewer}
            onChange={(e) => rememberReviewer(e.target.value)}
            placeholder={t('migrationRisk.review.reviewer')}
            style={{ width: 180 }}
            maxLength={120}
          />
          <Button icon={<CheckOutlined />} disabled={selectedItems.length === 0} loading={saving}
            onClick={() => void submit(selectedItems, 'SAFE')}>
            {t('migrationRisk.review.selectedSafe', { count: selectedItems.length })}
          </Button>
          <Button danger icon={<WarningOutlined />} disabled={selectedItems.length === 0}
            onClick={() => setEditing({ items: selectedItems, status: 'ATTENTION', note: '' })}>
            {t('migrationRisk.review.selectedAttention', { count: selectedItems.length })}
          </Button>
        </Space>
      )}
      <Table<RiskItem>
        rowKey="id"
        size="middle"
        loading={loading}
        columns={columns}
        dataSource={rows}
        pagination={{ pageSize: 20, showSizeChanger: true }}
        // Numeric width, not 'max-content': with a fixed-right column the latter collapses the last scrolling column.
        scroll={{ x: saved ? 1870 : 1620 }}
        rowSelection={saved ? { selectedRowKeys: selected, onChange: (keys) => setSelected(keys as string[]) } : undefined}
        expandable={{
          // Every finding in the file; line-level differences are the diff page's job.
          expandedRowRender: (i) => (
            <Space direction="vertical" size="middle" style={{ width: '100%' }}>
              {partsOf(i).map((p) => (
                <Space key={p.id} direction="vertical" size={2} style={{ width: '100%' }}>
                  <Space size={4} wrap>
                    <Tag color={LEVEL_COLOR[p.level]}>{t(`migrationRisk.level.${p.level}` as MessageKey)}</Tag>
                    <Tooltip title={categoryHint(p.category)}><Text strong>{categoryLabel(p.category)}</Text></Tooltip>
                    {partName(p, i) && <Text code>{partName(p, i)}</Text>}
                    {p.targetState && (
                      <Tag color={STATE_COLOR[p.targetState]}>{t(`migrationRisk.state.${p.targetState}` as MessageKey)}</Tag>
                    )}
                    {where(p.target) && (
                      <Text type="secondary" style={{ fontSize: 12 }}>
                        → {p.target?.repo ? `${p.target.repo}: ` : ''}{where(p.target)}
                      </Text>
                    )}
                  </Space>
                  {p.evidence.map((e) => <Text key={e} type="secondary" style={{ fontSize: 12 }}>{e}</Text>)}
                  {p.details.map((d) => <DetailLine key={d} line={d} />)}
                </Space>
              ))}
            </Space>
          ),
        }}
      />
      <Modal
        open={editing != null}
        title={t('migrationRisk.review.title', { count: editing?.items.length ?? 0 })}
        okText={t('migrationRisk.review.save')}
        confirmLoading={saving}
        onCancel={() => setEditing(null)}
        onOk={() => editing && void submit(editing.items, editing.status, editing.note)}
        destroyOnHidden
      >
        {editing && (
          <Space direction="vertical" style={{ width: '100%' }}>
            {editing.items.length === 1 && <Text code>{editing.items[0].element}</Text>}
            <Radio.Group
              value={editing.status}
              onChange={(e) => setEditing({ ...editing, status: e.target.value })}
              options={(['SAFE', 'ATTENTION', 'PENDING'] as ReviewStatus[]).map((s) => ({
                value: s,
                label: t(`migrationRisk.review.${s}` as MessageKey),
              }))}
            />
            <Input.TextArea
              rows={4}
              maxLength={2000}
              showCount
              value={editing.note}
              onChange={(e) => setEditing({ ...editing, note: e.target.value })}
              placeholder={t('migrationRisk.review.notePlaceholder')}
            />
          </Space>
        )}
      </Modal>
    </Space>
  );
}
