package com.creed.report.config;

import org.junit.jupiter.api.Test;
import org.springframework.context.MessageSource;
import org.springframework.context.NoSuchMessageException;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.support.AbstractApplicationContext;

import java.util.Locale;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Pins the domain-bundle chain: both domains resolve through one MessageSource, in every locale. */
class MessageSourceConfigTest {

    private final MessageSource messages = new MessageSourceConfig().messageSource();

    @Test
    void resolvesReportKeysFromTheHeadOfTheChain() {
        assertThat(messages.getMessage("report.topology", null, Locale.ENGLISH))
                .isEqualTo("Server Topology");
        assertThat(messages.getMessage("report.topology", null, Locale.SIMPLIFIED_CHINESE))
                .isEqualTo("服务器拓扑");
        assertThat(messages.getMessage("report.topology", null, Locale.TRADITIONAL_CHINESE))
                .isEqualTo("伺服器拓撲");
    }

    @Test
    void resolvesPaymentKeysThroughTheParent() {
        // report-messages has no payment.* key, so this can only come from the chained parent.
        assertThat(messages.getMessage("payment.status.captured", null, Locale.ENGLISH))
                .isEqualTo("Captured");
        assertThat(messages.getMessage("payment.status.captured", null, Locale.SIMPLIFIED_CHINESE))
                .isEqualTo("已扣款");
        assertThat(messages.getMessage("payment.status.captured", null, Locale.TRADITIONAL_CHINESE))
                .isEqualTo("已扣款");
    }

    @Test
    void unknownLocaleFallsBackToTheEnglishBundleInBothDomains() {
        // fallbackToSystemLocale=false: French must land on the default bundle, not the JVM locale.
        Locale french = Locale.forLanguageTag("fr");
        assertThat(messages.getMessage("report.topology", null, french)).isEqualTo("Server Topology");
        assertThat(messages.getMessage("payment.status.captured", null, french)).isEqualTo("Captured");
    }

    @Test
    void bareChineseResolvesForClientsSendingAcceptLanguageZh() {
        Locale zh = Locale.forLanguageTag("zh");
        assertThat(messages.getMessage("report.topology", null, zh)).isEqualTo("服务器拓扑");
        assertThat(messages.getMessage("payment.status.captured", null, zh)).isEqualTo("已扣款");
    }

    @Test
    void countryKeysComeFromTheRegionBundleAndFallThroughForEverythingElse() {
        // The whole point of folding the country into the locale's region subtag: a country bundle
        // carries only what differs, and the language bundle underneath supplies the rest.
        assertThat(messages.getMessage("report.country.name", null, Locale.of("th", "TH")))
                .isEqualTo("ประเทศไทย");
        assertThat(messages.getMessage("report.country.name", null, Locale.of("en", "TH")))
                .isEqualTo("Thailand");
        assertThat(messages.getMessage("report.country.timezone", null, Locale.of("ms", "MY")))
                .isEqualTo("Asia/Kuala_Lumpur");
        // Not in any region bundle: th_TH -> th, and en_MY -> the English base bundle.
        assertThat(messages.getMessage("report.topology", null, Locale.of("th", "TH")))
                .isEqualTo("โครงสร้างเซิร์ฟเวอร์");
        assertThat(messages.getMessage("report.topology", null, Locale.of("en", "MY")))
                .isEqualTo("Server Topology");
    }

    @Test
    void aCountrylessLocaleGetsTheGlobalEdition() {
        assertThat(messages.getMessage("report.country.name", null, Locale.ENGLISH)).isEqualTo("Global");
        assertThat(messages.getMessage("report.country.name", null, Locale.SIMPLIFIED_CHINESE)).isEqualTo("全球");
    }

    @Test
    void theNewLanguagesResolveInBothDomains() {
        assertThat(messages.getMessage("report.topology", null, Locale.forLanguageTag("ms")))
                .isEqualTo("Topologi Pelayan");
        assertThat(messages.getMessage("report.topology", null, Locale.forLanguageTag("vi")))
                .isEqualTo("Sơ đồ Máy chủ");
        assertThat(messages.getMessage("payment.status.captured", null, Locale.forLanguageTag("th")))
                .isEqualTo("ตัดเงินแล้ว");
        assertThat(messages.getMessage("payment.status.captured", null, Locale.forLanguageTag("vi")))
                .isEqualTo("Đã ghi nợ");
    }

    @Test
    void onlyThaiSwitchesThePdfFontStack() {
        // Flying Saucer has no per-glyph fallback, so the stack's first family must cover the
        // locale's script. Malay and Vietnamese are Latin and keep the default face.
        assertThat(messages.getMessage("pdf.font.family", null, Locale.of("th", "TH")))
                .startsWith("'Noto Sans Thai'");
        assertThat(messages.getMessage("pdf.font.family", null, Locale.of("ms", "MY")))
                .startsWith("'Noto Sans'");
        assertThat(messages.getMessage("pdf.font.family", null, Locale.of("vi", "VN")))
                .startsWith("'Noto Sans'");
    }

    @Test
    void unknownCodeStillFailsRatherThanEchoingTheKey() {
        assertThatThrownBy(() -> messages.getMessage("nope.not.a.key", null, Locale.ENGLISH))
                .isInstanceOf(NoSuchMessageException.class);
    }

    @Test
    void containerResolvesMessagesThroughThisConfigsBean() {
        // The bean must be named 'messageSource' or the context silently ignores it.
        try (AnnotationConfigApplicationContext context =
                     new AnnotationConfigApplicationContext(MessageSourceConfig.class)) {
            assertThat(context.containsBean(AbstractApplicationContext.MESSAGE_SOURCE_BEAN_NAME)).isTrue();
            assertThat(context.getMessage("report.topology", null, Locale.SIMPLIFIED_CHINESE))
                    .isEqualTo("服务器拓扑");
            assertThat(context.getMessage("payment.status.captured", null, Locale.SIMPLIFIED_CHINESE))
                    .isEqualTo("已扣款");
        }
    }
}
