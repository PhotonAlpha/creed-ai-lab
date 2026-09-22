package com.creed.jasper.i18n;

import java.util.Locale;

/**
 * The languages this module renders in — the one presentation axis it has.
 *
 * <p>{@code creed-report} varies on <b>two</b> axes, country × language, because its documents
 * carry a country edition (palette, row density, the Buddhist calendar, a per-country content
 * fragment). The approval-status listing is the one document there that is <i>fixed brand chrome</i>
 * — the statement palette is not delegated to a country sheet — so the only axis it actually varies
 * on is the language: the text, the faces, and whether a caption is bold. This module carries that
 * axis and no other, which is why there is an enum of six languages here and no {@code Country}.
 *
 * <p><b>Why an enum at all, rather than passing the request locale straight through.</b>
 * JasperReports resolves a report's {@code resourceBundle} with a plain
 * {@link java.util.ResourceBundle#getBundle(String, Locale)}, and that call falls back to the
 * <b>JVM's default locale</b> before it falls back to the base bundle. On a machine whose default
 * is {@code zh_CN}, an {@code Accept-Language: fr} request would therefore render in Chinese.
 * Normalising to a language this module actually ships means every lookup is an exact match and
 * that fallback is never reached. ({@code creed-report} rules the same trap out with
 * {@code fallbackToSystemLocale=false} on its {@code MessageSource}; Jasper has no such switch.)
 */
public enum ReportLanguage {

    /** The default, and the bundle every other one is layered on. */
    EN(Locale.ENGLISH),
    ZH_CN(Locale.forLanguageTag("zh-CN")),
    ZH_TW(Locale.forLanguageTag("zh-TW")),
    TH(Locale.forLanguageTag("th")),
    MS(Locale.forLanguageTag("ms")),
    VI(Locale.forLanguageTag("vi"));

    private final Locale locale;

    ReportLanguage(Locale locale) {
        this.locale = locale;
    }

    /** The locale a fill runs in: {@code REPORT_LOCALE}, the bundle key and the font's locale. */
    public Locale locale() {
        return locale;
    }

    /**
     * Resolves a requested locale to one this module ships.
     *
     * <p>An exact tag wins ({@code zh-TW} stays Traditional), then the bare language
     * ({@code zh-SG} → {@code zh-CN}, {@code th-TH} → {@code th}), then English. Region is
     * otherwise ignored: {@code en-MY} and {@code en-GB} are the same document here, because
     * nothing in it varies by region once the country axis is gone.
     */
    public static ReportLanguage of(Locale requested) {
        if (requested == null) {
            return EN;
        }
        String tag = requested.toLanguageTag();
        for (ReportLanguage language : values()) {
            if (language.locale.toLanguageTag().equalsIgnoreCase(tag)) {
                return language;
            }
        }
        String base = requested.getLanguage();
        for (ReportLanguage language : values()) {
            if (language.locale.getLanguage().equals(base)) {
                return language;
            }
        }
        return EN;
    }

    /**
     * Whether dates are printed in the Buddhist era.
     *
     * <p>In creed-report this is a property of the <b>country</b> — {@code ?country=th&lang=en}
     * still shows 2569 — and it stays keyed on the language here only because this module has no
     * country to key it on. Worth knowing before comparing a Thai PDF from the two side by side:
     * they agree for {@code th}, and this one shows 2026 where creed-report's Thai <i>edition</i>
     * read in English shows 2569.
     */
    public boolean isBuddhistCalendar() {
        return this == TH;
    }
}
