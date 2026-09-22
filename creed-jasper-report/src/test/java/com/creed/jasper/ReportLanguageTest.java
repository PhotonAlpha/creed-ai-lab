package com.creed.jasper;

import com.creed.jasper.i18n.ExportTimestamp;
import com.creed.jasper.i18n.ReportLanguage;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.Locale;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The language axis: how a request locale is normalised, and what that normalisation is for.
 */
class ReportLanguageTest {

    @Test
    void anExactTagWinsOverTheBareLanguage() {
        // Traditional must not collapse onto Simplified -- it would render in the wrong Han face
        // and read as merely odd rather than as broken.
        assertThat(ReportLanguage.of(Locale.forLanguageTag("zh-TW"))).isEqualTo(ReportLanguage.ZH_TW);
        assertThat(ReportLanguage.of(Locale.forLanguageTag("zh-CN"))).isEqualTo(ReportLanguage.ZH_CN);
    }

    @Test
    void aRegionThisModuleDoesNotShipFallsBackToItsLanguage() {
        assertThat(ReportLanguage.of(Locale.forLanguageTag("th-TH"))).isEqualTo(ReportLanguage.TH);
        assertThat(ReportLanguage.of(Locale.forLanguageTag("ms-MY"))).isEqualTo(ReportLanguage.MS);
        // Region is otherwise ignored: nothing in this document varies by region once the country
        // axis creed-report carries is gone.
        assertThat(ReportLanguage.of(Locale.forLanguageTag("en-MY"))).isEqualTo(ReportLanguage.EN);
        assertThat(ReportLanguage.of(Locale.forLanguageTag("zh-SG"))).isEqualTo(ReportLanguage.ZH_CN);
    }

    @Test
    void anythingElseIsEnglishRatherThanWhateverTheJvmDefaultIs() {
        // The whole reason this enum exists. JasperReports resolves its resourceBundle with a
        // plain ResourceBundle.getBundle, which falls back to the JVM's default locale BEFORE the
        // base bundle -- so on a zh_CN machine an unnormalised Accept-Language: fr would render a
        // Chinese document. Normalising first means every lookup is an exact match.
        assertThat(ReportLanguage.of(Locale.FRENCH)).isEqualTo(ReportLanguage.EN);
        assertThat(ReportLanguage.of(Locale.forLanguageTag("de-DE"))).isEqualTo(ReportLanguage.EN);
        assertThat(ReportLanguage.of(null)).isEqualTo(ReportLanguage.EN);
    }

    @Test
    void thaiDatesAreInTheBuddhistEraAndNothingElseIs() {
        LocalDateTime when = LocalDateTime.of(2026, 9, 11, 17, 52, 27);

        assertThat(ExportTimestamp.date(when, ReportLanguage.TH)).contains("2569");
        assertThat(ExportTimestamp.date(when, ReportLanguage.EN)).contains("2026").contains("Sep");
        assertThat(ExportTimestamp.date(when, ReportLanguage.VI)).contains("2026");
        // To the second, and in ASCII digits whatever the locale would otherwise use -- an
        // embedded face may not carry another numeral set at all.
        assertThat(ExportTimestamp.time(when, ReportLanguage.TH)).contains("17:52:27");
        assertThat(ExportTimestamp.time(when, ReportLanguage.EN)).contains("52:27");
    }
}
