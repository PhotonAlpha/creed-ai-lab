package com.creed.resource.veracode.template;

import java.util.List;

/**
 * Which cells of an uploaded workbook show which data - what the page's grid edits and the JXLS markup is written from.
 * Rows and columns are 0-based, as POI counts them; the page shows them as Excel does ({@code B6}).
 *
 * @param sheets one entry per sheet that has bindings, by name
 */
public record TemplateMapping(List<SheetMapping> sheets) {

    public static final TemplateMapping EMPTY = new TemplateMapping(List.of());

    public TemplateMapping {
        sheets = sheets == null ? List.of() : List.copyOf(sheets);
    }

    public boolean isEmpty() {
        return sheets.stream().allMatch(SheetMapping::isEmpty);
    }

    /**
     * @param lists  data rows: each repeated once per item of its dataset at export, what is below moving down
     * @param values single cells showing a summary field ({@code TemplateFields#SUMMARY}), outside every data row
     */
    public record SheetMapping(String sheet, List<ListBinding> lists, List<CellBinding> values) {

        public SheetMapping {
            lists = lists == null ? List.of() : List.copyOf(lists);
            values = values == null ? List.of() : List.copyOf(values);
        }

        public boolean isEmpty() {
            return lists.isEmpty() && values.isEmpty();
        }
    }

    /**
     * A template row that becomes one row per item of {@code dataset} ({@code sca} = the SCA findings table,
     * {@code sast} = the SAST Jira table). The whole row is repeated - its unbound cells keep their text and style.
     *
     * @param cells the row's bound cells; each shows a field of the dataset
     */
    public record ListBinding(String dataset, int row, List<FieldCell> cells) {

        public ListBinding {
            cells = cells == null ? List.of() : List.copyOf(cells);
        }
    }

    public record FieldCell(int col, String field) {
    }

    public record CellBinding(int row, int col, String field) {
    }
}
