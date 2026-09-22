package com.creed.jasper.report;

import net.sf.jasperreports.engine.JRDataSource;
import net.sf.jasperreports.engine.JRException;
import net.sf.jasperreports.engine.JRField;

import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

/**
 * A {@link JRDataSource} over a list of anything, with the jrxml's field names mapped to getters
 * <b>explicitly</b>.
 *
 * <p>JasperReports' own {@code JRBeanCollectionDataSource} reads its objects through
 * commons-beanutils, i.e. by the JavaBeans {@code getXxx()} convention — which a Java
 * {@code record} does not follow ({@code label()}, not {@code getLabel()}). Rather than reshape the
 * domain into beans to suit the report engine, or hand the fill a {@code Map} and lose every
 * compile-time check, this keeps the mapping in one visible place:
 *
 * <pre>{@code
 * FieldDataSource.of(report.criteria(), Map.of("label", Criterion::label,
 *                                              "value", Criterion::value));
 * }</pre>
 *
 * <p>The payoff is that renaming a record component breaks the <b>build</b>, and a field the jrxml
 * declares but nothing supplies throws by name at fill time instead of printing an empty cell —
 * the two ways a report silently loses a column.
 *
 * <p>Not thread-safe and single-pass, like every {@code JRDataSource}: a fill consumes it once.
 *
 * @param <T> the element type
 */
public final class FieldDataSource<T> implements JRDataSource {

    private final Map<String, Function<T, Object>> fields;
    private final Iterator<T> elements;
    private T current;

    private FieldDataSource(List<T> elements, Map<String, Function<T, Object>> fields) {
        // Copied, so a caller cannot mutate either out from under a fill in progress.
        this.elements = List.copyOf(elements).iterator();
        this.fields = new LinkedHashMap<>(fields);
    }

    /** A data source over {@code elements}, reading the named fields with the given accessors. */
    public static <T> FieldDataSource<T> of(List<T> elements, Map<String, Function<T, Object>> fields) {
        return new FieldDataSource<>(elements, fields);
    }

    @Override
    public boolean next() {
        boolean hasNext = elements.hasNext();
        current = hasNext ? elements.next() : null;
        return hasNext;
    }

    @Override
    public Object getFieldValue(JRField field) throws JRException {
        Function<T, Object> accessor = fields.get(field.getName());
        if (accessor == null) {
            // Loud on purpose: an unmapped field is a jrxml and a data source that have drifted
            // apart, and JasperReports' own answer -- null, i.e. a blank cell -- hides it.
            throw new JRException("No accessor for field '" + field.getName() + "'; this data source"
                    + " supplies " + fields.keySet());
        }
        return current == null ? null : accessor.apply(current);
    }
}
