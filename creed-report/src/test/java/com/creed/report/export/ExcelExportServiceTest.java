package com.creed.report.export;

import com.creed.report.config.MessageSourceConfig;
import com.creed.report.dynamic.DynamicTableProperties;
import com.creed.report.dynamic.DynamicTableService;
import com.creed.report.service.EnvironmentInspectionService;
import com.creed.report.service.ServerInfoService;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.ss.usermodel.WorkbookFactory;
import org.junit.jupiter.api.Test;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.context.MessageSource;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Exercises the export strategies end to end without a Spring context: the real
 * {@link MessageSourceConfig} bundle chain, the real data services, POI writing a workbook, and
 * POI reading it back — so a broken message key, a missing sheet or an unreadable file fails here
 * rather than in the browser.
 */
class ExcelExportServiceTest {

    private final MessageSource messages = new MessageSourceConfig().messageSource();
    private final DynamicTableService tables =
            new DynamicTableService(new ObjectMapper(), messages, new DynamicTableProperties());
    private final ExcelExportService service = new ExcelExportService(List.of(
            new ServerInventoryExcelExporter(new ServerInfoService(), messages),
            new EnvironmentExcelExporter(new EnvironmentInspectionService(), messages),
            new DynamicTableExcelExporter(tables, messages)));

    @Test
    void serverInventoryStrategyWritesTheServerTable() throws IOException {
        byte[] xlsx = service.export(request(ReportType.SERVER_INVENTORY, Locale.ENGLISH, Map.of()));

        try (Workbook workbook = read(xlsx)) {
            Sheet sheet = workbook.getSheet("Servers");
            assertThat(sheet).isNotNull();
            // Title, caption, spacer, header, then one row per server.
            assertThat(values(sheet.getRow(3)))
                    .containsExactly("#", "Host", "IP", "App", "Country", "Service", "Env", "Zone", "Slot");
            assertThat(values(sheet.getRow(4)))
                    .containsExactly("1.0", "creed-auth-01", "10.10.1.11", "creed-author-server",
                            "CN", "auth", "prod", "cn-east-1a", "blue");
            assertThat(sheet.getLastRowNum() - 3)
                    .isEqualTo(new ServerInfoService().listServers().size());
        }
    }

    @Test
    void reportTypeSelectsTheStrategy() throws IOException {
        // Same call, different type -> a completely different workbook, chosen by dispatch alone.
        byte[] xlsx = service.export(request(ReportType.ENVIRONMENT, Locale.ENGLISH, Map.of(
                "spring.profiles.active", "test",
                "spring.config.location", "optional:classpath:/",
                "spring.config.additional-location", "optional:classpath:/")));

        try (Workbook workbook = read(xlsx)) {
            assertThat(sheetNames(workbook))
                    .containsExactly("Summary", "Effective Properties", "Property Sources", "All Properties");
            // The summary echoes the inspection parameters it ran with.
            assertThat(cellsOf(workbook.getSheet("Summary")))
                    .contains("Active profiles", "test", "optional:classpath:/");
            // application.yml of this module is on the classpath, so the inspection found properties.
            assertThat(workbook.getSheet("Effective Properties").getLastRowNum()).isPositive();
        }
    }

    @Test
    void sheetNamesAndHeadersFollowTheRequestLocale() throws IOException {
        byte[] xlsx = service.export(request(ReportType.SERVER_INVENTORY, Locale.SIMPLIFIED_CHINESE, Map.of()));

        try (Workbook workbook = read(xlsx)) {
            Sheet sheet = workbook.getSheet("服务器");
            assertThat(sheet).isNotNull();
            assertThat(values(sheet.getRow(3))).contains("主机", "应用", "部署槽");
        }
    }

    @Test
    void unknownLocaleFallsBackToTheEnglishBundle() throws IOException {
        byte[] xlsx = service.export(request(ReportType.SERVER_INVENTORY, Locale.forLanguageTag("fr"), Map.of()));

        try (Workbook workbook = read(xlsx)) {
            assertThat(workbook.getSheet("Servers")).isNotNull();
        }
    }

