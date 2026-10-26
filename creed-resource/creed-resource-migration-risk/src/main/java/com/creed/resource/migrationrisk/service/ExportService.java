package com.creed.resource.migrationrisk.service;

import com.creed.resource.migrationrisk.api.dto.AnalysisResponse;
import com.creed.resource.migrationrisk.api.dto.AnalysisResponse.Source;
import com.creed.resource.migrationrisk.domain.Location;
import com.creed.resource.migrationrisk.domain.RiskItem;
import org.apache.poi.ss.usermodel.CellStyle;
import org.apache.poi.ss.usermodel.Font;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.springframework.stereotype.Service;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.List;
import java.util.stream.Collectors;

/**
 * The analysis on screen as a sync checklist: one row / line per file with its findings, target in repo2 and
 * review. Works for unsaved analyses too — the page posts what it shows.
 */
@Service
public class ExportService {

    private static final String[] COLUMNS = {"Level", "Category", "File", "Module", "Test", "Change", "In repo2",
            "Review", "Note", "Jira", "Findings"};

    public byte[] xlsx(AnalysisResponse analysis) {
        try (XSSFWorkbook book = new XSSFWorkbook(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            Sheet sheet = book.createSheet("Sync checklist");
            CellStyle bold = book.createCellStyle();
            Font font = book.createFont();
            font.setBold(true);
            bold.setFont(font);
            CellStyle wrap = book.createCellStyle();
            wrap.setWrapText(true);
            Row header = sheet.createRow(0);
            for (int c = 0; c < COLUMNS.length; c++) {
                header.createCell(c).setCellValue(COLUMNS[c]);
                header.getCell(c).setCellStyle(bold);
            }
            int r = 1;
            for (RiskItem i : analysis.items()) {
                Row row = sheet.createRow(r++);
                String[] values = {i.level().name(), i.category().name(), i.element(), nz(i.module()),
                        i.test() ? "yes" : "", String.valueOf(i.change()), where(i.target()),
                        i.review() == null ? "" : i.review().status().name(),
                        i.review() == null ? "" : nz(i.review().note()),
                        i.review() == null ? "" : nz(i.review().jiraKey()), findings(i, "\n")};
                for (int c = 0; c < values.length; c++) {
                    row.createCell(c).setCellValue(values[c]);
                }
                row.getCell(values.length - 1).setCellStyle(wrap);
            }
            int[] widths = {8, 22, 60, 16, 6, 10, 50, 12, 30, 12, 90};
            for (int c = 0; c < widths.length; c++) {
                sheet.setColumnWidth(c, widths[c] * 256);
            }
            sheet.createFreezePane(0, 1);
            sheet.setAutoFilter(new org.apache.poi.ss.util.CellRangeAddress(0, Math.max(r - 1, 1), 0, COLUMNS.length - 1));
            book.write(out);
            return out.toByteArray();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** A Markdown checklist grouped by level: tick a file when it is synced. */
    public String markdown(AnalysisResponse analysis) {
        StringBuilder md = new StringBuilder();
        Source s = analysis.source();
        md.append("# Migration sync checklist").append(analysis.release() == null ? "" : " — " + analysis.release()).append("\n\n");
        md.append("- repo1: `").append(s.repo1Path()).append('`')
                .append(s.baseline() != null ? " baseline `" + s.baseline().shortId() + "` → HEAD `" + s.repo1Head().shortId() + "`" : "")
                .append("\n- repo2: `").append(s.repo2Path()).append('`')
                .append(s.repo2bPath() != null ? " + `" + s.repo2bPath() + "`" : "").append("\n")
                .append("- ").append(analysis.counts().total()).append(" files: ").append(analysis.counts().high())
                .append(" HIGH, ").append(analysis.counts().medium()).append(" MEDIUM, ").append(analysis.counts().low())
                .append(" LOW · analysed ").append(analysis.analyzedAt()).append("\n");
        for (var level : com.creed.resource.migrationrisk.domain.RiskLevel.values()) {
            List<RiskItem> items = analysis.items().stream().filter(i -> i.level() == level).toList();
            if (items.isEmpty()) {
                continue;
            }
            md.append("\n## ").append(level).append(" (").append(items.size()).append(")\n\n");
            for (RiskItem i : items) {
                boolean done = i.review() != null && i.review().status().name().equals("SYNCED");
                md.append("- [").append(done ? 'x' : ' ').append("] `").append(i.element()).append("` — ")
                        .append(i.category());
                if (i.target() != null) {
                    md.append(" → `").append(where(i.target())).append('`');
                }
                if (i.review() != null && !i.review().status().name().equals("PENDING")) {
                    md.append(" · **").append(i.review().status()).append("**");
                    if (i.review().note() != null) {
                        md.append(" ").append(i.review().note());
                    }
                }
                if (i.review() != null && i.review().jiraKey() != null) {
                    md.append(" · ").append(i.review().jiraKey());
                }
                md.append('\n');
                for (RiskItem p : parts(i)) {
                    md.append("  - ").append(p.category()).append(p.element().equals(i.element()) ? "" : " `" + p.element() + "`");
                    p.details().stream().limit(5).forEach(d -> md.append("\n    - `").append(d.replace("`", "'")).append('`'));
                    md.append('\n');
                }
            }
        }
        return md.toString();
    }

    private static List<RiskItem> parts(RiskItem i) {
        return i.parts().isEmpty() ? List.of(i) : i.parts();
    }

    private static String findings(RiskItem i, String separator) {
        return parts(i).stream()
                .map(p -> p.level() + " " + p.category() + (p.element().equals(i.element()) ? "" : " " + p.element())
                        + (p.details().isEmpty() ? "" : ": " + p.details().getFirst()))
                .collect(Collectors.joining(separator));
    }

    private static String where(Location l) {
        return l == null ? "" : (l.repo() == null ? "" : l.repo() + ": ") + l.path();
    }

    private static String nz(String s) {
        return s == null ? "" : s;
    }
}
