import { useCallback, useEffect, useMemo, useState } from 'react';
import { PageContainer } from '@ant-design/pro-components';
import {
  Alert, App, Button, Card, Col, Descriptions, Empty, List, Popconfirm, Row, Select, Space, Spin, Tabs, Tag, Tooltip,
  Typography, Upload, theme,
} from 'antd';
import {
  DeleteOutlined, FileExcelOutlined, FileSearchOutlined, SaveOutlined, UndoOutlined, UploadOutlined,
} from '@ant-design/icons';
import { reportTemplateApi, saveBlob } from '../../../api/veracode';
import type {
  ListDataset, ReportTemplateDetail, ReportTemplateSummary, TemplateDataset, TemplateField, TemplateMapping,
} from '../../../api/veracode';
import { useI18n } from '../../../locales';
import type { MessageKey } from '../../../locales';
import enUS from '../../../locales/en-US';
import { ApplicationAliasesPanel } from './ApplicationAliasesPanel';
import { DATASET_COLOR, TemplateGrid } from './TemplateGrid';
import {
  bindingAt, cellRef, countBindings, emptyDataRows, listAt, setCellField, setDataRow, sheetMapping,
} from './mappingEdit';

const { Text, Paragraph } = Typography;

/**
 * Report templates (asked for, 2026-11-12): upload the company workbook, click its cells and bind them to SCA / SAST
 * fields, save; the Veracode page then exports an analysis through it. The backend writes the JXLS markup from the
 * bindings at each export (`creed-resource-veracode`, `/api/veracode/report-templates`).
 */
