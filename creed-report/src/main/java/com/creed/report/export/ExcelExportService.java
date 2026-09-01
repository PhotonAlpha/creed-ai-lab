package com.creed.report.export;

import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.time.format.DateTimeFormatter;
import java.util.Collections;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

/**
 * The context of the strategy pattern behind {@code /export/excel}: it owns the workbook
 * lifecycle and the {@link ReportType} → {@link ExcelReportExporter} dispatch, while each strategy
 * owns only its own data and sheet layout.
 *
 * <p>Spring injects every {@link ExcelReportExporter} bean on the classpath, so a new report type
 * is one enum constant plus one bean — nothing here changes. A duplicate registration fails at
 * startup rather than silently letting one strategy win.
 *
 * <p>Workbooks are {@link XSSFWorkbook} (in-memory .xlsx). That is the right trade-off for these
 * reports — thousands of rows, plus styling and autosized columns, which the streaming SXSSF
 * variant only supports for rows still inside its sliding window. A workbook is created per call
 * and closed here; POI workbooks are not thread-safe, so none is ever shared.
 */
@Service
public class ExcelExportService {

    /** OOXML spreadsheet (.xlsx) media type; POI writes this format. */
    public static final String CONTENT_TYPE =
            "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet";

    /** Timestamp fragment of the download filename. */
    private static final DateTimeFormatter FILE_TS = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss");

    private static final Logger log = LoggerFactory.getLogger(ExcelExportService.class);

    private final Map<ReportType, ExcelReportExporter> strategies;

    public ExcelExportService(List<ExcelReportExporter> exporters) {
        Map<ReportType, ExcelReportExporter> byType = new EnumMap<>(ReportType.class);
        for (ExcelReportExporter exporter : exporters) {
            ExcelReportExporter previous = byType.put(exporter.reportType(), exporter);
            if (previous != null) {
                throw new IllegalStateException("Two Excel exporters claim report type "
                        + exporter.reportType() + ": " + previous.getClass().getName()
                        + " and " + exporter.getClass().getName());
            }
        }
        this.strategies = Collections.unmodifiableMap(byType);
        log.info("Excel export strategies registered: {}", byType.keySet());
    }

    /** Every report type an exporter is registered for, in enum order. */
    public List<ReportType> supportedTypes() {
        return List.copyOf(strategies.keySet());
    }

    /**
     * The subset a menu can offer as a plain link — see {@link ReportType#linkable()}. A type whose
     * data has to travel with the request is reachable only from the page that holds that data.
     */
    public List<ReportType> linkableTypes() {
        return strategies.keySet().stream().filter(ReportType::linkable).toList();
    }

    /**
     * Runs the strategy for {@code request}'s type and returns the finished .xlsx bytes.
     *
     * @throws UnknownReportTypeException if no strategy handles the type (mapped to 400)
     */
    public byte[] export(ExcelExportRequest request) {
        ExcelReportExporter exporter = strategies.get(request.type());
        if (exporter == null) {
            throw new UnknownReportTypeException(request.type().code());
        }
        try (Workbook workbook = new XSSFWorkbook();
             ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            exporter.write(workbook, request);
            workbook.write(out);
            return out.toByteArray();
        }
        catch (IOException ex) {
            throw new IllegalStateException("Excel export failed for report type " + request.type(), ex);
        }
    }

    /** Download filename for an export, e.g. {@code creed-server-report-20260726-101500.xlsx}. */
    public String filename(ExcelExportRequest request) {
        return request.type().filePrefix() + "-" + request.generatedAt().format(FILE_TS) + ".xlsx";
    }
}
