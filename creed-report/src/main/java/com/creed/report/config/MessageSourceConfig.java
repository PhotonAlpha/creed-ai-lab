package com.creed.report.config;

import org.springframework.context.MessageSource;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.support.ResourceBundleMessageSource;

import java.nio.charset.StandardCharsets;

/**
 * One {@link MessageSource} bean per business domain, composed into a single lookup chain.
 *
 * <p>Bundles live in {@code classpath:/i18n/} as {@code <domain>-messages[_locale].properties}.
 * Each domain owns a key prefix ({@code report.*}/{@code pdf.*}/{@code html.*} vs
 * {@code payment.*}) so the domains can never shadow each other; the chain order below is only
 * the tie-breaker if they ever do.
 *
 * <p>Composition is Spring's parent chain, not a merged basename list:
 * {@link org.springframework.context.support.AbstractMessageSource} resolves a code against its
 * own bundles first and delegates to {@code parentMessageSource} on a miss. Adding a domain means
 * adding a {@code @Bean} and linking it into the chain — templates keep using plain {@code #{...}}
 * and never learn which bundle a key came from.
 *
 * <p>Two consequences of defining the bean here: the bean <b>must</b> be named
 * {@code messageSource} (that is the name {@code ApplicationContext} and Thymeleaf look up, and
 * the name {@code MessageSourceAutoConfiguration} backs off on), and Boot's
 * {@code spring.messages.*} properties are inert as a result — encoding and the
 * system-locale-fallback switch are set here instead.
 *
 * <p>{@code fallbackToSystemLocale=false} matters for the PDF/HTML exports: an unknown locale must
 * land on the English default bundle rather than the JVM's locale, since the locale also picks the
 * CSS font stack ({@code pdf.font.family}) that the export renders with.
 */
@Configuration
public class MessageSourceConfig {

    /** Payment domain ({@code payment.*}) — tail of the chain. */
    @Bean
    public MessageSource paymentMessageSource() {
        return bundle("i18n/payment-messages");
    }

    /**
     * Report domain ({@code report.*}, {@code pdf.*}, {@code html.*}) and head of the chain: it is
     * the {@code messageSource} bean the container resolves everything through, falling back to
     * {@link #paymentMessageSource()}.
     */
    @Bean
    public MessageSource messageSource() {
        ResourceBundleMessageSource source = bundle("i18n/report-messages");
        // Direct call, not an injected parameter: @Configuration is CGLIB-proxied, so this
        // returns the singleton rather than a second copy of the bundle.
        source.setParentMessageSource(paymentMessageSource());
        return source;
    }

    private ResourceBundleMessageSource bundle(String basename) {
        ResourceBundleMessageSource source = new ResourceBundleMessageSource();
        source.setBasename(basename);
        source.setDefaultEncoding(StandardCharsets.UTF_8.name());
        source.setFallbackToSystemLocale(false);
        return source;
    }
}
