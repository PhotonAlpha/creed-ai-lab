package com.creed.report.i18n;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.Collections;
import java.util.EnumMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/**
 * The country axis, resolved. Merges the {@link ReportCountry} built-in defaults with the
 * {@link CountryProperties} overrides once at startup, then answers the two questions the rest of
 * the module asks: <em>which country is this request for</em> and <em>what profile does it get</em>.
 *
 * <p>Merging happens here rather than in the enum so a misconfigured override fails at startup with
 * the country named, in the same spirit as
 * {@link com.creed.report.export.ExcelExportService}'s duplicate-strategy check.
 */
@Component
public class CountryCatalog {

    private static final Logger log = LoggerFactory.getLogger(CountryCatalog.class);

    private final Map<ReportCountry, Settings> settings;
    private final ReportCountry defaultCountry;

    public CountryCatalog(CountryProperties properties) {
        Map<ReportCountry, Settings> merged = new EnumMap<>(ReportCountry.class);
        for (ReportCountry country : ReportCountry.values()) {
            merged.put(country, merge(country, properties.getProfiles().get(country)));
        }
        this.settings = Collections.unmodifiableMap(merged);
        this.defaultCountry = properties.getDefaultCountry();
        log.info("Report countries registered: {} (default {})", settings.keySet(), defaultCountry);
    }

    private static Settings merge(ReportCountry country, CountryProperties.Profile override) {
        Settings defaults = new Settings(country.languages(), country.datePattern(),
                country.calendar(), country.numberLocale());
        if (override == null) {
            return defaults;
        }
        List<String> languages = override.getLanguages() != null && !override.getLanguages().isEmpty()
                ? List.copyOf(override.getLanguages()) : defaults.languages();
        Settings settings = new Settings(languages,
                override.getDatePattern() != null ? override.getDatePattern() : defaults.datePattern(),
                override.getCalendar() != null ? override.getCalendar() : defaults.calendar(),
                override.getNumberLocale() != null ? override.getNumberLocale() : defaults.numberLocale());
        validate(country, settings);
        return settings;
    }

    private static void validate(ReportCountry country, Settings settings) {
        try {
            java.time.format.DateTimeFormatter.ofPattern(settings.datePattern());
        }
        catch (IllegalArgumentException ex) {
            throw new IllegalStateException("Invalid date-pattern configured for country "
                    + country.code() + ": " + settings.datePattern(), ex);
        }
        if (Locale.forLanguageTag(settings.numberLocale()).getLanguage().isEmpty()) {
            throw new IllegalStateException("Invalid number-locale configured for country "
                    + country.code() + ": " + settings.numberLocale());
        }
    }

    /** Every country, in enum order — what the page's country switcher offers. */
    public List<ReportCountry> countries() {
        return List.of(ReportCountry.values());
    }

    /** Country used when a request carries no usable hint. */
    public ReportCountry defaultCountry() {
        return defaultCountry;
    }

    /** The profile for an explicit country rendered in the requested language. */
    public CountryProfile profile(ReportCountry country, Locale requested) {
        Settings s = settings.get(country);
        return new CountryProfile(country,
                CountryProfile.effectiveLocale(country, s.languages(), requested),
                s.languages(), s.datePattern(), s.calendar(), Locale.forLanguageTag(s.numberLocale()));
    }

    /**
     * The profile implied by an already-effective locale, i.e. the inverse of
     * {@link CountryProfile#effectiveLocale}: the region subtag names the country.
     *
     * <p>This is how controllers, exporters and the PDF renderer stay country-aware without
     * threading a second argument through every signature — the locale they already receive
     * carries the country.
     */
    public CountryProfile profileFor(Locale locale) {
        ReportCountry country = locale != null
                ? ReportCountry.byRegion(locale.getCountry()).orElse(defaultCountry)
                : defaultCountry;
        return profile(country, locale);
    }

    /**
     * Country for a request, most explicit source first: the {@code ?country=} value, the cookie,
     * then the language hint — its region subtag ({@code Accept-Language: th-TH}), else the country
     * whose default language it is ({@code Accept-Language: th}) — and finally the configured
     * default. Unusable values fall through instead of failing.
     */
    public ReportCountry resolve(String requestedCode, String cookieCode, Locale requestedLanguage) {
        return ReportCountry.of(requestedCode)
                .or(() -> ReportCountry.of(cookieCode))
                .or(() -> fromLanguage(requestedLanguage))
                .orElse(defaultCountry);
    }

    private Optional<ReportCountry> fromLanguage(Locale requested) {
        if (requested == null) {
            return Optional.empty();
        }
        return ReportCountry.byRegion(requested.getCountry())
                .or(() -> byDefaultLanguage(requested.getLanguage()));
    }

    private Optional<ReportCountry> byDefaultLanguage(String language) {
        if (language == null || language.isEmpty()) {
            return Optional.empty();
        }
        for (Map.Entry<ReportCountry, Settings> entry : settings.entrySet()) {
            String defaultTag = entry.getValue().languages().get(0);
            if (Locale.forLanguageTag(defaultTag).getLanguage().equals(language)) {
                return Optional.of(entry.getKey());
            }
        }
        return Optional.empty();
    }

    /** Effective per-country settings after defaults and overrides are merged. */
    private record Settings(List<String> languages, String datePattern,
                            ReportCountry.Calendar calendar, String numberLocale) {
    }
}
