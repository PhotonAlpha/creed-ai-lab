package com.creed.report.i18n;

import org.junit.jupiter.api.Test;

import java.util.Locale;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The PDF stylesheet's three layers — shared base, country edition, locale overlay — and the two
 * things that can only go wrong between them: the order they are concatenated in, and which locale
 * an overlay is found for.
 *
 * <p>The marker each file is recognised by here is its own header comment, which is also what a
 * reader sees first when dumping {@code ${pdfCss}} out of a preview; a rule would identify the
 * layer just as well but would move every time the look is tuned.
 */
class CountryStylesTest {

    /** Present in the header of both {@code locale/<tag>/report-pdf.css} files. */
    private static final String OVERLAY = "LOCALE overlay";

    private final CountryStyles styles = new CountryStyles();

    @Test
    void aLocaleWithNoOverlayRendersTheBaseAndCountrySheetsUnchanged() {
        // The normal case, and deliberately not a failure the way a missing country sheet is:
        // English, Malay and Vietnamese need no adjustment, so they ship no file.
        assertThat(styles.localeKey(Locale.ENGLISH)).isEmpty();
        assertThat(styles.localeKey(Locale.forLanguageTag("ms-MY"))).isEmpty();
        assertThat(styles.pdf(ReportCountry.MY, Locale.forLanguageTag("ms-MY")))
                .contains(".total-badge")          // base
                .contains("#010066")               // country/my
                .doesNotContain(OVERLAY);
    }

    @Test
    void anOverlayIsKeyedOnTheLanguage() {
        // th-TH, and any other country reading in Thai, resolve the one locale/th directory --
        // which is the difference between this axis and country/th/style-pdf.css.
        assertThat(styles.localeKey(Locale.forLanguageTag("th-TH"))).isEqualTo("th");
        assertThat(styles.localeKey(Locale.forLanguageTag("th"))).isEqualTo("th");
        // zh-CN and zh-TW share locale/zh; a rule only one of them needs would go in a
        // locale/zh-tw/ of its own, which the walk reaches first.
        assertThat(styles.localeKey(Locale.forLanguageTag("zh-CN"))).isEqualTo("zh");
        assertThat(styles.localeKey(Locale.forLanguageTag("zh-TW"))).isEqualTo("zh");
        // A locale no country offers is not an error either -- it just has nothing to add.
        assertThat(styles.localeKey(Locale.FRENCH)).isEmpty();
        assertThat(styles.localeKey(null)).isEmpty();
    }

    @Test
    void theOverlayIsConcatenatedLast() {
        // Last is the whole point: the layer carries typography that follows the script, and a
        // country edition appended after it could silently take the weight back.
        String thai = styles.pdf(ReportCountry.TH, Locale.forLanguageTag("th-TH"));
        assertThat(thai).contains(".total-badge").contains("#a51931").contains(OVERLAY);
        assertThat(thai.indexOf(OVERLAY))
                .as("locale overlay after the country sheet")
                .isGreaterThan(thai.indexOf("#a51931"));
        assertThat(thai.indexOf("#a51931"))
                .as("country sheet after the base")
                .isGreaterThan(thai.indexOf(".total-badge"));
    }

    @Test
    void theSameClassChangesWeightWithTheScript() {
        // The reason this layer exists at all, and the one thing a message key cannot say: at
        // 8.5pt a Thai caption is unreadable bold and a Han one is invisible without it.
        assertThat(styles.pdf(ReportCountry.TH, Locale.forLanguageTag("th-TH")))
                .contains(".statement-footer-title,");   // only the overlay groups it
        assertThat(styles.pdf(ReportCountry.GLOBAL, Locale.forLanguageTag("zh-CN")))
                .contains(".criteria-label { font-weight: bold; }");
        // The Latin editions get neither -- the base sheet's weights stand.
        assertThat(styles.pdf(ReportCountry.GLOBAL, Locale.ENGLISH))
                .doesNotContain(".criteria-label { font-weight: bold; }");
    }

    @Test
    void theBrowserSheetIsStillTheCountrysAlone() {
        // Unchanged by the locale axis: the live page links report.css itself, so this half has
        // no base to prepend and no overlay to append.
        assertThat(styles.browser(ReportCountry.VN)).contains("#da251d").doesNotContain(OVERLAY);
    }
}
