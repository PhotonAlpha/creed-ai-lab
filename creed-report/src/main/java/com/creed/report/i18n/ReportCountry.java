package com.creed.report.i18n;

import java.util.List;
import java.util.Locale;
import java.util.Optional;

/**
 * The countries the report can be rendered for — the second presentation axis next to language.
 *
 * <p>Country and language are deliberately <b>separate</b> inputs ({@code ?country=my&lang=en}) and
 * only get folded into one {@link Locale} at the end: the country contributes the region subtag, so
 * {@link org.springframework.context.support.ResourceBundleMessageSource} gives the three-level
 * fallback {@code report-messages_en_MY} → {@code report-messages_en} → {@code report-messages} for
 * free, and a country bundle only has to carry the handful of keys that actually differ.
 *
 * <p>{@link #GLOBAL} is the region-less default and the reason the region is a {@code String}
 * rather than an {@code Optional}: with an empty region the language locale is passed through
 * untouched, which is what keeps {@code zh-CN} and {@code zh-TW} resolving to their own bundles
 * instead of collapsing onto bare {@code zh}.
 *
 * <p>The constants carry the built-in presentation defaults — the {@code :fallback} half of the
 * module's config convention. {@link CountryProperties} overrides any of them from configuration,
 * and {@link CountryCatalog} merges the two.
 */
public enum ReportCountry {

    /** No region: every server, ISO-ordered dates, the module's original en/zh behaviour. */
    GLOBAL("", List.of("en", "zh-CN", "zh-TW"), "yyyy-MM-dd HH:mm:ss", Calendar.ISO, "en"),

    /**
     * Thailand. Dates are rendered in the Buddhist era (CE + 543) because that — not the language —
     * is what Thai documents date by: {@code ?country=th&lang=en} still shows 2569, not 2026.
     */
    TH("TH", List.of("th", "en"), "dd MMM yyyy HH:mm:ss", Calendar.BUDDHIST, "th-TH"),

    /** Malaysia. Day-first dates on a 12-hour clock, whose PG/PTG markers come from the locale. */
    MY("MY", List.of("ms", "en"), "dd/MM/yyyy hh:mm:ss a", Calendar.ISO, "ms-MY"),

    /** Vietnam. Day-first dates, and {@code vi-VN} number grouping ({@code 1.234}, not {@code 1,234}). */
    VN("VN", List.of("vi", "en"), "dd/MM/yyyy HH:mm:ss", Calendar.ISO, "vi-VN");

    /** Calendar system dates are rendered in; the reason a country can change the year itself. */
    public enum Calendar {
        ISO, BUDDHIST
    }

    private final String region;
    private final List<String> languages;
    private final String datePattern;
    private final Calendar calendar;
    private final String numberLocale;

    ReportCountry(String region, List<String> languages, String datePattern, Calendar calendar,
                  String numberLocale) {
        this.region = region;
        this.languages = List.copyOf(languages);
        this.datePattern = datePattern;
        this.calendar = calendar;
        this.numberLocale = numberLocale;
    }

    /** Stable wire value used on {@code ?country=} and as the CSS/fragment discriminator. */
    public String code() {
        return name().toLowerCase(Locale.ROOT);
    }

    /** ISO 3166 region folded into the effective locale; empty for {@link #GLOBAL}. */
    public String region() {
        return region;
    }

    /** Language tags this country renders in, most preferred first — the first is its default. */
    public List<String> languages() {
        return languages;
    }

    /** Default {@link java.time.format.DateTimeFormatter} pattern for this country. */
    public String datePattern() {
        return datePattern;
    }

    /** Default calendar system for this country. */
    public Calendar calendar() {
        return calendar;
    }

    /** Default language tag whose grouping separators numbers are formatted with. */
    public String numberLocale() {
        return numberLocale;
    }

    /**
     * Marker class put on {@code <body>}, e.g. {@code country-th}.
     *
     * <p>A label, not a selector hook: a page loads only its own country's stylesheet, so the
     * country's rules need no prefix. It is here so a rendered page says which edition it is.
     */
    public String styleClass() {
        return "country-" + code();
    }

    /*
     * Everything a country edition owns lives under its own code, in two places:
     *
     *   templates/country/<code>/report.html         browser fragments
     *   templates/country/<code>/report-pdf.html     PDF fragments
     *   static/css/country/<code>/style.css          browser stylesheet
     *   static/css/country/<code>/style-pdf.css      PDF stylesheet (inlined, not linked)
     *
     * The four accessors below are the single definition of that layout: templates and
     * CountryStyles both go through them, so adding a country is creating those files and adding
     * a constant -- never editing a shared block, and never mangling a fragment name.
     */

    /** Thymeleaf template holding this country's browser fragments. */
    public String contentTemplate() {
        return "country/" + code() + "/report";
    }

    /** Thymeleaf template holding this country's PDF fragments. */
    public String pdfContentTemplate() {
        return "country/" + code() + "/report-pdf";
    }

    /** Context-relative URL of this country's browser stylesheet. */
    public String styleSheet() {
        return "/css/country/" + code() + "/style.css";
    }

    /**
     * This country's PDF stylesheet. Inlined into the PDF rather than linked — openpdf-html renders
     * from a string with no base URL — though it sits under the same directory as the browser one
     * so a country edition stays a single folder.
     */
    public String pdfStyleSheet() {
        return "/css/country/" + code() + "/style-pdf.css";
    }

    /**
     * Resolves a request value to a country, accepting either the wire code ({@code th}) or the
     * ISO region ({@code TH}). Unknown input is {@link Optional#empty()} rather than an error:
     * country is a presentation preference, so a bad value falls through to the next source.
     */
    public static Optional<ReportCountry> of(String value) {
        if (value == null || value.isBlank()) {
            return Optional.empty();
        }
        String normalized = value.trim().toLowerCase(Locale.ROOT);
        for (ReportCountry country : values()) {
            if (country.code().equals(normalized)) {
                return Optional.of(country);
            }
        }
        return Optional.empty();
    }

    /** The country owning an ISO region, e.g. the {@code TH} of an {@code Accept-Language: th-TH}. */
    public static Optional<ReportCountry> byRegion(String region) {
        if (region == null || region.isBlank()) {
            return Optional.empty();
        }
        for (ReportCountry country : values()) {
            if (!country.region.isEmpty() && country.region.equalsIgnoreCase(region.trim())) {
                return Optional.of(country);
            }
        }
        return Optional.empty();
    }
}