export function ReportTemplatesPage() {
  const { t } = useI18n();
  const { message, modal } = App.useApp();
  const { token } = theme.useToken();
  const [templates, setTemplates] = useState<ReportTemplateSummary[]>([]);
  const [fields, setFields] = useState<Record<TemplateDataset, TemplateField[]> | null>(null);
  const [detail, setDetail] = useState<ReportTemplateDetail | null>(null);
  /** The bindings being edited; `detail.mapping` is what is saved. */
  const [draft, setDraft] = useState<TemplateMapping>({ sheets: [] });
  const [sheet, setSheet] = useState('');
  const [selected, setSelected] = useState<{ row: number; col: number } | null>(null);
  const [loading, setLoading] = useState(false);
  const [saving, setSaving] = useState(false);
  const [uploading, setUploading] = useState(false);
  const [tab, setTab] = useState<'templates' | 'aliases'>('templates');

  const dirty = !!detail && JSON.stringify(detail.mapping) !== JSON.stringify(draft);

  const refresh = useCallback(() => reportTemplateApi.list().then(setTemplates)
    .catch((e: Error) => message.error(e.message)), [message]);

  useEffect(() => {
    void refresh();
    reportTemplateApi.fields().then(setFields).catch((e: Error) => message.error(e.message));
  }, [refresh, message]);

  /** A field's label in the page's language; a key this page does not know shows the backend's English label. */
  const label = useCallback((dataset: TemplateDataset, key: string) => {
    const messageKey = `veracode.tpl.field.${key}`;
    if (messageKey in enUS) return t(messageKey as MessageKey);
    return fields?.[dataset].find((f) => f.key === key)?.label ?? key;
  }, [t, fields]);

  const open = (id: string) => {
    const go = () => {
      setLoading(true);
      reportTemplateApi.get(id)
        .then((d) => {
          setDetail(d);
          setDraft(d.mapping);
          setSheet(d.sheets[0]?.name ?? '');
          setSelected(null);
        })
        .catch((e: Error) => message.error(e.message))
        .finally(() => setLoading(false));
    };
    if (dirty) {
      modal.confirm({ title: t('veracode.tpl.discardTitle'), content: t('veracode.tpl.discardContent'), onOk: go });
    } else {
      go();
    }
  };

  const upload = async (file: File) => {
    setUploading(true);
    try {
      const created = await reportTemplateApi.upload(file);
      message.success(t('veracode.tpl.uploaded', { name: created.name }));
      await refresh();
      setDetail(created);
      setDraft(created.mapping);
      setSheet(created.sheets[0]?.name ?? '');
      setSelected(null);
    } catch (e) {
      message.error((e as Error).message);
    } finally {
      setUploading(false);
    }
    return false;
  };

  const save = async () => {
    if (!detail) return;
    const empty = emptyDataRows(draft);
    if (empty.length) {
      message.warning(t('veracode.tpl.emptyRows', { rows: empty.join(', ') }));
      return;
    }
    setSaving(true);
    try {
      const saved = await reportTemplateApi.saveMapping(detail.id, draft);
      setDetail(saved);
      setDraft(saved.mapping);
      message.success(t('veracode.tpl.saved'));
      void refresh();
    } catch (e) {
      // the backend names the cell: "SCA components!C5: a summary field on a data row would repeat"
      message.error((e as Error).message);
    } finally {
      setSaving(false);
    }
  };

  const remove = async (id: string) => {
    try {
      await reportTemplateApi.remove(id);
      if (detail?.id === id) {
        setDetail(null);
        setDraft({ sheets: [] });
      }
      await refresh();
    } catch (e) {
      message.error((e as Error).message);
    }
  };

  const current = detail?.sheets.find((s) => s.name === sheet);
  const sm = sheetMapping(draft, sheet);
  const list = selected ? listAt(sm, selected.row) : undefined;
  const binding = selected ? bindingAt(sm, selected.row, selected.col) : undefined;
  const selectedText = useMemo(() => {
    if (!current || !selected) return '';
    return current.data.find((r) => r.row === selected.row)?.cells.find((c) => c.col === selected.col)?.text ?? '';
  }, [current, selected]);
  const counts = countBindings(draft);

  const datasetName = useCallback((dataset: ListDataset) => t(`veracode.tpl.dataset.${dataset}`), [t]);

  const fieldOptions = (dataset: TemplateDataset) => (fields?.[dataset] ?? []).map((f) => ({
    value: f.key, label: label(dataset, f.key),
  }));

  return (
    <PageContainer
      title={t('veracode.tpl.title')}
      subTitle={tab === 'aliases' ? t('veracode.alias.subtitle') : t('veracode.tpl.subtitle')}
      tabList={[
        { tab: t('veracode.tpl.tab.templates'), key: 'templates' },
        { tab: t('veracode.tpl.tab.aliases'), key: 'aliases' },
      ]}
      tabActiveKey={tab}
      onTabChange={(key) => setTab(key as 'templates' | 'aliases')}
    >
      {/* both stay mounted: each holds unsaved edits a tab switch must not drop */}
      <div hidden={tab !== 'aliases'}>
        <ApplicationAliasesPanel />
      </div>
      <div hidden={tab !== 'templates'}>
        <Row gutter={[16, 16]}>
          <Col xs={24} lg={6}>
            <Card
              title={t('veracode.tpl.list')}
              extra={(
                <Upload accept=".xlsx,.xlsm" showUploadList={false} beforeUpload={(f) => upload(f)} disabled={uploading}>
                  <Button size="small" type="primary" icon={<UploadOutlined />} loading={uploading}>
                    {t('veracode.tpl.upload')}
                  </Button>
                </Upload>
              )}
            >
              <List<ReportTemplateSummary>
                dataSource={templates}
                locale={{ emptyText: <Empty description={t('veracode.tpl.none')} /> }}
                renderItem={(item) => (
                  <List.Item
                    onClick={() => open(item.id)}
                    style={{
                      cursor: 'pointer',
                      paddingInline: 8,
                      borderRadius: token.borderRadius,
                      background: item.id === detail?.id ? token.controlItemBgActive : undefined,
                    }}
                    actions={[
                      <Tooltip key="jxls" title={t('veracode.tpl.jxlsHint')}>
                        <Button
                          type="text"
                          size="small"
                          icon={<FileSearchOutlined />}
                          onClick={(e) => {
                            e.stopPropagation();
                            reportTemplateApi.jxls(item.id).then(saveBlob).catch((err: Error) => message.error(err.message));
                          }}
                        />
                      </Tooltip>,
                      <Popconfirm
                        key="delete"
                        title={t('veracode.tpl.deleteConfirm', { name: item.name })}
                        onConfirm={(e) => {
                          e?.stopPropagation();
                          void remove(item.id);
                        }}
                        onCancel={(e) => e?.stopPropagation()}
                      >
                        <Button type="text" size="small" danger icon={<DeleteOutlined />} onClick={(e) => e.stopPropagation()} />
                      </Popconfirm>,
                    ]}
                  >
                    <List.Item.Meta
                      avatar={<FileExcelOutlined style={{ fontSize: 20, color: token.colorSuccess }} />}
                      title={<Text ellipsis={{ tooltip: item.name }}>{item.name}</Text>}
                      description={item.lists + item.values > 0
                        ? t('veracode.tpl.bound', { lists: item.lists, values: item.values })
                        : <Text type="warning">{t('veracode.tpl.unbound')}</Text>}
                    />
                  </List.Item>
                )}
              />
            </Card>
          </Col>
          <Col xs={24} lg={18}>
            <Spin spinning={loading}>
              {!detail ? (
                <Card><Empty description={t('veracode.tpl.pick')} /></Card>
              ) : (
                <Card
                  title={(
                    <Space size={8} wrap>
                      <FileExcelOutlined style={{ color: token.colorSuccess }} />
                      {detail.name}
                      <Text type="secondary" style={{ fontWeight: 'normal' }}>{detail.fileName}</Text>
                      {dirty && <Tag color="warning">{t('veracode.tpl.unsaved')}</Tag>}
                    </Space>
                  )}
                  extra={(
                    <Space>
                      <Button icon={<UndoOutlined />} disabled={!dirty} onClick={() => setDraft(detail.mapping)}>
                        {t('veracode.tpl.revert')}
                      </Button>
                      <Button type="primary" icon={<SaveOutlined />} disabled={!dirty} loading={saving} onClick={() => void save()}>
                        {t('veracode.tpl.save')}
                      </Button>
                    </Space>
                  )}
                >
                  <Paragraph type="secondary" style={{ marginTop: 0 }}>
                    {t('veracode.tpl.howTo')}
                    {' '}
                    {(['sca', 'sast', 'scaStats', 'sastStats'] as const).map((d) => (
                      <Tag key={d} color={DATASET_COLOR[d]}>{datasetName(d)}</Tag>
                    ))}
                    <Tag color={DATASET_COLOR.summary}>{t('veracode.tpl.summary')}</Tag>
                    {t('veracode.tpl.counts', counts)}
                  </Paragraph>
                  <Row gutter={16}>
                    <Col xs={24} xl={17}>
                      <Tabs
                        activeKey={sheet}
                        onChange={(key) => {
                          setSheet(key);
                          setSelected(null);
                        }}
                        items={detail.sheets.map((s) => {
                          const bound = sheetMapping(draft, s.name);
                          const n = bound.lists.length + bound.values.length;
                          return {
                            key: s.name,
                            label: n > 0 ? <Space size={4}>{s.name}<Tag style={{ marginInlineEnd: 0 }}>{n}</Tag></Space> : s.name,
                            children: (
                              <>
                                {s.truncated && <Alert type="info" showIcon style={{ marginBottom: 8 }} message={t('veracode.tpl.truncated')} />}
                                {s.hidden && (
                                  <Alert
                                    type="warning"
                                    showIcon
                                    style={{ marginBottom: 8 }}
                                    message={t('veracode.tpl.hidden', {
                                      cells: s.hidden.cells.toLocaleString(),
                                      lastRow: s.hidden.lastRow.toLocaleString(),
                                    })}
                                    description={t('veracode.tpl.hiddenHint', { example: s.hidden.example })}
                                  />
                                )}
                                <TemplateGrid
                                  sheet={s}
                                  mapping={bound}
                                  label={label}
                                  datasetName={datasetName}
                                  selected={sheet === s.name ? selected : null}
                                  onSelect={(row, col) => setSelected({ row, col })}
                                />
                              </>
                            ),
                          };
                        })}
                      />
                    </Col>
                    <Col xs={24} xl={7}>
                      {/* the binding of the selected cell: its row as a data row, then the cell's field */}
                      <Card size="small" title={selected ? `${sheet}!${cellRef(selected.row, selected.col)}` : t('veracode.tpl.cell')}>
                        {!selected ? (
                          <Text type="secondary">{t('veracode.tpl.clickCell')}</Text>
                        ) : (
                          <Space direction="vertical" style={{ width: '100%' }} size="middle">
                            {selectedText && (
                              <Descriptions size="small" column={1} items={[{ label: t('veracode.tpl.text'), children: selectedText }]} />
                            )}
                            <div>
                              <Text strong>{t('veracode.tpl.rowKind', { row: selected.row + 1 })}</Text>
                              <Select<'none' | ListDataset>
                                style={{ width: '100%', marginTop: 4 }}
                                value={list?.dataset ?? 'none'}
                                onChange={(v) => setDraft(setDataRow(draft, sheet, selected.row, v === 'none' ? null : v))}
                                options={[
                                  { value: 'none', label: t('veracode.tpl.plainRow') },
                                  { value: 'sca', label: t('veracode.tpl.scaRow') },
                                  { value: 'sast', label: t('veracode.tpl.sastRow') },
                                  { value: 'scaStats', label: t('veracode.tpl.scaStatsRow') },
                                  { value: 'sastStats', label: t('veracode.tpl.sastStatsRow') },
                                ]}
                              />
                              <Text type="secondary" style={{ display: 'block', marginTop: 4, fontSize: token.fontSizeSM }}>
                                {t(list ? 'veracode.tpl.dataRowHint' : 'veracode.tpl.plainRowHint')}
                              </Text>
                            </div>
                            <div>
                              <Text strong>
                                {list ? t('veracode.tpl.columnField', { dataset: datasetName(list.dataset) }) : t('veracode.tpl.summaryField')}
                              </Text>
                              <Select
                                allowClear
                                showSearch
                                optionFilterProp="label"
                                style={{ width: '100%', marginTop: 4 }}
                                placeholder={t('veracode.tpl.chooseField')}
                                value={binding?.field}
                                onChange={(v?: string) => setDraft(setCellField(draft, sheet, selected.row, selected.col, v ?? null))}
                                options={fieldOptions(list ? list.dataset : 'summary')}
                              />
                            </div>
                          </Space>
                        )}
                      </Card>
                    </Col>
                  </Row>
                </Card>
              )}
            </Spin>
          </Col>
        </Row>
      </div>
    </PageContainer>
  );
}
