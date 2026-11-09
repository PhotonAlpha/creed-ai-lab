package com.creed.resource.veracode.service;

import com.creed.resource.veracode.api.dto.AnalysisResponse;
import com.creed.resource.veracode.api.dto.FindingView;
import com.creed.resource.veracode.domain.SastFlaw;
import com.creed.resource.veracode.domain.SastIssue;
import com.creed.resource.veracode.domain.Severity;
import com.creed.resource.veracode.service.JiraLookupService.IssueRef;
import org.apache.poi.common.usermodel.HyperlinkType;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.CellStyle;
import org.apache.poi.ss.usermodel.CreationHelper;
import org.apache.poi.ss.usermodel.FillPatternType;
import org.apache.poi.ss.usermodel.Font;
import org.apache.poi.ss.usermodel.Hyperlink;
import org.apache.poi.ss.usermodel.IndexedColors;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.util.CellRangeAddress;
import org.apache.poi.xssf.usermodel.XSSFCellStyle;
import org.apache.poi.xssf.usermodel.XSSFColor;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.springframework.stereotype.Component;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * One workbook per analysis: sheet "Findings" (one row per finding, the Jira columns last), sheet "SCA summary"
 * (one row per report: {@link ScaSummary}, the page's SCA statistics table), sheets "SAST flaws" (every static
 * flaw, the most severe first) and "SAST summary" ({@link SastSummary}), and sheet
 * "Source" (Veracode app, scan, policy, file, project, time, warnings) so a forwarded file still says what it was computed from.
 *
 * <p>A finding with several issues gets all their keys in one cell; the hyperlink can only point at one,
 * so it points at the first.
 */
@Component
public class ExcelExporter {

    /** The page's columns: Title = {@code cve_id|cwe_id}, Description = the full {@code cve_summary}. */
    private static final String[] HEADERS = {"Status", "Veracode application", "Title", "Library file",
            "Component(s)", "Severity", "CVSS", "Affected version", "Jira issue(s)", "Jira status",
            "Location", "Description"};
    static final int SEVERITY_COLUMN = 5;
    private static final int[] WIDTHS = {12, 28, 30, 30, 28, 12, 8, 16, 22, 16, 40, 80};
    /**
     * Severity cell fill by {@link Severity#rank} (Informational 0 ... Very High 5): the hues of the page's severity
     * tags (blue, gold, orange, volcano, red), at a lightness that keeps black text readable and survives printing.
     * Unrecognised severities stay unfilled.
     */
    private static final String[] SEVERITY_FILL = {"F0F0F0", "BAE0FF", "FFE58F", "FFD591", "FFBB96", "FFA39E"};
    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss 'UTC'")
            .withZone(ZoneOffset.UTC);

