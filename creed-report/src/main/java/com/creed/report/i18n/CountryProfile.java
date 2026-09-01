package com.creed.report.i18n;

import java.util.List;
import java.util.Locale;

/**
 * One country resolved against one requested language: everything a view, an export or a formatter
 * needs in order to render for that country.
 *
 * <p>This is the object the templates receive as {@code ${profile}}. The page header and footer
 * never read anything but {@link #countryNameKey()} from it — they are identical for every country
 * by construction — while the body pulls that country's markup and CSS in by path
 * ({@link #contentTemplate()}, {@link #styleSheet()}), never by branching on the country.
 *
 * @param country      the country axis
 * @param locale       the effective locale, language + the country's region (see {@link #effectiveLocale})
 * @param languages    the language tags this country offers, most preferred first
 * @param datePattern  {@link java.time.format.DateTimeFormatter} pattern for timestamps
 * @param calendar     calendar system timestamps are converted to before formatting
 * @param numberLocale locale supplying grouping separators for counts
 */
public record CountryProfile(ReportCountry country,
                             Locale locale,
                             List<String> languages,
                             String datePattern,
                             ReportCountry.Calendar calendar,
                             Locale numberLocale) {

    public CountryProfile {
        languages = List.copyOf(languages);
    }

    /** Built-in profile for a country, using the enum's defaults — the no-configuration path. */
    public static CountryProfile of(ReportCountry country, Locale requested) {
        return new CountryProfile(country,
                effectiveLocale(country, country.languages(), requested),
                country.languages(),
                country.datePattern(),
                country.calendar(),
                Locale.forLanguageTag(country.numberLocale()));
    }

    /**
     * Folds a requested language and a country into the single locale everything downstream runs
     * on.
     *
     * <p>An unsupported language falls back to the country's default rather than being carried
     * through: a country renders in its own languages, so {@code ?country=th&lang=fr} is Thai, not
     * a Thai page with French chrome. {@link ReportCountry#GLOBAL} has no region and returns the
     * language locale untouched — that is what keeps {@code zh-CN} and {@code zh-TW} apart.
     */
    public static Locale effectiveLocale(ReportCountry country, List<String> languages, Locale requested) {
        Locale language = Locale.forLanguageTag(bestLanguage(languages, requested));
        if (country.region().isEmpty()) {
            return language;
        }
        return new Locale.Builder()
                .setLanguage(language.getLanguage())
                .setRegion(country.region())
                .build();
    }

    private static String bestLanguage(List<String> languages, Locale requested) {
        if (requested != null) {
            String tag = requested.toLanguageTag();
            for (String candidate : languages) {
                if (candidate.equalsIgnoreCase(tag)) {
                    return candidate;
                }
            }
            String language = requested.getLanguage();
            for (String candidate : languages) {
                if (Locale.forLanguageTag(candidate).getLanguage().equals(language)) {
                    return candidate;
                }
            }
        }
        return languages.get(0);
    }

    /** Wire value of the country, e.g. {@code th}; also names its content fragment. */
    public String code() {
        return country.code();
    }

    /** Marker class for {@code <body>}, e.g. {@code country-th}; see {@link ReportCountry#styleClass()}. */
    public String styleClass() {
        return country.styleClass();
    }

    /** Template of this country's browser fragments — {@code ~{${profile.contentTemplate} :: notice}}. */
    public String contentTemplate() {
        return country.contentTemplate();
    }

    /** Template of this country's PDF fragments. */
    public String pdfContentTemplate() {
        return country.pdfContentTemplate();
    }

    /** Context-relative URL of this country's browser stylesheet. */
    public String styleSheet() {
        return country.styleSheet();
    }

    /** BCP-47 tag of the effective locale, for {@code <html lang>} and the language switcher. */
    public String languageTag() {
        return locale.toLanguageTag();
    }

    /**
     * Whether the effective locale is the given language tag — what the language switcher marks as
     * active. A bare tag matches on language alone ({@code th} matches {@code th-TH}); a tag that
     * names a region must match it too, so {@code zh-CN} and {@code zh-TW} stay distinguishable.
     */
    public boolean isLanguage(String tag) {
        Locale other = Locale.forLanguageTag(tag);
        return locale.getLanguage().equals(other.getLanguage())
                && (other.getCountry().isEmpty() || other.getCountry().equals(locale.getCountry()));
    }

    /** Message key for this country's display name; resolved through the region bundle. */
    public String countryNameKey() {
        return "report.country.name";
    }
}
