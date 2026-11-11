import type { TemplateMapping } from '../../../api/veracode';

type SheetMapping = TemplateMapping['sheets'][number];
type ListBinding = SheetMapping['lists'][number];

/** `B6` for row 5, column 1 (0-based, as the mapping counts). */
export const cellRef = (row: number, col: number) => {
  let letters = '';
  for (let c = col + 1; c > 0; c = Math.floor((c - 1) / 26)) {
    letters = String.fromCharCode(65 + ((c - 1) % 26)) + letters;
  }
  return `${letters}${row + 1}`;
};

export const columnName = (col: number) => cellRef(0, col).replace(/\d+$/, '');

export const sheetMapping = (m: TemplateMapping, sheet: string): SheetMapping =>
  m.sheets.find((s) => s.sheet === sheet) ?? { sheet, lists: [], values: [] };

export const listAt = (sm: SheetMapping, row: number): ListBinding | undefined => sm.lists.find((l) => l.row === row);

/** What a cell shows: a data-row field, a summary field, or nothing. */
export const bindingAt = (sm: SheetMapping, row: number, col: number) => {
  const list = listAt(sm, row);
  if (list) {
    const cell = list.cells.find((c) => c.col === col);
    return cell ? { dataset: list.dataset, field: cell.field } : undefined;
  }
  const value = sm.values.find((v) => v.row === row && v.col === col);
  return value ? { dataset: 'summary' as const, field: value.field } : undefined;
};

/** The mapping with one sheet replaced; sheets left without bindings are dropped. */
const withSheet = (m: TemplateMapping, next: SheetMapping): TemplateMapping => ({
  sheets: [...m.sheets.filter((s) => s.sheet !== next.sheet), next]
    .filter((s) => s.lists.length > 0 || s.values.length > 0),
});

/**
 * Makes `row` a data row of `dataset`, or a plain row again (`null`). Summary cells on it go: they would repeat
 * with the row (the backend refuses that).
 */
export const setDataRow = (m: TemplateMapping, sheet: string, row: number, dataset: 'sca' | 'sast' | null) => {
  const sm = sheetMapping(m, sheet);
  const existing = listAt(sm, row);
  const lists = sm.lists.filter((l) => l.row !== row);
  if (dataset) {
    // switching the dataset keeps no field: the two datasets name different things
    lists.push({ dataset, row, cells: existing?.dataset === dataset ? existing.cells : [] });
  }
  return withSheet(m, {
    sheet,
    lists: lists.sort((a, b) => a.row - b.row),
    values: dataset ? sm.values.filter((v) => v.row !== row) : sm.values,
  });
};

/** Binds (or, with `null`, unbinds) a cell: a column of its data row, else a summary cell. */
export const setCellField = (m: TemplateMapping, sheet: string, row: number, col: number, field: string | null) => {
  const sm = sheetMapping(m, sheet);
  const list = listAt(sm, row);
  if (list) {
    const cells = list.cells.filter((c) => c.col !== col);
    if (field) cells.push({ col, field });
    return withSheet(m, {
      ...sm,
      lists: sm.lists.map((l) => (l === list ? { ...l, cells: cells.sort((a, b) => a.col - b.col) } : l)),
    });
  }
  const values = sm.values.filter((v) => !(v.row === row && v.col === col));
  if (field) values.push({ row, col, field });
  return withSheet(m, { ...sm, values });
};

/** Data rows with no bound cell yet, as `Sheet!5` - saving them would be refused. */
export const emptyDataRows = (m: TemplateMapping) =>
  m.sheets.flatMap((s) => s.lists.filter((l) => l.cells.length === 0).map((l) => `${s.sheet}!${l.row + 1}`));

export const countBindings = (m: TemplateMapping) => ({
  lists: m.sheets.reduce((n, s) => n + s.lists.length, 0),
  cells: m.sheets.reduce((n, s) => n + s.values.length + s.lists.reduce((k, l) => k + l.cells.length, 0), 0),
});
