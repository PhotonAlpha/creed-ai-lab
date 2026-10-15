package com.creed.resource.veracode.service;

import com.creed.resource.veracode.api.dto.AnalysisResponse;
import com.creed.resource.veracode.api.dto.FindingView;
import com.creed.resource.veracode.service.JiraLookupService.IssueRef;
import org.apache.poi.common.usermodel.HyperlinkType;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.CellStyle;
import org.apache.poi.ss.usermodel.CreationHelper;
import org.apache.poi.ss.usermodel.Font;
import org.apache.poi.ss.usermodel.Hyperlink;
import org.apache.poi.ss.usermodel.IndexedColors;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.util.CellRangeAddress;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.springframework.stereotype.Component;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * One workbook per analysis: sheet "Findings" (one row per finding, the Jira columns last) and sheet
 * "Source" (page, project, time, warnings) so a forwarded file still says what it was computed from.
 *
 * <p>A finding with several issues gets all their keys in one cell; the hyperlink can only point at one,
 * so it points at the first.
 */
@Component
public class ExcelExporter {

    private static final String[] HEADERS = {"Status", "CVE", "Title", "Component(s)", "Severity", "CVSS",
            "Affected version", "Jira version", "Fixed version", "Jira issue(s)", "Jira status", "Report location",
            "Description"};
    private static final int[] WIDTHS = {12, 18, 48, 36, 12, 8, 16, 14, 16, 22, 16, 20, 60};
    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss 'UTC'")
            .withZone(ZoneOffset.UTC);

    public byte[] export(AnalysisResponse analysis) {
        try (XSSFWorkbook wb = new XSSFWorkbook(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            CreationHelper helper = wb.getCreationHelper();
            CellStyle header = headerStyle(wb);
            CellStyle link = linkStyle(wb);
            CellStyle wrap = wb.createCellStyle();
            wrap.setWrapText(true);

            Sheet sheet = wb.createSheet("Findings");
            Row head = sheet.createRow(0);
            for (int i = 0; i < HEADERS.length; i++) {
                Cell c = head.createCell(i);
                c.setCellValue(HEADERS[i]);
                c.setCellStyle(header);
                sheet.setColumnWidth(i, WIDTHS[i] * 256);
            }
            int r = 1;
            for (FindingView f : analysis.findings()) {
                Row row = sheet.createRow(r++);
                int c = 0;
                row.createCell(c++).setCellValue(f.status().name());
                Cell cve = row.createCell(c++);
                if (f.cve() != null) {
                    cve.setCellValue(f.cve());
                    cve.setHyperlink(url(helper, "https://nvd.nist.gov/vuln/detail/" + f.cve()));
                    cve.setCellStyle(link);
                }
                row.createCell(c++).setCellValue(nullToEmpty(f.title()));
                row.createCell(c++).setCellValue(String.join(", ", f.components()));
                row.createCell(c++).setCellValue(nullToEmpty(f.severity()));
                Cell cvss = row.createCell(c++);
                if (f.cvss() != null) {
                    cvss.setCellValue(f.cvss());
                }
                row.createCell(c++).setCellValue(f.affectedVersion());
                row.createCell(c++).setCellValue(f.jiraVersion() == null ? "(not in Jira)" : f.jiraVersion());
                row.createCell(c++).setCellValue(nullToEmpty(f.fixedVersion()));
                Cell keys = row.createCell(c++);
                keys.setCellValue(join(f.issues(), IssueRef::key));
                if (!f.issues().isEmpty() && f.issues().getFirst().url() != null) {
                    keys.setHyperlink(url(helper, f.issues().getFirst().url()));
                    keys.setCellStyle(link);
                }
                row.createCell(c++).setCellValue(join(f.issues(), i -> nullToEmpty(i.status())));
                row.createCell(c++).setCellValue(String.join("; ", f.locations()));
                Cell description = row.createCell(c);
                description.setCellValue(nullToEmpty(f.description()));
                description.setCellStyle(wrap);
            }
            sheet.createFreezePane(0, 1);
            sheet.setAutoFilter(new CellRangeAddress(0, Math.max(0, r - 1), 0, HEADERS.length - 1));

            source(wb.createSheet("Source"), analysis, header);
            wb.write(out);
            return out.toByteArray();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static void source(Sheet sheet, AnalysisResponse a, CellStyle header) {
        AnalysisResponse.Counts n = a.counts();
        Object[][] rows = {
                a.page().uploaded()
                        ? new Object[]{"Uploaded file", a.page().title()}
                        : new Object[]{"Confluence page", a.page().title() + " (id " + a.page().id()
                                + (a.page().version() == null ? "" : ", version " + a.page().version()) + ")"},
                {"Page URL", nullToEmpty(a.page().url())},
                {"Jira project", a.projectKey()},
                {"Analysed at", TIME.format(a.analyzedAt())},
                {"Findings", n.total() + " (exists " + n.exists() + ", missing " + n.missing()
                        + ", version not in Jira " + n.versionNotInJira() + ")"},
        };
        int r = 0;
        for (Object[] values : rows) {
            Row row = sheet.createRow(r++);
            Cell k = row.createCell(0);
            k.setCellValue(values[0].toString());
            k.setCellStyle(header);
            row.createCell(1).setCellValue(values[1].toString());
        }
        r++;
        for (String warning : a.warnings()) {
            sheet.createRow(r++).createCell(1).setCellValue("warning: " + warning);
        }
        sheet.setColumnWidth(0, 20 * 256);
        sheet.setColumnWidth(1, 100 * 256);
    }

    private static Hyperlink url(CreationHelper helper, String address) {
        Hyperlink link = helper.createHyperlink(HyperlinkType.URL);
        link.setAddress(address);
        return link;
    }

    private static CellStyle headerStyle(XSSFWorkbook wb) {
        Font bold = wb.createFont();
        bold.setBold(true);
        CellStyle style = wb.createCellStyle();
        style.setFont(bold);
        return style;
    }

    private static CellStyle linkStyle(XSSFWorkbook wb) {
        Font font = wb.createFont();
        font.setUnderline(Font.U_SINGLE);
        font.setColor(IndexedColors.BLUE.getIndex());
        CellStyle style = wb.createCellStyle();
        style.setFont(font);
        return style;
    }

    private static String join(List<IssueRef> issues, Function<IssueRef, String> field) {
        return issues.stream().map(field).collect(Collectors.joining(", "));
    }

    private static String nullToEmpty(String s) {
        return s == null ? "" : s;
    }
}
