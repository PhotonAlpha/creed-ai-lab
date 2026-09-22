package com.creed.jasper.render;

import net.sf.jasperreports.engine.JRDataSource;
import net.sf.jasperreports.engine.JRException;
import net.sf.jasperreports.engine.data.JsonDataSource;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;

/**
 * The listing's rows — whatever the caller has, turned into the one thing the table's
 * {@code datasetRun} takes: a {@link JRDataSource}.
 *
 * <p>Both ways the reference implementation feeds a report. It passes an ordinary data source for
 * the main report, and puts raw JSON on {@code JsonQueryExecuterFactory.JSON_INPUT_STREAM} so
 * JasperReports binds {@code $F{...}} straight to JSON properties — no Java type for the row at
 * all, which is what makes caller-defined columns over caller-supplied data work end to end.
 *
 * <p>Here the JSON half is a {@link JsonDataSource} built directly rather than a query on the
 * design. Same binding, and it means the table is always handed a data source: no {@code json}
 * query to put on a dataset, no {@code JSON_INPUT_STREAM} parameter to route into a subDataset,
 * and one fewer thing in {@code ReportShape}.
 *
 * <p><b>JSON has a shape limit worth knowing before choosing it.</b> A field binds to a JSON
 * <i>property</i>, so a value that is an array or an object has no useful rendering: the
 * approval-status listing's {@code account} block is four lines that must arrive joined, which is
 * a decision only Java can make. That document therefore uses {@link #source}; a flat table can
 * use {@link #json} and skip the model entirely.
 */
@FunctionalInterface
public interface TableData {

    /** The rows, as the table's {@code datasetRun} wants them. */
    JRDataSource rows();

    /** Rows a caller already turned into a data source. */
    static TableData source(JRDataSource rows) {
        return () -> rows;
    }

    /**
     * Rows read out of {@code json} at the JSONPath {@code select} (e.g. {@code rows} for
     * <code>{"rows":[…]}</code>). Field names are the JSON property names.
     */
    static TableData json(String json, String select) {
        return () -> {
            try {
                return new JsonDataSource(
                        new ByteArrayInputStream(json.getBytes(StandardCharsets.UTF_8)), select);
            }
            catch (JRException ex) {
                throw new IllegalArgumentException("Not a JSON document with an array at '"
                        + select + "'", ex);
            }
        };
    }
}
