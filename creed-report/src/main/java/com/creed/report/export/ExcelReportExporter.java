package com.creed.report.export;

import org.apache.poi.ss.usermodel.Workbook;

/**
 * Strategy for turning one {@link ReportType} into Excel sheets.
 *
 * <p>Implementations are Spring beans; {@link ExcelExportService} collects them and dispatches on
 * {@link #reportType()}, so a new report format is a new bean and nothing else — no switch, no
 * registration call.
 *
 * <p>Contract: fetch your own data, add sheets to the given workbook, and return. The workbook is
 * created, written out and closed by {@link ExcelExportService} — an implementation must neither
 * write nor close it. One workbook is used by exactly one exporter on one thread, so it needs no
 * synchronization even though POI workbooks are not thread-safe.
 */
public interface ExcelReportExporter {

    /** The report type this strategy handles; must be unique across all beans. */
    ReportType reportType();

    /** Populates the workbook with this report's sheets. */
    void write(Workbook workbook, ExcelExportRequest request);
}
