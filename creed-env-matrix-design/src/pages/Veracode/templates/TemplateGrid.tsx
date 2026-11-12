import { useMemo } from 'react';
import type { CSSProperties } from 'react';
import { Tag, Tooltip, theme } from 'antd';
import type { ListDataset, TemplateDataset, TemplateMapping, TemplateSheet } from '../../../api/veracode';
import { bindingAt, columnName, listAt } from './mappingEdit';

/** Rows / columns past the used range the user can still bind (a data row is often the empty one under a header). */
const SPARE_ROWS = 3;
const SPARE_COLS = 1;

export const DATASET_COLOR: Record<TemplateDataset, string> = {
  sca: 'blue', sast: 'orange', scaStats: 'cyan', sastStats: 'green', summary: 'purple',
};

/** A data row's band (row background) and its row number's color, per dataset. */
const bandColors = (token: ReturnType<typeof theme.useToken>['token'], dataset: ListDataset) => ({
  sca: { band: token.colorPrimaryBg, text: token.colorPrimary },
  sast: { band: token.colorWarningBg, text: token.colorWarning },
  scaStats: { band: token.cyan1, text: token.cyan7 },
  sastStats: { band: token.colorSuccessBg, text: token.colorSuccess },
}[dataset]);

/**
 * The sheet drawn as Excel does - column letters, row numbers, merges, widths, bold and fills - with what each cell is
 * bound to on top. A click selects a cell; the side panel binds it.
 */
export function TemplateGrid({ sheet, mapping, label, datasetName, selected, onSelect }: {
  sheet: TemplateSheet;
  mapping: TemplateMapping['sheets'][number];
  label: (dataset: TemplateDataset, field: string) => string;
  /** A dataset's name, for the row number's tooltip. */
  datasetName: (dataset: ListDataset) => string;
  selected: { row: number; col: number } | null;
  onSelect: (row: number, col: number) => void;
}) {
  const { token } = theme.useToken();
  const rows = sheet.rows + SPARE_ROWS;
  const cols = Math.max(sheet.cols, 1) + SPARE_COLS;

  const cells = useMemo(() => {
    const byKey = new Map<string, TemplateSheet['data'][number]['cells'][number]>();
    sheet.data.forEach((r) => r.cells.forEach((c) => byKey.set(`${r.row}:${c.col}`, c)));
    return byKey;
  }, [sheet]);

  /** A merge's first cell spans it; the others are not drawn. */
  const { spans, covered } = useMemo(() => {
    const spanMap = new Map<string, { rowSpan: number; colSpan: number }>();
    const coveredSet = new Set<string>();
    sheet.merges.forEach((m) => {
      spanMap.set(`${m.firstRow}:${m.firstCol}`, { rowSpan: m.lastRow - m.firstRow + 1, colSpan: m.lastCol - m.firstCol + 1 });
      for (let r = m.firstRow; r <= m.lastRow; r++) {
        for (let c = m.firstCol; c <= m.lastCol; c++) {
          if (r !== m.firstRow || c !== m.firstCol) coveredSet.add(`${r}:${c}`);
        }
      }
    });
    return { spans: spanMap, covered: coveredSet };
  }, [sheet]);

  const head: CSSProperties = {
    background: token.colorFillAlter,
    color: token.colorTextSecondary,
    border: `1px solid ${token.colorBorderSecondary}`,
    fontSize: token.fontSizeSM,
    textAlign: 'center',
    position: 'sticky',
    zIndex: 1,
  };

  return (
    <div style={{ overflow: 'auto', maxHeight: 560, border: `1px solid ${token.colorBorderSecondary}` }}>
      <table style={{ borderCollapse: 'collapse', tableLayout: 'fixed', fontSize: token.fontSizeSM }}>
        <colgroup>
          <col style={{ width: 44 }} />
          {Array.from({ length: cols }, (_, c) => (
            <col key={c} style={{ width: Math.max(sheet.widths[c] ?? 64, 56) }} />
          ))}
        </colgroup>
        <thead>
          <tr>
            <th style={{ ...head, top: 0, left: 0, zIndex: 2 }} />
            {Array.from({ length: cols }, (_, c) => (
              <th key={c} style={{ ...head, top: 0 }}>{columnName(c)}</th>
            ))}
          </tr>
        </thead>
        <tbody>
          {Array.from({ length: rows }, (_, r) => {
            const list = listAt(mapping, r);
            const colors = list ? bandColors(token, list.dataset) : undefined;
            const band = colors?.band;
            return (
              <tr key={r}>
                <th style={{ ...head, left: 0, background: band ?? head.background }}>
                  {list ? (
                    <Tooltip title={datasetName(list.dataset)}>
                      <span style={{ color: colors?.text, fontWeight: 600 }}>
                        {r + 1}
                      </span>
                    </Tooltip>
                  ) : r + 1}
                </th>
                {Array.from({ length: cols }, (_, c) => {
                  const key = `${r}:${c}`;
                  if (covered.has(key)) return null;
                  const cell = cells.get(key);
                  const binding = bindingAt(mapping, r, c);
                  const isSelected = selected?.row === r && selected.col === c;
                  return (
                    <td
                      key={c}
                      {...spans.get(key)}
                      onClick={() => onSelect(r, c)}
                      style={{
                        border: `1px solid ${token.colorBorderSecondary}`,
                        outline: isSelected ? `2px solid ${token.colorPrimary}` : undefined,
                        outlineOffset: -2,
                        background: band ?? cell?.fill ?? token.colorBgContainer,
                        fontWeight: cell?.bold ? 600 : undefined,
                        padding: '2px 4px',
                        height: 26,
                        cursor: 'pointer',
                        overflow: 'hidden',
                        whiteSpace: 'nowrap',
                        textOverflow: 'ellipsis',
                        verticalAlign: 'top',
                      }}
                    >
                      {binding ? (
                        <Tooltip title={cell?.text ? `${cell.text} → ${label(binding.dataset, binding.field)}` : undefined}>
                          <Tag color={DATASET_COLOR[binding.dataset]} style={{ marginInlineEnd: 0, maxWidth: '100%', overflow: 'hidden', textOverflow: 'ellipsis' }}>
                            {label(binding.dataset, binding.field)}
                          </Tag>
                        </Tooltip>
                      ) : (
                        <span title={cell?.text}>{cell?.text}</span>
                      )}
                    </td>
                  );
                })}
              </tr>
            );
          })}
        </tbody>
      </table>
    </div>
  );
}
