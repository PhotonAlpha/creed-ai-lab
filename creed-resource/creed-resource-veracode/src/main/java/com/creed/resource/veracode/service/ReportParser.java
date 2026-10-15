package com.creed.resource.veracode.service;

import com.creed.resource.veracode.config.ReportProperties;
import com.creed.resource.veracode.domain.ReportRow;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.DataFormatter;
import org.apache.poi.ss.usermodel.FormulaEvaluator;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.ss.usermodel.WorkbookFactory;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.springframework.stereotype.Component;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.Charset;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Reads report rows out of a Confluence page's storage format, or out of an uploaded file
 * ({@code .html}/{@code .htm}, {@code .csv}, {@code .xlsx}/{@code .xls}).
 *
 * <p>Every format is first reduced to plain tables of cell text; the header and row rules below are the
 * same for all of them, so a report saved from Confluence as HTML, exported to Excel or to CSV reads
 * identically.
 *
 * <ul>
 *   <li>A table is a report table when one of its first {@value #HEADER_SCAN_ROWS} rows names an
 *       affected-version column and a CVE or title column. That row is the header; rows above it (a
 *       spreadsheet's title lines) are ignored. A report split into several tables or sheets reads as
 *       one report; other tables (a summary, a sign-off block) are skipped.</li>
 *   <li>HTML: rows are the table's own {@code tr}s only, so a table nested inside a cell (a macro, an
 *       expand) contributes nothing. The Confluence editor writes headers as {@code <th>} inside
 *       {@code <tbody>}, not {@code <thead>}.</li>
 *   <li>Merged cells ({@code rowspan}/{@code colspan}, merged spreadsheet regions) are not expanded: such
 *       a row reads shifted and usually fails the required-field check, which is reported.</li>
 * </ul>
 */
@Component
public class ReportParser {

    static final int HEADER_SCAN_ROWS = 10;
    private static final Pattern CVE = Pattern.compile("CVE-\\d{4}-\\d{4,}", Pattern.CASE_INSENSITIVE);
    private static final Pattern NUMBER = Pattern.compile("\\d+(?:\\.\\d+)?");

    enum Column { CVE, TITLE, COMPONENT, SEVERITY, CVSS, AFFECTED_VERSION, FIXED_VERSION, DESCRIPTION }

    public record ParsedReport(List<ReportRow> rows, List<String> warnings) {
    }

    /** A table reduced to cell text; {@code label} is how a warning names the row ("row 7"). */
    record Table(String name, List<TableRow> rows) {
    }

    record TableRow(String label, List<String> cells) {
    }

    private final Map<Column, List<String>> headers = new EnumMap<>(Column.class);

    public ReportParser(ReportProperties props) {
        headers.put(Column.CVE, normalised(props.cve()));
        headers.put(Column.TITLE, normalised(props.title()));
        headers.put(Column.COMPONENT, normalised(props.component()));
        headers.put(Column.SEVERITY, normalised(props.severity()));
        headers.put(Column.CVSS, normalised(props.cvss()));
        headers.put(Column.AFFECTED_VERSION, normalised(props.affectedVersion()));
        headers.put(Column.FIXED_VERSION, normalised(props.fixedVersion()));
        headers.put(Column.DESCRIPTION, normalised(props.description()));
    }

    /** A Confluence page body (storage format). */
    public ParsedReport parseStorage(String storage) {
        if (storage == null || storage.isBlank()) {
            throw new ReportFormatException("the page has no body");
        }
        return read(htmlTables(Jsoup.parseBodyFragment(storage)));
    }

    /**
     * An uploaded file. The type comes from the extension; a file without a known one is sniffed (zip or
     * OLE2 signature -> workbook, leading {@code <} -> HTML).
     */
    public ParsedReport parseFile(String filename, byte[] content) {
        if (content == null || content.length == 0) {
            throw new ReportFormatException("the file is empty");
        }
        return switch (typeOf(filename, content)) {
            case HTML -> read(htmlTables(parseHtml(content)));
            case CSV -> read(List.of(csvTable(content)));
            case WORKBOOK -> read(workbookTables(content));
        };
    }

    enum FileType { HTML, CSV, WORKBOOK }

    static FileType typeOf(String filename, byte[] content) {
        String name = filename == null ? "" : filename.toLowerCase(Locale.ROOT);
        if (name.endsWith(".html") || name.endsWith(".htm")) {
            return FileType.HTML;
        }
        if (name.endsWith(".csv")) {
            return FileType.CSV;
        }
        if (name.endsWith(".xlsx") || name.endsWith(".xls")) {
            return FileType.WORKBOOK;
        }
        if (startsWith(content, 0x50, 0x4B, 0x03, 0x04) || startsWith(content, 0xD0, 0xCF, 0x11, 0xE0)) {
            return FileType.WORKBOOK;
        }
        String head = new String(content, 0, Math.min(content.length, 64), StandardCharsets.UTF_8).stripLeading();
        if (head.startsWith("﻿")) {
            head = head.substring(1).stripLeading();
        }
        if (head.startsWith("<")) {
            return FileType.HTML;
        }
        throw new UnsupportedFileException(filename);
    }

    // ---- format -> tables ------------------------------------------------------------------------

    private static Document parseHtml(byte[] content) {
        try {
            // null charset: jsoup reads a BOM or <meta charset>, else UTF-8.
            return Jsoup.parse(new ByteArrayInputStream(content), null, "");
        } catch (IOException e) {
            throw new ReportFormatException("the HTML could not be read: " + e.getMessage());
        }
    }

    private static List<Table> htmlTables(Document doc) {
        List<Table> tables = new ArrayList<>();
        int tableNo = 0;
        for (Element table : doc.select("table")) {
            tableNo++;
            List<TableRow> rows = new ArrayList<>();
            List<Element> trs = ownRows(table);
            for (int r = 0; r < trs.size(); r++) {
                List<String> cells = trs.get(r).children().stream()
                        .filter(c -> c.nameIs("td") || c.nameIs("th"))
                        .map(ReportParser::text)
                        .toList();
                rows.add(new TableRow("row " + r, cells));
            }
            tables.add(new Table("table " + tableNo, rows));
        }
        return tables;
    }

    private static List<Element> ownRows(Element table) {
        List<Element> trs = new ArrayList<>();
        for (Element child : table.children()) {
            if (child.nameIs("tr")) {
                trs.add(child);
            } else if (child.nameIs("thead") || child.nameIs("tbody") || child.nameIs("tfoot")) {
                child.children().stream().filter(c -> c.nameIs("tr")).forEach(trs::add);
            }
        }
        return trs;
    }

    /** Spreadsheet row numbers as Excel shows them (1-based), so a warning can be found in the file. */
    private static List<Table> workbookTables(byte[] content) {
        try (Workbook wb = WorkbookFactory.create(new ByteArrayInputStream(content))) {
            DataFormatter formatter = new DataFormatter();
            FormulaEvaluator evaluator = wb.getCreationHelper().createFormulaEvaluator();
            List<Table> tables = new ArrayList<>();
            for (Sheet sheet : wb) {
                List<TableRow> rows = new ArrayList<>();
                for (Row row : sheet) {
                    List<String> cells = new ArrayList<>();
                    for (int c = 0; c < Math.max(0, row.getLastCellNum()); c++) {
                        Cell cell = row.getCell(c);
                        // DataFormatter gives the text the sheet shows: a version typed as 2.3 stays "2.3",
                        // not the double 2.3 or 2.2999999.
                        cells.add(cell == null ? "" : clean(formatter.formatCellValue(cell, evaluator)));
                    }
                    rows.add(new TableRow("row " + (row.getRowNum() + 1), cells));
                }
                tables.add(new Table("sheet '" + sheet.getSheetName() + "'", rows));
            }
            return tables;
        } catch (IOException | RuntimeException e) {
            // POI throws a zoo of unchecked exceptions for a corrupt or encrypted file.
            throw new ReportFormatException("the workbook could not be read: " + e.getMessage());
        }
    }

    /** RFC 4180: quoted fields may hold the delimiter, newlines and {@code ""}. Row label = record number. */
    static Table csvTable(byte[] content) {
        String text = decode(content);
        char delimiter = detectDelimiter(text);
        List<TableRow> rows = new ArrayList<>();
        List<String> cells = new ArrayList<>();
        StringBuilder field = new StringBuilder();
        boolean quoted = false;
        int record = 1;
        for (int i = 0; i < text.length(); i++) {
            char ch = text.charAt(i);
            if (quoted) {
                if (ch == '"' && i + 1 < text.length() && text.charAt(i + 1) == '"') {
                    field.append('"');
                    i++;
                } else if (ch == '"') {
                    quoted = false;
                } else {
                    field.append(ch);
                }
            } else if (ch == '"' && field.isEmpty()) {
                quoted = true;
            } else if (ch == delimiter) {
                cells.add(clean(field.toString()));
                field.setLength(0);
            } else if (ch == '\n' || ch == '\r') {
                if (ch == '\r' && i + 1 < text.length() && text.charAt(i + 1) == '\n') {
                    i++;
                }
                cells.add(clean(field.toString()));
                field.setLength(0);
                rows.add(new TableRow("row " + record++, cells));
                cells = new ArrayList<>();
            } else {
                field.append(ch);
            }
        }
        if (!field.isEmpty() || !cells.isEmpty()) {
            cells.add(clean(field.toString()));
            rows.add(new TableRow("row " + record, cells));
        }
        return new Table("CSV", rows);
    }

    /**
     * UTF-8 (BOM stripped) when the bytes are valid UTF-8; otherwise GB18030, which is what Excel on a
     * Chinese Windows writes for "CSV" and is a superset of ASCII - a silent UTF-8 decode would turn
     * every Chinese title into U+FFFD.
     */
    private static String decode(byte[] content) {
        String text;
        try {
            text = StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(content)).toString();
        } catch (CharacterCodingException e) {
            text = new String(content, Charset.forName("GB18030"));
        }
        return text.startsWith("﻿") ? text.substring(1) : text;
    }

    /** The most frequent of {@code , ; TAB} in the first line - a European Excel writes {@code ;}. */
    private static char detectDelimiter(String text) {
        int end = text.indexOf('\n');
        String first = end < 0 ? text : text.substring(0, end);
        char best = ',';
        long bestCount = -1;
        for (char candidate : new char[]{',', ';', '\t'}) {
            long count = first.chars().filter(c -> c == candidate).count();
            if (count > bestCount) {
                best = candidate;
                bestCount = count;
            }
        }
        return best;
    }

    // ---- tables -> rows (shared by every format) --------------------------------------------------

    ParsedReport read(List<Table> tables) {
        List<ReportRow> rows = new ArrayList<>();
        List<String> warnings = new ArrayList<>();
        int reportTables = 0;
        for (Table table : tables) {
            int headerAt = -1;
            Map<Column, Integer> index = null;
            for (int r = 0; r < Math.min(HEADER_SCAN_ROWS, table.rows().size()); r++) {
                Map<Column, Integer> candidate = columnIndex(table.rows().get(r).cells());
                if (candidate.containsKey(Column.AFFECTED_VERSION)
                        && (candidate.containsKey(Column.CVE) || candidate.containsKey(Column.TITLE))) {
                    headerAt = r;
                    index = candidate;
                    break;
                }
            }
            if (index == null) {
                continue;
            }
            reportTables++;
            for (int r = headerAt + 1; r < table.rows().size(); r++) {
                TableRow row = table.rows().get(r);
                readRow(row.cells(), index, table.name() + ", " + row.label(), rows, warnings);
            }
        }
        if (reportTables == 0) {
            throw new ReportFormatException("no table has an affected-version column "
                    + headers.get(Column.AFFECTED_VERSION) + " and a CVE " + headers.get(Column.CVE)
                    + " or title " + headers.get(Column.TITLE) + " column");
        }
        return new ParsedReport(List.copyOf(rows), List.copyOf(warnings));
    }

    private void readRow(List<String> cells, Map<Column, Integer> index, String location,
                         List<ReportRow> rows, List<String> warnings) {
        if (cells.stream().allMatch(String::isEmpty)) {
            return;
        }
        String version = cell(cells, index, Column.AFFECTED_VERSION);
        String title = cell(cells, index, Column.TITLE);
        List<String> cves = new ArrayList<>();
        Matcher m = CVE.matcher(cell(cells, index, Column.CVE));
        while (m.find()) {
            String id = m.group().toUpperCase(Locale.ROOT);
            if (!cves.contains(id)) {
                cves.add(id);
            }
        }
        if (version.isEmpty()) {
            warnings.add(location + ": skipped, no affected version");
            return;
        }
        if (cves.isEmpty() && title.isEmpty()) {
            warnings.add(location + ": skipped, neither a CVE nor a title");
            return;
        }
        Double cvss = parseCvss(cell(cells, index, Column.CVSS), location, warnings);
        for (String cve : cves.isEmpty() ? Collections.<String>singletonList(null) : cves) {
            rows.add(new ReportRow(location, cve, title, cell(cells, index, Column.COMPONENT),
                    cell(cells, index, Column.SEVERITY), cvss, version,
                    cell(cells, index, Column.FIXED_VERSION), cell(cells, index, Column.DESCRIPTION)));
        }
    }

    private Map<Column, Integer> columnIndex(List<String> cells) {
        Map<Column, Integer> index = new EnumMap<>(Column.class);
        for (int i = 0; i < cells.size(); i++) {
            String header = normalise(cells.get(i));
            for (Map.Entry<Column, List<String>> e : headers.entrySet()) {
                if (e.getValue().contains(header)) {
                    index.putIfAbsent(e.getKey(), i);
                }
            }
        }
        return index;
    }

    private static Double parseCvss(String raw, String location, List<String> warnings) {
        if (raw.isEmpty()) {
            return null;
        }
        Matcher m = NUMBER.matcher(raw);
        if (m.find()) {
            return Double.valueOf(m.group());
        }
        warnings.add(location + ": CVSS '" + raw + "' is not a number, left empty");
        return null;
    }

    private static String cell(List<String> cells, Map<Column, Integer> index, Column column) {
        Integer i = index.get(column);
        return i == null || i >= cells.size() ? "" : cells.get(i);
    }

    private static String text(Element cell) {
        return clean(cell.text());
    }

    /** jsoup and spreadsheets both keep {@code &nbsp;} as U+00A0, which {@code trim()} does not strip. */
    private static String clean(String text) {
        return text.replace(' ', ' ').trim();
    }

    private static String normalise(String header) {
        return header.toLowerCase(Locale.ROOT).replaceAll("\\s+", " ").trim();
    }

    private static List<String> normalised(List<String> values) {
        return values.stream().map(ReportParser::normalise).toList();
    }

    private static boolean startsWith(byte[] content, int... signature) {
        if (content.length < signature.length) {
            return false;
        }
        for (int i = 0; i < signature.length; i++) {
            if ((content[i] & 0xFF) != signature[i]) {
                return false;
            }
        }
        return true;
    }

    /** The source is reachable but holds no readable report. */
    public static class ReportFormatException extends RuntimeException {
        public ReportFormatException(String message) {
            super(message);
        }
    }

    public static class UnsupportedFileException extends RuntimeException {
        public UnsupportedFileException(String filename) {
            super("'" + filename + "' is not a supported report file - upload .html/.htm, .csv, .xlsx or .xls");
        }
    }
}
