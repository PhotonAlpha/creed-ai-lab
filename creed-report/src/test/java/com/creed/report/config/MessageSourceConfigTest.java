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
