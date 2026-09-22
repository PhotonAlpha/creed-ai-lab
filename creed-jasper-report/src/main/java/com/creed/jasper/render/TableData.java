package com.creed.jasper.render;

import net.sf.jasperreports.engine.JRDataSource;
import net.sf.jasperreports.engine.query.JsonQueryExecuterFactory;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.Map;

/**
 * Where a fill's rows come from — either a {@link JRDataSource} the caller built, or raw JSON the
 * engine reads itself.
 *
 * <p>Both halves of the reference implementation. It puts the payload on
 * {@code JsonQueryExecuterFactory.JSON_INPUT_STREAM} and lets JasperReports bind {@code $F{...}}
 * straight to JSON properties — no Java type for the row at all, which is what makes
 * caller-defined columns over caller-supplied data work end to end. It also passes an ordinary
 * {@code JRDataSource} for the main report. Keeping both as one closed choice means the renderer
 * branches once, here, rather than everywhere a fill happens.
 *
 * <p><b>JSON has a shape limit worth knowing before choosing it.</b> A field binds to a JSON
 * <i>property</i>, so a value that is an array or an object has no useful rendering: the
 * approval-status listing's {@code account} block is four lines that must arrive joined, which is
 * a decision only Java can make. That document therefore uses {@link #source}; a flat table can
 * use {@link #json} and skip the model entirely.
 */
public sealed interface TableData {

    /** Adds whatever the fill needs of this source to the parameter map. */
    void contributeTo(Map<String, Object> parameters);

    /** The data source to fill with, or {@code null} when the engine builds its own from JSON. */
    JRDataSource dataSource();

    /** The JSONPath the rows live at, or {@code null} — see {@code ReportShape.fedByJson}. */
    String jsonQuery();

    /** Rows a caller already turned into a data source. */
    static TableData source(JRDataSource dataSource) {
        return new FromDataSource(dataSource);
    }

    /**
     * Rows the engine reads out of {@code json} itself, at the JSONPath {@code query}
     * (e.g. {@code rows} for <code>{"rows":[…]}</code>). Field names are the JSON property names.
     */
    static TableData json(String json, String query) {
        return new FromJson(json, query);
    }

    record FromDataSource(JRDataSource dataSource) implements TableData {

        @Override
        public void contributeTo(Map<String, Object> parameters) {
            // Nothing: the source is handed to fillReport directly.
        }

        @Override
        public String jsonQuery() {
            return null;
        }
    }

    record FromJson(String json, String query) implements TableData {

        @Override
        public void contributeTo(Map<String, Object> parameters) {
            // A fresh stream per fill: it is read to exhaustion, so a shared one would serve the
            // first caller and hand every later one an empty report.
            parameters.put(JsonQueryExecuterFactory.JSON_INPUT_STREAM,
                    new ByteArrayInputStream(json.getBytes(StandardCharsets.UTF_8)));
        }

        @Override
        public JRDataSource dataSource() {
            return null;
        }

        @Override
        public String jsonQuery() {
            return query;
        }
    }
}
