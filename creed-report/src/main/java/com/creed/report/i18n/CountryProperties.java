package com.creed.report.i18n;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

/**
 * Externalized overrides for the country axis, on top of the {@link ReportCountry} built-in
 * defaults. Every field is optional: what is not set here keeps the enum's value, so configuration
 * only ever states the difference.
 *
 * <pre>
 * creed:
 *   report:
 *     country:
 *       default-country: my
 *       profiles:
 *         my:
 *           date-pattern: "dd-MM-yyyy HH:mm"
 * </pre>
 *
 * <p>The {@code profiles} keys are {@link ReportCountry} constants, matched by Boot's relaxed
 * binding ({@code global}/{@code th}/{@code my}/{@code vn}).
 */
@ConfigurationProperties(prefix = "creed.report.country")
public class CountryProperties {

    /** Country used when the request carries no usable country hint at all. */
    private ReportCountry defaultCountry = ReportCountry.GLOBAL;

    /** Query parameter that switches country, mirroring {@code ?lang=} for language. */
    private String paramName = "country";

    /** Cookie the chosen country is remembered in, mirroring the locale cookie. */
    private String cookieName = "creed-report-country";

    /** Lifetime of the country and locale cookies; a session cookie when unset. */
    private Duration cookieMaxAge = Duration.ofDays(30);

    /** Per-country overrides; absent entries and absent fields keep the enum defaults. */
    private Map<ReportCountry, Profile> profiles = new EnumMap<>(ReportCountry.class);

    public ReportCountry getDefaultCountry() {
        return defaultCountry;
    }

    public void setDefaultCountry(ReportCountry defaultCountry) {
        this.defaultCountry = defaultCountry;
    }

    public String getParamName() {
        return paramName;
    }

    public void setParamName(String paramName) {
        this.paramName = paramName;
    }

    public String getCookieName() {
        return cookieName;
    }

    public void setCookieName(String cookieName) {
        this.cookieName = cookieName;
    }

    public Duration getCookieMaxAge() {
        return cookieMaxAge;
    }

    public void setCookieMaxAge(Duration cookieMaxAge) {
        this.cookieMaxAge = cookieMaxAge;
    }

    public Map<ReportCountry, Profile> getProfiles() {
        return profiles;
    }

    public void setProfiles(Map<ReportCountry, Profile> profiles) {
        this.profiles = profiles;
    }

    /** Overrides for one country. A {@code null} field means "keep the enum default". */
    public static class Profile {

        private List<String> languages;

        private String datePattern;

        private ReportCountry.Calendar calendar;

        private String numberLocale;

        public List<String> getLanguages() {
            return languages;
        }

        public void setLanguages(List<String> languages) {
            this.languages = languages;
        }

        public String getDatePattern() {
            return datePattern;
        }

        public void setDatePattern(String datePattern) {
            this.datePattern = datePattern;
        }

        public ReportCountry.Calendar getCalendar() {
            return calendar;
        }

        public void setCalendar(ReportCountry.Calendar calendar) {
            this.calendar = calendar;
        }

        public String getNumberLocale() {
            return numberLocale;
        }

        public void setNumberLocale(String numberLocale) {
            this.numberLocale = numberLocale;
        }
    }
}
