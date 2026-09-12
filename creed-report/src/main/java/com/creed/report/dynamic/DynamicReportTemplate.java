package com.creed.report.dynamic;

import java.util.Locale;
import java.util.Map;

/**
 * The PDF layouts a dynamic report can be printed in — the caller supplies not only the table's
 * shape ({@link DynamicTableRequest}) but also the paper it is printed on.
 *
 * <p>A constant is a <b>chrome set + a template</b>, nothing more: each value names one
 * {@code *-pdf.html} in {@code templates/}, and that file wears one whole chrome set from
 * {@code fragments/report-chrome-pdf.html}. Adding a layout is therefore one constant, one
 * template and one chrome set — never a branch in the controller, which only ever asks the enum
 * for {@link #pdfTemplate()}.
 *
 * <p><b>Why only the PDF varies.</b> The layout choice is a paged-media concern: running
 * header/footer, page counter, stamp, page box. The offline HTML export is the live page's twin
 * (same Bootstrap markup, same country stylesheet) and has no pages to lay out, so it stays a
 * single template and ignores {@code template=} rather than growing a second look that nothing
 * would distinguish.
 *
 * <p>The wire code travels as {@code template=}, alongside {@code title}/{@code headers}/
 * {@code data} — which is why it is read off {@link DynamicTableRequest} and re-posted by the
 * page's export forms like the rest of the definition. An unknown code is bad input, not a
 * fallback: 400 via {@link InvalidTableDefinitionException}, the same answer an unparseable
 * {@code data} gets. Silently printing a different layout than the caller asked for would be
 * worse than refusing.
 */
public enum DynamicReportTemplate {

    /**
     * The printed-bank-form look modelled on
     * {@code pdf-template/sample-form-uob-infinity-standard-registration.pdf}: A4 landscape, logo
     * and uppercase blue title at the top left, sections opened by a blue rule and a numbered chip.
     * The default, so a caller that never heard of {@code template=} gets what it always got.
     */
    FORM("form", "dynamic-report-export-pdf"),

    /**
     * The transaction-statement look modelled on {@code docs/template.jpg}: A4 portrait, a running
     * header carrying nothing but the logo, the title under a rule in the body, and a two-storey
     * running footer — export date/time and report name on the left, seal on the right, page
     * counter centred below them in the page margin.
     */
    STATEMENT("statement", "dynamic-report-statement-pdf");

    /** Request parameter carrying the wire code, next to title/headers/data. */
    public static final String TEMPLATE_PARAM = "template";

    /** What an absent {@code template=} means — the layout this report had before it had a choice. */
    public static final DynamicReportTemplate DEFAULT = FORM;

    private final String code;
    private final String pdfTemplate;

    DynamicReportTemplate(String code, String pdfTemplate) {
        this.code = code;
        this.pdfTemplate = pdfTemplate;
    }

    /** Wire value, e.g. {@code statement}. */
    public String code() {
        return code;
    }

    /** Thymeleaf template name this layout renders through. */
    public String pdfTemplate() {
        return pdfTemplate;
    }

    /**
     * Message key of this layout's display name, resolved like every other label — so the page's
     * template picker is localized by the same bundles as the columns it prints.
     */
    public String labelKey() {
        return "report.template." + code;
    }

    /** Reads {@code template=} out of a request's raw parameter map. */
    public static DynamicReportTemplate from(Map<String, String> parameters) {
        return of(parameters.get(TEMPLATE_PARAM));
    }

    /**
     * Resolves a wire code. Blank means {@link #DEFAULT}; anything else must name a layout — an
     * unknown code is refused rather than defaulted, so a typo cannot quietly hand back the wrong
     * paper.
     */
    public static DynamicReportTemplate of(String code) {
        if (code == null || code.isBlank()) {
            return DEFAULT;
        }
        String wanted = code.trim().toLowerCase(Locale.ROOT);
        for (DynamicReportTemplate template : values()) {
            if (template.code.equals(wanted)) {
                return template;
            }
        }
        throw new InvalidTableDefinitionException("Unknown report template '" + code
                + "'; known templates are " + codes());
    }

    private static String codes() {
        StringBuilder codes = new StringBuilder();
        for (DynamicReportTemplate template : values()) {
            codes.append(codes.isEmpty() ? "" : ", ").append(template.code);
        }
        return codes.toString();
    }
}
