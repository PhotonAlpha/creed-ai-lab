package com.creed.simple.web.logbook;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.util.AntPathMatcher;
import org.springframework.util.StringUtils;
import org.zalando.logbook.HttpRequest;
import org.zalando.logbook.Strategy;

import java.util.List;
import java.util.Locale;
import java.util.function.Predicate;

/**
 * Production-tuning extension points for the shared Zalando Logbook instance — used by both the
 * inbound servlet filter and the two hc5 {@code LogbookHttpExecHandler}s ({@code CamelConfig} /
 * {@code CookieRelayRestClientConfiguration}). Both beans below hook into
 * {@code LogbookAutoConfiguration}'s {@code @ConditionalOnMissingBean} extension points, so defining
 * them here backs off every property-driven default the auto-configuration would otherwise create.
 */
@Slf4j
@Configuration(proxyBeanMethods = false)
public class LogbookAuditConfiguration {

    private static final AntPathMatcher PATH_MATCHER = new AntPathMatcher();

    /**
     * Replaces {@code LogbookAutoConfiguration.requestCondition()} (the default {@code $ -> true}) —
     * matched by <em>bean name</em>, not type, so this method must stay named exactly
     * {@code requestCondition}. {@code logbook.predicate.include}/{@code exclude} still apply on top of
     * whatever this returns (the auto-configuration ANDs/ORs them in afterwards), so path-based
     * excludes can keep using that property instead of {@code creed.logbook.skip-paths} if preferred.
     *
     * <p>This runs as the top-level {@code Logbook.condition()} — i.e. <em>before</em> the
     * {@link Strategy}/body-filter pipeline even starts, so a request that fails either gate here never
     * pays for body buffering at all:
     * <ul>
     *   <li>{@code creed.logbook.skip-paths} — Ant-style patterns for high-traffic/low-value paths
     *       (e.g. polling/bulk endpoints) that shouldn't be audited at all;</li>
     *   <li>only requests whose {@code Content-Type} matches one of
     *       {@code creed.logbook.allowed-content-types} are audited (default: {@code application/json}
     *       only); requests with no body at all (GET/DELETE/health probes) always pass. An empty list
     *       disables this gate entirely (path-based filtering only).</li>
     * </ul>
     */
    @Bean("requestCondition")
    @ConditionalOnProperty(name = "creed.logbook.request-condition.enabled", havingValue = "true", matchIfMissing = true)
    public Predicate<HttpRequest> requestCondition(
            @Value("${creed.logbook.skip-paths:}") List<String> skipPaths,
            @Value("${creed.logbook.allowed-content-types:application/json}") List<String> allowedContentTypes) {
        List<String> normalizedAllowed = allowedContentTypes.stream()
                .map(type -> type.toLowerCase(Locale.ROOT))
                .toList();
        return request -> {
            String path = request.getPath();
            for (String pattern : skipPaths) {
                if (PATH_MATCHER.match(pattern, path)) {
                    return false;
                }
            }
            if (normalizedAllowed.isEmpty()) {
                return true;
            }
            String contentType = request.getContentType();
            if (!StringUtils.hasText(contentType)) {
                return true;
            }
            String lowerContentType = contentType.toLowerCase(Locale.ROOT);
            return normalizedAllowed.stream().anyMatch(lowerContentType::startsWith);
        };
    }

    /**
     * The module's Logbook {@link Strategy} (see {@link ContentAwareBodyStrategy}): gates the response
     * body by content-type — the response-side counterpart of {@link #requestCondition} that
     * {@code Logbook.condition()} structurally cannot cover — and, optionally, by status.
     *
     * <p>Registered by default ({@code matchIfMissing = true}), so it takes over from Logbook's
     * property-driven strategy selection (the {@code logbook.strategy} property no longer applies while
     * this bean exists); set {@code creed.logbook.strategy.enabled=false} to fall back to the built-ins.
     * With the content-type list empty <em>and</em> {@code body-on-error} off it behaves exactly like
     * {@code DefaultStrategy}, so "always on" is a safe default.
     *
     * <ul>
     *   <li>{@code creed.logbook.allowed-content-types} — same list as the request gate; here it decides
     *       whether the <em>response</em> body is buffered (default {@code application/json} only);</li>
     *   <li>{@code creed.logbook.body-on-error.*} — when enabled, additionally keep the response body
     *       only for {@code status >= minimum-status} (off by default so dev/test see full bodies).</li>
     * </ul>
     */
    @Bean
    @ConditionalOnProperty(name = "creed.logbook.strategy.enabled", havingValue = "true", matchIfMissing = true)
    public Strategy logbookStrategy(
            @Value("${creed.logbook.allowed-content-types:application/json}") List<String> allowedContentTypes,
            @Value("${creed.logbook.body-on-error.enabled:false}") boolean bodyOnError,
            @Value("${creed.logbook.body-on-error.minimum-status:500}") int minimumStatus) {
        List<String> normalizedAllowed = allowedContentTypes.stream()
                .map(type -> type.toLowerCase(Locale.ROOT))
                .toList();
        log.info("Logbook Strategy active: response body buffered only when Content-Type matches {}{}",
                normalizedAllowed.isEmpty() ? "(any)" : normalizedAllowed,
                bodyOnError ? " and status >= " + minimumStatus : "");
        return new ContentAwareBodyStrategy(normalizedAllowed, bodyOnError, minimumStatus);
    }
}
