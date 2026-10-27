import { useMemo, useState } from 'react';
import { useSearchParams } from 'react-router-dom';
import {
  App, Button, Collapse, Input, Modal, Radio, Segmented, Select, Space, Table, Tag, Tooltip, Typography,
} from 'antd';
import type { TableColumnsType } from 'antd';
import {
  CheckOutlined, DiffOutlined, EditOutlined, StopOutlined, WarningOutlined,
} from '@ant-design/icons';
import { RISK_CATEGORIES, diffUrl, migrationRiskApi } from '../../api/migrationRisk';
import type {
  Location,
  MigrationRiskAnalysis,
  MigrationRiskSettings,
  ReviewDelta,
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
type DeltaFilter = 'ALL' | ReviewDelta;

/** How many detail lines a row shows before "N more". */
const DETAIL_LINES = 3;
const PAGE_SIZES = [20, 50, 100, 200];

/** `+ added`, `- removed`, `~ changed`, `≈ suggested` — coloured like a diff so the column scans at a glance. */
function DetailLine({ line }: { line: string }) {
  const type = line.startsWith('+ ') ? 'success' : line.startsWith('- ') ? 'danger'
    : line.startsWith('~ ') ? 'warning' : line.startsWith('≈ ') ? 'secondary' : undefined;
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
  return [item.element, item.module, item.upstream?.path, item.target?.path, item.review?.note, item.review?.jiraKey,
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
const hasDiff = (i: RiskItem) => i.upstream != null || i.target != null;

const REVIEWER_KEY = 'migration-risk.reviewer';
const storedReviewer = () => {
  try {
    return localStorage.getItem(REVIEWER_KEY) ?? '';
  } catch {
    return '';
  }
};

/**
 * Filters, page and page size live in the URL (`?level=HIGH&module=order&page=2`): a link to a filtered view can be
 * sent to a colleague, and the browser's back button undoes a filter.
 */
function useTableState() {
  const [params, setParams] = useSearchParams();
  const list = (key: string) => (params.get(key) ? params.get(key)!.split(',').filter(Boolean) : []);
  const state = {
    level: (params.get('level') ?? 'ALL') as LevelFilter,
    review: (params.get('review') ?? 'ALL') as ReviewFilter,
    delta: (params.get('delta') ?? 'ALL') as DeltaFilter,
    code: (params.get('code') ?? 'ALL') as CodeFilter,
    categories: list('category') as RiskCategory[],
    modules: list('module'),
    keyword: params.get('q') ?? '',
    page: Math.max(1, Number(params.get('page')) || 1),
    size: PAGE_SIZES.includes(Number(params.get('size'))) ? Number(params.get('size')) : PAGE_SIZES[0],
  };
  /** A filter change goes back to page 1; `null` / '' / 'ALL' / [] remove the parameter. */
  const set = (changes: Partial<Record<'level' | 'review' | 'delta' | 'code' | 'category' | 'module' | 'q' | 'page' | 'size', string | string[] | number | null>>) => {
    setParams((prev) => {
      const next = new URLSearchParams(prev);
      if (!('page' in changes)) next.delete('page');
      Object.entries(changes).forEach(([key, value]) => {
        const v = Array.isArray(value) ? value.join(',') : value == null ? '' : String(value);
        if (!v || v === 'ALL' || (key === 'page' && v === '1') || (key === 'size' && v === String(PAGE_SIZES[0]))) {
          next.delete(key);
        } else {
          next.set(key, v);
        }
      });
      return next;
    }, { replace: true });
  };
  return { state, set };
}

/** Per module: files by level and how many are synced — where to split the work. */
function ModuleSummary({ items, active, onPick }: { items: RiskItem[]; active: string[]; onPick: (m: string) => void }) {
  const { t } = useI18n();
  const rows = useMemo(() => {
    const byModule = new Map<string, { module: string; HIGH: number; MEDIUM: number; LOW: number; synced: number; total: number }>();
    items.forEach((i) => {
      const m = i.module ?? '?';
      const row = byModule.get(m) ?? { module: m, HIGH: 0, MEDIUM: 0, LOW: 0, synced: 0, total: 0 };
      row[i.level] += 1;
      row.total += 1;
      if (i.review?.status === 'SYNCED') row.synced += 1;
      byModule.set(m, row);
    });
    return [...byModule.values()].sort((a, b) => b.HIGH - a.HIGH || b.total - a.total);
  }, [items]);
  const count = (n: number, color: string) => (n > 0 ? <Tag color={color}>{n}</Tag> : <Text type="secondary">0</Text>);
  return (
    <Table
      rowKey="module"
      size="small"
      pagination={false}
      scroll={{ y: 240 }}
      dataSource={rows}
      columns={[
        {
          title: t('migrationRisk.filter.module'),
          dataIndex: 'module',
          render: (m: string) => (
            <Button type="link" size="small" style={{ padding: 0, fontWeight: active.includes(m) ? 600 : undefined }}
              onClick={() => onPick(m)}>{m}</Button>
          ),
        },
        { title: t('migrationRisk.level.HIGH'), dataIndex: 'HIGH', width: 90, render: (n: number) => count(n, LEVEL_COLOR.HIGH) },
        { title: t('migrationRisk.level.MEDIUM'), dataIndex: 'MEDIUM', width: 90, render: (n: number) => count(n, LEVEL_COLOR.MEDIUM) },
        { title: t('migrationRisk.level.LOW'), dataIndex: 'LOW', width: 90, render: (n: number) => count(n, LEVEL_COLOR.LOW) },
        { title: t('migrationRisk.review.SYNCED'), dataIndex: 'synced', width: 110, render: (n: number, r) => `${n} / ${r.total}` },
      ]}
    />
  );
}

interface Props {
  analysis: MigrationRiskAnalysis | null;
  settings: MigrationRiskSettings | null;
  loading: boolean;
  /** Replaces the given items (by id) in the analysis on screen. */
  onReviewed: (items: RiskItem[]) => void;
  /** An ignore rule was added from a row: the page refreshes its rule list. */
  onIgnoreRuleAdded: () => void;
}

export function ItemsTable({ analysis, settings, loading, onReviewed, onIgnoreRuleAdded }: Props) {
  const { t } = useI18n();
  const { message } = App.useApp();
  const saved = analysis?.analysisId != null;
  const { state: f, set } = useTableState();

  const [selected, setSelected] = useState<string[]>([]);
  const [reviewer, setReviewer] = useState(storedReviewer);
  const [saving, setSaving] = useState(false);
  /** One page-level modal for notes (a modal per row loses its trigger state on re-render — see the skill). */
  const [editing, setEditing] = useState<{ items: RiskItem[]; status: ReviewStatus; note: string } | null>(null);
  /** The same for "ignore this file / this kind of finding". */
  const [ignoring, setIgnoring] = useState<{ pattern: string; category: RiskCategory | null; scoped: boolean; note: string } | null>(null);
  const [keywordDraft, setKeywordDraft] = useState(f.keyword);

  const rememberReviewer = (name: string) => {
    setReviewer(name);
    try {
      localStorage.setItem(REVIEWER_KEY, name);
    } catch {
      // private mode: the name just is not remembered
    }
  };

  const items = useMemo(() => analysis?.items ?? [], [analysis]);
  /** Modules present in this run, for the filter; runs saved before tags have none. */
  const moduleOptions = useMemo(
    () => [...new Set(items.map((i) => i.module).filter((m): m is string => !!m))].sort(),
    [items],
  );
  const hasTests = items.some((i) => i.test);
  const hasDelta = items.some((i) => i.review?.delta);

  const rows = useMemo(
    () => items
      .filter((i) => f.level === 'ALL' || i.level === f.level)
      .filter((i) => f.review === 'ALL' || i.review?.status === f.review)
      .filter((i) => f.delta === 'ALL' || i.review?.delta === f.delta)
      .filter((i) => f.categories.length === 0 || partsOf(i).some((p) => f.categories.includes(p.category)))
      .filter((i) => f.modules.length === 0 || (i.module != null && f.modules.includes(i.module)))
      .filter((i) => f.code === 'ALL' || (f.code === 'TEST') === i.test)
      .filter((i) => matchesKeyword(i, f.keyword.trim())),
    // the URL state is rebuilt each render; its primitive parts are what change
    // eslint-disable-next-line react-hooks/exhaustive-deps
    [items, f.level, f.review, f.delta, f.categories.join(), f.modules.join(), f.code, f.keyword],
  );
  // A page beyond the filtered rows would show an empty table: clamp instead.
  const lastPage = Math.max(1, Math.ceil(rows.length / f.size));
  const page = Math.min(f.page, lastPage);

  const submit = async (targets: RiskItem[], status: ReviewStatus, note?: string) => {
    const ids = targets.map((i) => i.review?.itemId).filter((id): id is number => id != null);
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

  const createJira = async (item: RiskItem) => {
    if (item.review == null) return;
    try {
      const updated = await migrationRiskApi.createJira(item.review.itemId);
      onReviewed([updated]);
      message.success(t('migrationRisk.jira.created', { key: updated.review?.jiraKey ?? '' }));
    } catch (e) {
      message.error((e as Error).message);
    }
  };

  const addIgnoreRule = async () => {
    if (!ignoring || !analysis) return;
    try {
      await migrationRiskApi.addIgnoreRule({
        pattern: ignoring.pattern.trim(),
        category: ignoring.category,
        scope: ignoring.scoped ? analysis.source.repo1Path : null,
        note: ignoring.note.trim() || undefined,
      });
      setIgnoring(null);
      onIgnoreRuleAdded();
      message.success(t('migrationRisk.ignore.added'));
    } catch (e) {
      message.error((e as Error).message);
    }
  };

  const categoryLabel = (c: RiskCategory) => t(`migrationRisk.category.${c}` as MessageKey);
  const categoryHint = (c: RiskCategory) => t(`migrationRisk.categoryHint.${c}` as MessageKey);
  const reviewLabel = (s: ReviewStatus) => t(`migrationRisk.review.${s}` as MessageKey);

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
          <Space size={4} wrap>
            {i.parts?.length > 0 && <Tag>{t('migrationRisk.parts.count', { count: i.parts.length })}</Tag>}
            {i.module && (
              <Tooltip title={t('migrationRisk.filter.module')}>
                <Tag color="geekblue" style={{ cursor: 'pointer' }} onClick={() => set({ module: [i.module!] })}>
                  {i.module}
                </Tag>
              </Tooltip>
            )}
            {i.test && <Tag color="purple">{t('migrationRisk.tag.test')}</Tag>}
            {i.review?.delta === 'NEW' && <Tag color="blue">{t('migrationRisk.delta.NEW')}</Tag>}
            {i.review?.delta === 'CHANGED' && <Tag color="orange">{t('migrationRisk.delta.CHANGED')}</Tag>}
          </Space>
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
      width: 150,
      // a file only one side has opens one-sided; repo1's path, else repo2's
      render: (_, i) => analysis && hasDiff(i) && (
        <Tooltip title={t('migrationRisk.diff.hint')}>
          <Button size="small" icon={<DiffOutlined />} target="_blank"
            href={diffUrl(analysis.source, i.upstream?.path ?? i.target?.path, i.upstream ? i.target?.path : null)}>
            {t('migrationRisk.diff.open')}
          </Button>
        </Tooltip>
      ),
    },
    ...(saved ? [{
      title: t('migrationRisk.col.review'),
      key: 'review',
      width: 290,
      fixed: 'right' as const,
      render: (_: unknown, i: RiskItem) => i.review && (
        <Space direction="vertical" size={2}>
          <Space size={4} wrap>
            <Tooltip title={i.review.auto ? t('migrationRisk.review.auto')
              : i.review.carriedOverFrom != null ? t('migrationRisk.review.carried') : undefined}>
              <Tag color={REVIEW_COLOR[i.review.status]}>
                {reviewLabel(i.review.status)}
                {i.review.auto && ' ⚙'}
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
            {i.review.jiraKey ? (
              settings?.jiraBrowseUrl
                ? <a href={`${settings.jiraBrowseUrl}${i.review.jiraKey}`} target="_blank" rel="noreferrer"><Tag color="blue">{i.review.jiraKey}</Tag></a>
                : <Tooltip title={settings?.jiraMock ? t('migrationRisk.jira.mock') : undefined}><Tag color="blue">{i.review.jiraKey}</Tag></Tooltip>
            ) : (
              <Tooltip title={t('migrationRisk.jira.create')}>
                <Button size="small" type="text" onClick={() => void createJira(i)}>Jira</Button>
              </Tooltip>
            )}
          </Space>
          {/* The file changed after its review: what it was, and how many of its findings moved since. */}
          {i.review.previous && (
            <Tooltip title={i.review.previous.note ?? undefined}>
              <Text type="warning" style={{ fontSize: 12 }}>
                {t('migrationRisk.review.previous', {
                  status: reviewLabel(i.review.previous.status),
                  count: i.review.changedParts.length,
                })}
              </Text>
            </Tooltip>
          )}
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

  const selectedItems = items.filter((i) => selected.includes(i.id));

  return (
    <Space direction="vertical" size="middle" style={{ width: '100%' }}>
      {moduleOptions.length > 1 && (
        <Collapse
          size="small"
          items={[{
            key: 'modules',
            label: t('migrationRisk.modules.title', { count: moduleOptions.length }),
            children: (
              <ModuleSummary items={items} active={f.modules}
                onPick={(m) => set({ module: f.modules.length === 1 && f.modules[0] === m ? [] : [m] })} />
            ),
          }]}
        />
      )}
      <Space wrap>
        <Segmented<LevelFilter>
          value={f.level}
          onChange={(v) => set({ level: v })}
          options={[
            { value: 'ALL', label: t('migrationRisk.filter.all') },
            { value: 'HIGH', label: t('migrationRisk.level.HIGH') },
            { value: 'MEDIUM', label: t('migrationRisk.level.MEDIUM') },
            { value: 'LOW', label: t('migrationRisk.level.LOW') },
          ]}
        />
        {saved && (
          <Segmented<ReviewFilter>
            value={f.review}
            onChange={(v) => set({ review: v })}
            options={[
              { value: 'ALL', label: t('migrationRisk.filter.allReviews') },
              ...(['PENDING', 'SAFE', 'ATTENTION', 'SYNCED'] as ReviewStatus[]).map((s) => ({ value: s, label: reviewLabel(s) })),
            ]}
          />
        )}
        {hasDelta && (
          <Segmented<DeltaFilter>
            value={f.delta}
            onChange={(v) => set({ delta: v })}
            options={[
              { value: 'ALL', label: t('migrationRisk.delta.ALL') },
              { value: 'NEW', label: t('migrationRisk.delta.NEW') },
              { value: 'CHANGED', label: t('migrationRisk.delta.CHANGED') },
              { value: 'SAME', label: t('migrationRisk.delta.SAME') },
            ]}
          />
        )}
        <Select<RiskCategory[]>
          mode="multiple"
          allowClear
          maxTagCount="responsive"
          value={f.categories}
          onChange={(v) => set({ category: v })}
          placeholder={t('migrationRisk.filter.category')}
          style={{ minWidth: 220 }}
          options={RISK_CATEGORIES.map((c) => ({ value: c, label: categoryLabel(c) }))}
        />
        {moduleOptions.length > 0 && (
          <Select<string[]>
            mode="multiple"
            allowClear
            maxTagCount="responsive"
            value={f.modules}
            onChange={(v) => set({ module: v })}
            placeholder={t('migrationRisk.filter.module')}
            style={{ minWidth: 180 }}
            options={moduleOptions.map((m) => ({ value: m, label: m }))}
          />
        )}
        {hasTests && (
          <Segmented<CodeFilter>
            value={f.code}
            onChange={(v) => set({ code: v })}
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
          value={keywordDraft}
          onChange={(e) => {
            setKeywordDraft(e.target.value);
            if (!e.target.value) set({ q: null });
          }}
          onSearch={(v) => set({ q: v.trim() || null })}
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
          <Button disabled={selectedItems.length === 0} loading={saving}
            onClick={() => void submit(selectedItems, 'SYNCED')}>
            {t('migrationRisk.review.selectedSynced', { count: selectedItems.length })}
          </Button>
        </Space>
      )}
      <Table<RiskItem>
        rowKey="id"
        size="middle"
        loading={loading}
        columns={columns}
        dataSource={rows}
        // Controlled: a fixed `pageSize` with showSizeChanger ignored every size change, and filtering kept a page
        // number past the end (an empty table). Page, size and filters are all in the URL now.
        pagination={{
          current: page,
          pageSize: f.size,
          pageSizeOptions: PAGE_SIZES,
          showSizeChanger: true,
          showTotal: (total) => t('migrationRisk.pagination.total', { total }),
          onChange: (p, size) => set(size !== f.size ? { size, page: 1 } : { page: p }),
        }}
        // Numeric width, not 'max-content': with a fixed-right column the latter collapses the last scrolling column.
        scroll={{ x: saved ? 1940 : 1650 }}
        rowSelection={saved ? { selectedRowKeys: selected, onChange: (keys) => setSelected(keys as string[]) } : undefined}
        expandable={{
          // Every finding in the file; line-level differences are the diff page's job.
          expandedRowRender: (i) => (
            <Space direction="vertical" size="middle" style={{ width: '100%' }}>
              <Space size={4} wrap>
                <Button size="small" icon={<StopOutlined />}
                  onClick={() => setIgnoring({ pattern: i.element, category: null, scoped: true, note: '' })}>
                  {t('migrationRisk.ignore.file')}
                </Button>
              </Space>
              {partsOf(i).map((p) => (
                <Space key={p.id} direction="vertical" size={2} style={{ width: '100%' }}>
                  <Space size={4} wrap>
                    <Tag color={LEVEL_COLOR[p.level]}>{t(`migrationRisk.level.${p.level}` as MessageKey)}</Tag>
                    <Tooltip title={categoryHint(p.category)}><Text strong>{categoryLabel(p.category)}</Text></Tooltip>
                    {partName(p, i) && <Text code>{partName(p, i)}</Text>}
                    {i.review?.changedParts.includes(p.id) && (
                      <Tag color="orange">{t('migrationRisk.review.changedSince')}</Tag>
                    )}
                    {p.targetState && (
                      <Tag color={STATE_COLOR[p.targetState]}>{t(`migrationRisk.state.${p.targetState}` as MessageKey)}</Tag>
                    )}
                    {where(p.target) && (
                      <Text type="secondary" style={{ fontSize: 12 }}>
                        → {p.target?.repo ? `${p.target.repo}: ` : ''}{where(p.target)}
                      </Text>
                    )}
                    {analysis && p.target && p.target.path !== i.target?.path && (
                      // this finding lives in another repo2 file than the row's: open that one
                      <Button size="small" type="link" icon={<DiffOutlined />} target="_blank"
                        href={diffUrl(analysis.source, i.upstream?.path, p.target.path)}>
                        {t('migrationRisk.diff.open')}
                      </Button>
                    )}
                    <Button size="small" type="link" icon={<StopOutlined />}
                      onClick={() => setIgnoring({ pattern: i.element, category: p.category, scoped: true, note: '' })}>
                      {t('migrationRisk.ignore.category')}
                    </Button>
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
              options={(['SAFE', 'ATTENTION', 'SYNCED', 'PENDING'] as ReviewStatus[]).map((s) => ({
                value: s,
                label: reviewLabel(s),
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
      <Modal
        open={ignoring != null}
        title={t('migrationRisk.ignore.title')}
        okText={t('migrationRisk.ignore.save')}
        onCancel={() => setIgnoring(null)}
        onOk={() => void addIgnoreRule()}
        okButtonProps={{ disabled: !ignoring?.pattern.trim() }}
        destroyOnHidden
      >
        {ignoring && (
          <Space direction="vertical" style={{ width: '100%' }}>
            <Text type="secondary">{t('migrationRisk.ignore.hint')}</Text>
            <Input value={ignoring.pattern} onChange={(e) => setIgnoring({ ...ignoring, pattern: e.target.value })}
              addonBefore={t('migrationRisk.ignore.pattern')} />
            <Select<RiskCategory | null>
              value={ignoring.category}
              onChange={(v) => setIgnoring({ ...ignoring, category: v })}
              style={{ width: '100%' }}
              options={[
                { value: null, label: t('migrationRisk.ignore.wholeFile') },
                ...RISK_CATEGORIES.map((c) => ({ value: c, label: categoryLabel(c) })),
              ]}
            />
            <Radio.Group
              value={ignoring.scoped}
              onChange={(e) => setIgnoring({ ...ignoring, scoped: e.target.value })}
              options={[
                { value: true, label: t('migrationRisk.ignore.thisProject') },
                { value: false, label: t('migrationRisk.ignore.allProjects') },
              ]}
            />
            <Input value={ignoring.note} maxLength={1000} placeholder={t('migrationRisk.ignore.note')}
              onChange={(e) => setIgnoring({ ...ignoring, note: e.target.value })} />
          </Space>
        )}
      </Modal>
    </Space>
  );
}
