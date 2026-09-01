package com.creed.report.export;

import com.creed.report.dynamic.DynamicTable;
import com.creed.report.dynamic.DynamicTableRequest;
import com.creed.report.dynamic.DynamicTableService;
import com.creed.report.i18n.CountryFormatter;
import com.creed.report.i18n.CountryProfile;
import org.apache.poi.ss.usermodel.Workbook;
import org.springframework.context.MessageSource;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * {@link ReportType#DYNAMIC} strategy: the caller-defined table of {@code /dynamic}, as one sheet
 * with the same columns, the same order and the same country formats as the page and the PDF.
 *
 * <p>It needs no new plumbing — {@code headers}, {@code data} and {@code title} ride in on
 * {@link ExcelExportRequest#parameters()}, which is exactly what that pass-through map is for, and
 * {@link DynamicTableRequest#from} makes both this and {@code DynamicReportController} read them
 * under the same names.
 *
 * <p>Numbers are written as numeric cells via {@link DynamicTable.Cell#excelValue()}, so the sheet
 * can still sum and sort a column that arrived as JSON numbers.
 */
@Component
public class DynamicTableExcelExporter implements ExcelReportExporter {

    private final DynamicTableService tableService;
    private final MessageSource messageSource;

    public DynamicTableExcelExporter(DynamicTableService tableService, MessageSource messageSource) {
        this.tableService = tableService;
        this.messageSource = messageSource;
    }

    @Override
    public ReportType reportType() {
        return ReportType.DYNAMIC;
    }

    @Override
    public void write(Workbook workbook, ExcelExportRequest request) {
        Locale locale = request.locale();
        CountryProfile profile = request.country();
        DynamicTable table = tableService.build(DynamicTableRequest.from(request.parameters()), profile, locale);

        ExcelStyles styles = new ExcelStyles(workbook);
        ExcelSheetBuilder sheet = ExcelSheetBuilder.create(workbook, styles, msg("excel.sheet.dynamic", locale))
                .title(table.title())
                .caption(msg("report.generatedAt", locale) + ": " + request.generatedAtText()
                         + "  |  " + msg("report.total", locale) + ": "
                         + CountryFormatter.number(table.size(), profile))
                .blank()
                .header(headerLabels(table, locale));

        int index = 1;
        for (List<DynamicTable.Cell> row : table.rows()) {
            // Same leading row-number column the page and the PDF show, so the three outputs line up.
            Object[] values = new Object[row.size() + 1];
            values[0] = index++;
            for (int i = 0; i < row.size(); i++) {
                values[i + 1] = row.get(i).excelValue();
            }
            sheet.row(values);
        }
        if (table.isEmpty()) {
            sheet.row(msg("report.empty", locale));
        }
        sheet.finish();
    }

    private String[] headerLabels(DynamicTable table, Locale locale) {
        List<String> labels = new ArrayList<>(table.columns().size() + 1);
        labels.add(msg("excel.col.index", locale));
        table.columns().forEach(column -> labels.add(column.label()));
        return labels.toArray(String[]::new);
    }

    private String msg(String key, Locale locale) {
        return messageSource.getMessage(key, null, locale);
    }
}
