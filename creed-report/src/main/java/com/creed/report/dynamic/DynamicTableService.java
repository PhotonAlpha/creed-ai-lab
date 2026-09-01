package com.creed.report.dynamic;

import com.creed.report.i18n.CountryFormatter;
import com.creed.report.i18n.CountryProfile;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.MessageSource;
import org.springframework.stereotype.Service;

import java.text.NumberFormat;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Turns the request's {@code headers} + {@code data} into a {@link DynamicTable}: resolves the
 * column labels through the message bundles and formats every cell for the country.
 *
 * <h2>Columns</h2>
 * {@code headers} is split on commas. Each token is a <b>column key</b>, and its label is resolved
 * as {@code report.col.<key>} — which is why {@code headers=host,ip,app,country} comes out
 * translated into any of the module's languages for free, reusing the bundles the server report
 * already ships. A key with no message shows verbatim, so an ad-hoc column still works; write
 * {@code key:Label} to spell a label out explicitly instead.
 *
 * <h2>Rows</h2>
 * {@code data} is a JSON array in either shape:
 * <ul>
 *   <li><b>objects</b> — {@code [{"host":"a","ip":"b"}]}: each column reads its key, a missing
 *       field is an empty cell, so column order is free and rows may be ragged;</li>
 *   <li><b>arrays</b> — {@code [["a","b"]]}: positional, for callers whose data has no field
 *       names. Extra values past the last column are dropped, short rows are padded.</li>
 * </ul>
 *
 * <h2>Country</h2>
 * Formatting is the country's, not the language's: numbers take its grouping separators
 * ({@code 1.234} in Vietnam), booleans become the localized yes/no. Strings pass through untouched
 * — deliberately, since guessing at date-like strings would corrupt identifiers that merely look
 * like dates.
 */
@Service
@EnableConfigurationProperties(DynamicTableProperties.class)
public class DynamicTableService {

    /** Message code prefix column labels are looked up under — the server report's own columns. */
    private static final String COLUMN_KEY_PREFIX = "report.col.";

    /** Separates a column's key from an explicit label: {@code uptime:Uptime (days)}. */
    private static final char LABEL_SEPARATOR = ':';

    private final ObjectMapper objectMapper;
    private final MessageSource messageSource;
    private final DynamicTableProperties properties;

    public DynamicTableService(ObjectMapper objectMapper, MessageSource messageSource,
                               DynamicTableProperties properties) {
        this.objectMapper = objectMapper;
        this.messageSource = messageSource;
        this.properties = properties;
    }

    /**
     * Builds the table for one request.
     *
     * @throws InvalidTableDefinitionException on missing headers, unparseable or wrongly shaped
     *                                         JSON, or a payload over the configured limits
     */
    public DynamicTable build(DynamicTableRequest request, CountryProfile profile, Locale locale) {
        List<DynamicTable.Column> columns = columns(request.headers(), locale);
        List<Object> rawRows = rows(request.data());
        if (rawRows.size() > properties.getMaxRows()) {
            throw new InvalidTableDefinitionException("'data' holds " + rawRows.size()
                    + " rows; at most " + properties.getMaxRows() + " are allowed");
        }

        List<List<DynamicTable.Cell>> rows = new ArrayList<>(rawRows.size());
        for (Object rawRow : rawRows) {
            rows.add(cells(rawRow, columns, profile, locale));
        }
        return new DynamicTable(title(request.title(), locale), columns, rows);
    }

    private String title(String requested, Locale locale) {
        return (requested != null && !requested.isBlank()) ? requested.trim()
                : messageSource.getMessage("report.dynamic.defaultTitle", null, locale);
    }

    private List<DynamicTable.Column> columns(String headers, Locale locale) {
        if (headers == null || headers.isBlank()) {
            throw new InvalidTableDefinitionException("'headers' is required: a comma-separated list of column keys");
        }
        List<DynamicTable.Column> columns = new ArrayList<>();
        for (String token : headers.split(",")) {
            String trimmed = token.trim();
            if (trimmed.isEmpty()) {
                continue; // Tolerate a trailing or doubled comma rather than failing the report.
            }
            columns.add(column(trimmed, locale));
        }
        if (columns.isEmpty()) {
            throw new InvalidTableDefinitionException("'headers' named no columns");
        }
        if (columns.size() > properties.getMaxColumns()) {
            throw new InvalidTableDefinitionException("'headers' declares " + columns.size()
                    + " columns; at most " + properties.getMaxColumns() + " are allowed");
        }
        return columns;
    }

    private DynamicTable.Column column(String token, Locale locale) {
        int separator = token.indexOf(LABEL_SEPARATOR);
        if (separator > 0 && separator < token.length() - 1) {
            return new DynamicTable.Column(token.substring(0, separator).trim(),
                    token.substring(separator + 1).trim());
        }
        // No message for this key: show the key itself, so an ad-hoc column needs no bundle entry.
        return new DynamicTable.Column(token, messageSource.getMessage(COLUMN_KEY_PREFIX + token, null, token, locale));
    }

    private List<Object> rows(String data) {
        if (data == null || data.isBlank()) {
            return List.of(); // A headers-only request is a valid, empty report.
        }
        try {
            return objectMapper.readValue(data, new TypeReference<List<Object>>() { });
        }
        catch (JsonProcessingException ex) {
            throw new InvalidTableDefinitionException(
                    "'data' is not a JSON array of rows: " + ex.getOriginalMessage(), ex);
        }
    }

    private List<DynamicTable.Cell> cells(Object rawRow, List<DynamicTable.Column> columns,
                                          CountryProfile profile, Locale locale) {
        List<DynamicTable.Cell> cells = new ArrayList<>(columns.size());
        if (rawRow instanceof Map<?, ?> object) {
            for (DynamicTable.Column column : columns) {
                cells.add(cell(object.get(column.key()), profile, locale));
            }
        }
        else if (rawRow instanceof List<?> array) {
            for (int i = 0; i < columns.size(); i++) {
                cells.add(cell(i < array.size() ? array.get(i) : null, profile, locale));
            }
        }
        else {
            throw new InvalidTableDefinitionException(
                    "every element of 'data' must be a JSON object or array, got: " + rawRow);
        }
        return cells;
    }

    private DynamicTable.Cell cell(Object value, CountryProfile profile, Locale locale) {
        return new DynamicTable.Cell(value, text(value, profile, locale));
    }

    private String text(Object value, CountryProfile profile, Locale locale) {
        if (value == null) {
            return "";
        }
        if (value instanceof Boolean flag) {
            return messageSource.getMessage(flag ? "excel.value.yes" : "excel.value.no", null, locale);
        }
        if (value instanceof Number number) {
            // Integral values go through the country's integer format so they match the counts the
            // rest of the report shows; fractional ones keep their decimals.
            return isIntegral(number) ? CountryFormatter.number(number.longValue(), profile)
                    : NumberFormat.getNumberInstance(profile.numberLocale()).format(number);
        }
        if (value instanceof Map || value instanceof List) {
            // A nested structure has no cell layout of its own; show its JSON rather than
            // java.util's toString, which is not valid JSON and reads badly in a spreadsheet.
            try {
                return objectMapper.writeValueAsString(value);
            }
            catch (JsonProcessingException ex) {
                return String.valueOf(value);
            }
        }
        return String.valueOf(value);
    }

    private static boolean isIntegral(Number number) {
        return number instanceof Integer || number instanceof Long || number instanceof Short
                || number instanceof Byte || number instanceof java.math.BigInteger;
    }
}
