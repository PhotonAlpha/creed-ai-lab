package com.creed.resource.veracode.template;

import com.creed.resource.veracode.api.dto.AnalysisResponse;
import com.creed.resource.veracode.domain.ApplicationAliases;
import com.creed.resource.veracode.config.ConfluenceProperties;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.jxls.transform.poi.JxlsPoiTemplateFillerBuilder;
import org.springframework.stereotype.Service;
import tools.jackson.databind.json.JsonMapper;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.time.Clock;
import java.time.Instant;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Report templates (asked for, 2026-11-12): a company workbook uploaded once, its cells bound to fields on the page,
 * filled with an analysis at export. Upload -> {@link TemplateWorkbook#read} (the page's grid) -> {@link TemplateMapping}
 * saved -> {@link TemplateWorkbook#withJxlsMarkup} -> JXLS fills it with {@link TemplateFields#data}.
 *
 * <p>Only the upload and the mapping are stored: the JXLS template is derived at each export, so a mapping change or a
 * bug fix in the markup applies to every template at once.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class ReportTemplateService {

    /** Excel's own limits. */
    private static final int MAX_ROW = 1_048_575;
    private static final int MAX_COL = 16_383;

    private final ReportTemplateRepository repository;
    private final JsonMapper json;
    private final ApplicationAliases apps;
    private final ConfluenceProperties confluence;
    private final Clock clock;

    /** @param lists data rows; @param values summary cells - the list's "is it bound yet" */
    public record Summary(String id, String name, String fileName, int lists, int values, Instant updatedAt) {
    }

    public record Detail(String id, String name, String fileName, List<TemplateWorkbook.SheetView> sheets,
                         TemplateMapping mapping, Instant updatedAt) {
    }

    /**
     * @param fileName what the browser saves it as: the template's name and the export time, with the upload's
     *                 extension ({@code .xlsm} keeps its macros, and Excel refuses one under {@code .xlsx})
     */
    public record Filled(String fileName, byte[] content) {

        public boolean macroEnabled() {
            return fileName.toLowerCase(java.util.Locale.ROOT).endsWith(".xlsm");
        }
    }

    public static class TemplateNotFoundException extends RuntimeException {
        public TemplateNotFoundException(String id) {
            super("no report template " + id);
        }
    }

    public List<Summary> list() {
        return repository.list().stream().map(h -> {
            TemplateMapping m = mapping(h.mapping());
            return new Summary(h.id(), h.name(), h.fileName(),
                    m.sheets().stream().mapToInt(s -> s.lists().size()).sum(),
                    m.sheets().stream().mapToInt(s -> s.values().size()).sum(), h.updatedAt());
        }).toList();
    }

    /** Stored as uploaded, unbound; a file POI cannot read as {@code .xlsx} is refused here, not at export. */
    public Detail create(String name, String fileName, byte[] content) {
        List<TemplateWorkbook.SheetView> sheets = TemplateWorkbook.read(content);
        String title = name == null || name.isBlank()
                ? fileName.replaceFirst("(?i)\\.xls[xm]$", "") : name.trim();
        Instant now = clock.instant();
        ReportTemplateRepository.Row row = new ReportTemplateRepository.Row(UUID.randomUUID().toString(),
                title.length() > 200 ? title.substring(0, 200) : title, fileName, content,
                json.writeValueAsString(TemplateMapping.EMPTY), now, now);
        repository.insert(row);
        log.info("report template '{}' ({}, {} bytes, {} sheet(s)) stored as {}", row.name(), fileName,
                content.length, sheets.size(), row.id());
        return new Detail(row.id(), row.name(), fileName, sheets, TemplateMapping.EMPTY, now);
    }

    public Detail get(String id) {
        ReportTemplateRepository.Row row = require(id);
        return new Detail(row.id(), row.name(), row.fileName(), TemplateWorkbook.read(row.content()),
                mapping(row.mapping()), row.updatedAt());
    }

    /** @throws IllegalArgumentException a binding that cannot be written - nothing is saved then */
    public Detail saveMapping(String id, TemplateMapping mapping) {
        ReportTemplateRepository.Row row = require(id);
        TemplateMapping clean = mapping == null ? TemplateMapping.EMPTY : mapping;
        try (XSSFWorkbook wb = TemplateWorkbook.open(row.content())) {
            validate(clean, wb);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        Instant now = clock.instant();
        repository.updateMapping(id, json.writeValueAsString(clean), now);
        return new Detail(row.id(), row.name(), row.fileName(), TemplateWorkbook.read(row.content()), clean, now);
    }

    public void delete(String id) {
        if (!repository.delete(id)) {
            throw new TemplateNotFoundException(id);
        }
    }

    /** The workbook with its JXLS markup, as export fills it - to look at, or to fill elsewhere. */
    public Filled jxlsTemplate(String id) {
        ReportTemplateRepository.Row row = require(id);
        return new Filled(row.name() + "-jxls" + extension(row.fileName()),
                TemplateWorkbook.withJxlsMarkup(row.content(), mapping(row.mapping())));
    }

    /** The analysis written into the template. Sheets without bindings come out as uploaded. */
    public Filled fill(String id, AnalysisResponse analysis) {
        ReportTemplateRepository.Row row = require(id);
        TemplateMapping mapping = mapping(row.mapping());
        if (mapping.isEmpty()) {
            throw new IllegalArgumentException("report template '" + row.name()
                    + "' has no bound cells yet: bind them on the template page first");
        }
        Instant now = clock.instant();
        long started = System.nanoTime();
        // conditional formatting off while JXLS fills (it copies it cell by cell: quadratic), back on afterwards
        TemplateWorkbook.ForFill template = TemplateWorkbook.forFill(row.content(), mapping);
        Map<String, Object> data = TemplateFields.data(analysis, apps, now, confluence.exportZone());
        Map<String, Integer> items = new java.util.LinkedHashMap<>();
        data.forEach((dataset, value) -> {
            if (value instanceof java.util.Collection<?> c) {
                items.put(dataset, c.size());
            }
        });
        byte[] filled = TemplateWorkbook.withConditionalFormatting(JxlsPoiTemplateFillerBuilder.newInstance()
                .withTemplate(new ByteArrayInputStream(template.workbook()))
                .buildAndFill(data), mapping, template, items);
        log.info("report template '{}' filled in {} ms: {} item(s) per dataset, {} conditional formatting(s) put back",
                row.name(), (System.nanoTime() - started) / 1_000_000, items,
                template.formatting().values().stream().mapToInt(List::size).sum());
        String stamp = java.time.format.DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss")
                .withZone(confluence.exportZone()).format(now);
        return new Filled(row.name() + "-" + stamp + extension(row.fileName()), filled);
    }

    /** The upload's: {@code .xlsm} or {@code .xlsx}. */
    static String extension(String fileName) {
        return fileName != null && fileName.toLowerCase(java.util.Locale.ROOT).endsWith(".xlsm") ? ".xlsm" : ".xlsx";
    }

    private ReportTemplateRepository.Row require(String id) {
        return repository.find(id).orElseThrow(() -> new TemplateNotFoundException(id));
    }

    private TemplateMapping mapping(String stored) {
        return stored == null || stored.isBlank() ? TemplateMapping.EMPTY
                : json.readValue(stored, TemplateMapping.class);
    }

    /**
     * What JXLS could not honour, refused before saving: an unknown sheet, dataset or field; two data rows on one row;
     * a data row inside a merge spanning rows (repeating it would tear the merge); a summary cell on a data row (it
     * would repeat); one cell bound twice.
     */
    static void validate(TemplateMapping mapping, XSSFWorkbook wb) {
        Set<String> sheets = new HashSet<>();
        for (TemplateMapping.SheetMapping sm : mapping.sheets()) {
            Sheet sheet = sm.sheet() == null ? null : wb.getSheet(sm.sheet());
            if (sheet == null) {
                throw new IllegalArgumentException("no sheet '" + sm.sheet() + "' in the workbook");
            }
            if (!sheets.add(sm.sheet())) {
                throw new IllegalArgumentException("sheet '" + sm.sheet() + "' is mapped twice");
            }
            Set<Integer> listRows = new HashSet<>();
            Set<String> cells = new HashSet<>();
            for (TemplateMapping.ListBinding list : sm.lists()) {
                String where = sm.sheet() + "!" + (list.row() + 1);
                if (!TemplateFields.isList(list.dataset())) {
                    throw new IllegalArgumentException(where + ": unknown dataset '" + list.dataset() + "'");
                }
                checkRow(list.row(), where);
                if (!listRows.add(list.row())) {
                    throw new IllegalArgumentException(where + ": two data rows on one row");
                }
                if (TemplateWorkbook.mergedAcrossRows(sheet, list.row())) {
                    throw new IllegalArgumentException(where + ": a data row cannot be inside a merge that spans rows");
                }
                if (list.cells().isEmpty()) {
                    throw new IllegalArgumentException(where + ": a data row needs at least one bound cell");
                }
                for (TemplateMapping.FieldCell cell : list.cells()) {
                    String at = sm.sheet() + "!" + TemplateWorkbook.ref(list.row(), Math.max(cell.col(), 0));
                    checkCol(cell.col(), at);
                    if (!TemplateFields.known(list.dataset(), cell.field())) {
                        throw new IllegalArgumentException(at + ": no field '" + cell.field() + "' in "
                                + list.dataset());
                    }
                    arrayFormula(sheet, list.row(), cell.col(), at);
                    if (!cells.add(at)) {
                        throw new IllegalArgumentException(at + ": bound twice");
                    }
                }
            }
            for (TemplateMapping.CellBinding value : sm.values()) {
                checkRow(value.row(), sm.sheet() + "!" + (value.row() + 1));
                String at = sm.sheet() + "!" + TemplateWorkbook.ref(value.row(), Math.max(value.col(), 0));
                checkCol(value.col(), at);
                if (listRows.contains(value.row())) {
                    throw new IllegalArgumentException(at + ": a summary field on a data row would repeat - "
                            + "move it or bind it as a column of the data row");
                }
                if (!TemplateFields.known(TemplateFields.SUMMARY, value.field())) {
                    throw new IllegalArgumentException(at + ": no summary field '" + value.field() + "'");
                }
                arrayFormula(sheet, value.row(), value.col(), at);
                if (!cells.add(at)) {
                    throw new IllegalArgumentException(at + ": bound twice");
                }
            }
        }
    }

    /** A cell of a multi-cell array formula cannot take a value of its own (Excel refuses it too). */
    private static void arrayFormula(Sheet sheet, int row, int col, String at) {
        if (TemplateWorkbook.inArrayFormula(sheet, row, col)) {
            throw new IllegalArgumentException(at + ": the cell is part of an array formula - remove the formula in "
                    + "Excel or bind another cell");
        }
    }

    private static void checkRow(int row, String where) {
        if (row < 0 || row > MAX_ROW) {
            throw new IllegalArgumentException(where + ": row out of range");
        }
    }

    private static void checkCol(int col, String where) {
        if (col < 0 || col > MAX_COL) {
            throw new IllegalArgumentException(where + ": column out of range");
        }
    }
}