    public byte[] export(AnalysisResponse analysis) {
        try (XSSFWorkbook wb = new XSSFWorkbook(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            CreationHelper helper = wb.getCreationHelper();
            CellStyle header = headerStyle(wb);
            CellStyle link = linkStyle(wb);
            CellStyle wrap = wb.createCellStyle();
            wrap.setWrapText(true);
            CellStyle[] severity = severityStyles(wb);

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
                row.createCell(c++).setCellValue(f.status().name() + (f.needsLabel() ? " (label missing)" : ""));
                row.createCell(c++).setCellValue(nullToEmpty(f.application()));
                Cell title = row.createCell(c++);
                title.setCellValue(f.title());
                if (f.cve() != null) {
                    title.setHyperlink(url(helper, "https://nvd.nist.gov/vuln/detail/" + f.cve()));
                    title.setCellStyle(link);
                }
                row.createCell(c++).setCellValue(nullToEmpty(f.fileName()));
                row.createCell(c++).setCellValue(String.join(", ", f.components()));
                Cell level = row.createCell(c++);
                level.setCellValue(nullToEmpty(f.severity()));
                int rank = Severity.rank(f.severity());
                if (rank >= 0) {
                    level.setCellStyle(severity[rank]);
                }
                Cell cvss = row.createCell(c++);
                if (f.cvss() != null) {
                    cvss.setCellValue(f.cvss());
                }
                row.createCell(c++).setCellValue(nullToEmpty(f.affectedVersion()));
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

            scaSummary(wb.createSheet("SCA summary"), analysis.reports(), header, severity);
            sastFlaws(wb.createSheet("SAST flaws"), analysis, helper, header, link, wrap, severity);
            sastSummary(wb.createSheet("SAST summary"), analysis.reports(), header, severity);
            source(wb.createSheet("Source"), analysis, header);
            wb.write(out);
            return out.toByteArray();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /**
     * One row per report, the severity columns headed in their level's fill, and a Total row when there are
     * several. A count the report does not state stays empty - not 0, which would claim SCA found nothing.
     */
    private static void scaSummary(Sheet sheet, List<ReportView> reports, CellStyle header, CellStyle[] severity) {
        List<String> headers = new ArrayList<>(List.of("Uploaded file", "Veracode application",
                "Third-party components", "Vulnerable components", "Violates policy", "Libraries violating policy",
                "Blacklisted components", "Vulnerabilities"));
        int firstLevel = headers.size();
        headers.addAll(ScaSummary.LEVELS);
        headers.addAll(List.of("Unknown severity", "Max CVSS", "Mitigated (skipped)"));
        Row head = sheet.createRow(0);
        for (int i = 0; i < headers.size(); i++) {
            Cell c = head.createCell(i);
            c.setCellValue(headers.get(i));
            int level = i - firstLevel;
            c.setCellStyle(level >= 0 && level < ScaSummary.LEVELS.size()
                    ? severity[ScaSummary.LEVELS.size() - 1 - level] : header);
            sheet.setColumnWidth(i, (i < 2 ? 30 : 14) * 256);
        }
        int r = 1;
        long[] total = new long[headers.size()];
        Double maxCvss = null;
        for (ReportView report : reports) {
            ScaSummary sca = report.sca();
            Row row = sheet.createRow(r++);
            row.createCell(0).setCellValue(report.fileName());
            row.createCell(1).setCellValue(nullToEmpty(report.appName()));
            if (sca == null) {
                continue;
            }
            List<Object> values = new ArrayList<>(List.of(nullable(sca.thirdPartyComponents()),
                    sca.vulnerableComponents(),
                    sca.violatesPolicy() == null ? "" : sca.violatesPolicy() ? "Yes" : "No",
                    nullable(sca.componentsViolatedPolicy()), nullable(sca.blacklistedComponents()),
                    sca.vulnerabilities()));
            sca.bySeverity().forEach(level -> values.add(level.count()));
            values.add(sca.unknownSeverity());
            values.add(sca.maxCvss() == null ? "" : sca.maxCvss());
            values.add(sca.mitigated());
            for (int i = 0; i < values.size(); i++) {
                Cell cell = row.createCell(i + 2);
                Object value = values.get(i);
                if (value instanceof Integer n) {
                    cell.setCellValue(n);
                    total[i + 2] += n;
                } else if (value instanceof Double d) {
                    cell.setCellValue(d);
                } else {
                    cell.setCellValue(value.toString());
                }
            }
            if (sca.maxCvss() != null && (maxCvss == null || sca.maxCvss() > maxCvss)) {
                maxCvss = sca.maxCvss();
            }
        }
        if (reports.size() > 1) {
            Row row = sheet.createRow(r);
            Cell label = row.createCell(0);
            label.setCellValue("Total");
            label.setCellStyle(header);
            int maxCvssColumn = headers.indexOf("Max CVSS");
            for (int i = 2; i < headers.size(); i++) {
                // a yes/no column has no sum; the highest CVSS is the highest of the reports'
                if (i == headers.indexOf("Violates policy")) {
                    continue;
                }
                Cell cell = row.createCell(i);
                if (i == maxCvssColumn) {
                    if (maxCvss != null) {
                        cell.setCellValue(maxCvss);
                    }
                } else {
                    cell.setCellValue(total[i]);
                }
                cell.setCellStyle(header);
            }
        }
        sheet.createFreezePane(2, 1);
    }

    private static final String[] SAST_HEADERS = {"Status", "Veracode application", "Issue ID", "Severity", "CWE",
            "CWE name", "Category", "Module", "Source file", "Line", "Remediation status", "Mitigation status",
            "Affects policy", "First found", "Jira issue(s)", "Jira status", "Description"};
    private static final int[] SAST_WIDTHS = {12, 28, 10, 12, 10, 40, 30, 24, 44, 8, 16, 20, 12, 22, 22, 16, 80};
    static final int SAST_SEVERITY_COLUMN = 3;

    /** The Jira columns are the flaw's SAST issue's (CWE + category); empty for a fixed or mitigated flaw. */
    private static void sastFlaws(Sheet sheet, AnalysisResponse analysis, CreationHelper helper, CellStyle header,
                                  CellStyle link, CellStyle wrap, CellStyle[] severity) {
        List<SastFlaw> flaws = analysis.sastFlaws();
        java.util.Map<String, List<IssueRef>> jira = new java.util.HashMap<>();
        if (analysis.sast() != null) {
            analysis.sast().issues().forEach(i -> jira.put(i.id(), i.issues()));
        }
        Row head = sheet.createRow(0);
        for (int i = 0; i < SAST_HEADERS.length; i++) {
            Cell c = head.createCell(i);
            c.setCellValue(SAST_HEADERS[i]);
            c.setCellStyle(header);
            sheet.setColumnWidth(i, SAST_WIDTHS[i] * 256);
        }
        // the page's default order: the most severe first, then by file and line
        List<SastFlaw> sorted = flaws.stream()
                .sorted(Comparator.comparingInt((SastFlaw f) -> -Severity.rank(f.severity()))
                        .thenComparing(f -> nullToEmpty(f.sourceFile()))
                        .thenComparing(f -> f.line() == null ? 0 : f.line()))
                .toList();
        int r = 1;
        for (SastFlaw f : sorted) {
            Row row = sheet.createRow(r++);
            int c = 0;
            row.createCell(c++).setCellValue(f.fixed() ? "Fixed" : f.mitigated() ? "Mitigated" : "Open");
            row.createCell(c++).setCellValue(nullToEmpty(f.application()));
            row.createCell(c++).setCellValue(nullToEmpty(f.issueId()));
            Cell level = row.createCell(c++);
            level.setCellValue(nullToEmpty(f.severity()));
            int rank = Severity.rank(f.severity());
            if (rank >= 0) {
                level.setCellStyle(severity[rank]);
            }
            Cell cwe = row.createCell(c++);
            cwe.setCellValue(nullToEmpty(f.cwe()));
            if (f.cwe() != null) {
                cwe.setHyperlink(url(helper, "https://cwe.mitre.org/data/definitions/"
                        + f.cwe().substring("CWE-".length()) + ".html"));
                cwe.setCellStyle(link);
            }
            row.createCell(c++).setCellValue(nullToEmpty(f.cweName()));
            row.createCell(c++).setCellValue(nullToEmpty(f.category()));
            row.createCell(c++).setCellValue(nullToEmpty(f.module()));
            row.createCell(c++).setCellValue(nullToEmpty(f.sourceFile()));
            Cell line = row.createCell(c++);
            if (f.line() != null) {
                line.setCellValue(f.line());
            }
            row.createCell(c++).setCellValue(nullToEmpty(f.remediationStatus()));
            row.createCell(c++).setCellValue(nullToEmpty(f.mitigationStatus()));
            row.createCell(c++).setCellValue(f.affectsPolicy() == null ? "" : f.affectsPolicy() ? "Yes" : "No");
            row.createCell(c++).setCellValue(nullToEmpty(f.firstFound()));
            String issueId = SastIssue.idOf(f);
            List<IssueRef> issues = issueId == null ? List.of() : jira.getOrDefault(issueId, List.of());
            Cell keys = row.createCell(c++);
            keys.setCellValue(join(issues, IssueRef::key));
            if (!issues.isEmpty() && issues.getFirst().url() != null) {
                keys.setHyperlink(url(helper, issues.getFirst().url()));
                keys.setCellStyle(link);
            }
            row.createCell(c++).setCellValue(join(issues, i -> nullToEmpty(i.status())));
            Cell description = row.createCell(c);
            description.setCellValue(nullToEmpty(f.description()));
            description.setCellStyle(wrap);
        }
        sheet.createFreezePane(0, 1);
        sheet.setAutoFilter(new CellRangeAddress(0, Math.max(0, r - 1), 0, SAST_HEADERS.length - 1));
    }

    /**
     * One row per report and a Total row with several, like {@link #scaSummary}: the static analysis' rating and
     * score, the report's stated flaw counts, then the counted ones - the severity columns count open flaws.
     */
    private static void sastSummary(Sheet sheet, List<ReportView> reports, CellStyle header, CellStyle[] severity) {
        List<String> headers = new ArrayList<>(List.of("Uploaded file", "Veracode application", "Rating", "Score",
                "Total flaws (stated)", "Not mitigated (stated)", "Flaws listed", "Open", "Mitigated", "Fixed",
                "Open affecting policy"));
        int firstLevel = headers.size();
        headers.addAll(ScaSummary.LEVELS);
        headers.add("Unknown severity");
        Row head = sheet.createRow(0);
        for (int i = 0; i < headers.size(); i++) {
            Cell c = head.createCell(i);
            c.setCellValue(headers.get(i));
            int level = i - firstLevel;
            c.setCellStyle(level >= 0 && level < ScaSummary.LEVELS.size()
                    ? severity[ScaSummary.LEVELS.size() - 1 - level] : header);
            sheet.setColumnWidth(i, (i < 2 ? 30 : 14) * 256);
        }
        int r = 1;
        long[] total = new long[headers.size()];
        for (ReportView report : reports) {
            SastSummary sast = report.sast();
            Row row = sheet.createRow(r++);
            row.createCell(0).setCellValue(report.fileName());
            row.createCell(1).setCellValue(nullToEmpty(report.appName()));
            if (sast == null) {
                continue;
            }
            row.createCell(2).setCellValue(nullToEmpty(sast.rating()));
            List<Object> values = new ArrayList<>(List.of(nullable(sast.score()), nullable(sast.totalFlaws()),
                    nullable(sast.flawsNotMitigated()), sast.flaws(), sast.open(), sast.mitigated(), sast.fixed(),
                    sast.affectsPolicy()));
            sast.bySeverity().forEach(level -> values.add(level.count()));
            values.add(sast.unknownSeverity());
            for (int i = 0; i < values.size(); i++) {
                Cell cell = row.createCell(i + 3);
                if (values.get(i) instanceof Integer n) {
                    cell.setCellValue(n);
                    total[i + 3] += n;
                } else {
                    cell.setCellValue(values.get(i).toString());
                }
            }
        }
        if (reports.size() > 1) {
            Row row = sheet.createRow(r);
            Cell label = row.createCell(0);
            label.setCellValue("Total");
            label.setCellStyle(header);
            // rating and score are per scan: no sum
            for (int i = 4; i < headers.size(); i++) {
                Cell cell = row.createCell(i);
                cell.setCellValue(total[i]);
                cell.setCellStyle(header);
            }
        }
        sheet.createFreezePane(2, 1);
    }

    /** {@code null} as an empty cell rather than 0. */
    private static Object nullable(Integer n) {
        return n == null ? "" : n;
    }

    private static void source(Sheet sheet, AnalysisResponse a, CellStyle header) {
        AnalysisResponse.Counts n = a.counts();
        List<Object[]> rows = new ArrayList<>();
        for (ReportView report : a.reports()) {
            // one block per uploaded report
            rows.add(new Object[] {"Uploaded file", report.fileName()});
            rows.add(new Object[] {"Veracode application", nullToEmpty(report.appName())});
            rows.add(new Object[] {"Scan version", nullToEmpty(report.scanVersion())});
            rows.add(new Object[] {"Affected version", nullToEmpty(report.affectedVersion())
                    + (report.versionOverridden() ? " (set on the page)" : "")});
            rows.add(new Object[] {"Policy", nullToEmpty(report.policyName())
                    + (report.policyCompliance() == null ? "" : " - " + report.policyCompliance())});
            rows.add(new Object[] {"Report generated", nullToEmpty(report.generatedAt())});
        }
        rows.addAll(List.<Object[]>of(
                new Object[] {"Jira project", a.projectKey()},
                new Object[] {"Jira versions searched", a.selectedVersions().isEmpty() ? "(any)"
                        : String.join(", ", a.selectedVersions())},
                new Object[] {"Analysed at", TIME.format(a.analyzedAt())},
                new Object[] {"Findings", n.total() + " (exists " + n.exists() + ", missing " + n.missing()
                        + ", label missing " + n.needsLabel() + ")"}));
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

    /** One style per level - a workbook holds at most 64000 styles, so never one per cell. */
    private static CellStyle[] severityStyles(XSSFWorkbook wb) {
        Font bold = wb.createFont();
        bold.setBold(true);
        CellStyle[] styles = new CellStyle[SEVERITY_FILL.length];
        for (int rank = 0; rank < styles.length; rank++) {
            XSSFCellStyle style = wb.createCellStyle();
            style.setFillForegroundColor(new XSSFColor(java.util.HexFormat.of().parseHex(SEVERITY_FILL[rank])));
            style.setFillPattern(FillPatternType.SOLID_FOREGROUND);
            if (rank >= 4) {
                style.setFont(bold);
            }
            styles[rank] = style;
        }
        return styles;
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