    @Test
    void dynamicStrategyWritesTheCallerDefinedTable() throws IOException {
        // headers/data ride in on the pass-through parameters map -- no new plumbing for a report
        // type whose shape is not known until the request arrives.
        byte[] xlsx = service.export(request(ReportType.DYNAMIC, Locale.ENGLISH, Map.of(
                "title", "Ad hoc",
                "headers", "host,ip,uptimeDays",
                "data", "[{\"host\":\"a\",\"ip\":\"10.0.0.1\",\"uptimeDays\":1234}]")));

        try (Workbook workbook = read(xlsx)) {
            Sheet sheet = workbook.getSheet("Data");
            assertThat(sheet).isNotNull();
            assertThat(sheet.getRow(0).getCell(0).getStringCellValue()).isEqualTo("Ad hoc");
            assertThat(values(sheet.getRow(3))).containsExactly("#", "Host", "IP", "uptimeDays");
            // The number stays numeric so the sheet can still sum the column.
            assertThat(values(sheet.getRow(4))).containsExactly("1.0", "a", "10.0.0.1", "1234.0");
        }
    }

    @Test
    void dynamicStrategyLocalizesHeadersAndFormatsForTheCountry() throws IOException {
        byte[] xlsx = service.export(request(ReportType.DYNAMIC, Locale.of("vi", "VN"), Map.of(
                "headers", "host,ip",
                "data", "[{\"host\":\"a\",\"ip\":\"10.0.0.1\"}]")));

        try (Workbook workbook = read(xlsx)) {
            Sheet sheet = workbook.getSheet("Dữ liệu");
            assertThat(sheet).isNotNull();
            // Both the index column and the data columns come out of the Vietnamese bundle.
            assertThat(values(sheet.getRow(3))).containsExactly("STT", "Máy chủ", "IP");
        }
    }

    @Test
    void everyReportTypeHasAStrategy() {
        assertThat(service.supportedTypes()).containsExactly(ReportType.values());
    }

    @Test
    void onlyTypesThatWorkFromABareLinkAreOfferedAsLinks() {
        // The report page's dropdown is model-driven, so a type whose data must be posted with the
        // request has to be excluded there or the menu offers a guaranteed 400.
        assertThat(service.linkableTypes())
                .containsExactly(ReportType.SERVER_INVENTORY, ReportType.ENVIRONMENT)
                .doesNotContain(ReportType.DYNAMIC);
    }

    @Test
    void twoStrategiesForOneTypeFailFast() {
        ServerInfoService servers = new ServerInfoService();
        assertThatThrownBy(() -> new ExcelExportService(List.of(
                new ServerInventoryExcelExporter(servers, messages),
                new ServerInventoryExcelExporter(servers, messages))))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("SERVER_INVENTORY");
    }

    @Test
    void unknownTypeCodeIsRejectedAsBadInput() {
        assertThat(ReportType.of("SERVER ")).isEqualTo(ReportType.SERVER_INVENTORY);
        assertThatThrownBy(() -> ReportType.of("spreadsheet"))
                .isInstanceOf(UnknownReportTypeException.class)
                .hasMessageContaining("server")
                .hasMessageContaining("environment");
    }

    @Test
    void filenameCarriesTheTypePrefixAndTimestamp() {
        ExcelExportRequest request = new ExcelExportRequest(ReportType.ENVIRONMENT, Locale.ENGLISH, Map.of(),
                LocalDateTime.of(2026, 7, 26, 10, 15, 0));

        assertThat(service.filename(request)).isEqualTo("creed-environment-report-20260726-101500.xlsx");
    }

    private static ExcelExportRequest request(ReportType type, Locale locale, Map<String, String> parameters) {
        return new ExcelExportRequest(type, locale, parameters, LocalDateTime.of(2026, 7, 26, 10, 15, 0));
    }

    private static Workbook read(byte[] xlsx) throws IOException {
        return WorkbookFactory.create(new ByteArrayInputStream(xlsx));
    }

    private static List<String> sheetNames(Workbook workbook) {
        List<String> names = new ArrayList<>();
        for (int i = 0; i < workbook.getNumberOfSheets(); i++) {
            names.add(workbook.getSheetName(i));
        }
        return names;
    }

    /** Cell values of one row, as displayed text (numbers included, hence the trailing ".0"). */
    private static List<String> values(Row row) {
        List<String> values = new ArrayList<>();
        row.forEach(cell -> values.add(cell.toString()));
        return values;
    }

    private static List<String> cellsOf(Sheet sheet) {
        List<String> values = new ArrayList<>();
        sheet.forEach(row -> values.addAll(values(row)));
        return values;
    }
}
