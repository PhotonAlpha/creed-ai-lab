package com.creed.simple.lb;

import org.springframework.boot.context.properties.bind.DefaultValue;

import java.time.Duration;

/**
 * The full set of tunables for one HTTP client "stack" (pool + request factory): pool sizing plus the
 * four timeouts. One instance backs the business/cluster pool ({@code creed.partner.http.*}), another the
 * health-check pool ({@code creed.partner.health-check.http.*}) — see {@link PartnerProps}.
 *
 * <p>The {@link DefaultValue defaults} here mirror the historical business-pool {@code @Value} fallbacks.
 * The health-check pool's smaller/tighter values live in {@code application.yml} so both stacks read from
 * a single, type-safe place instead of scattered {@code @Value} strings.
 */
public record HttpPoolProperties(
        @DefaultValue("50") int maxTotal,
        @DefaultValue("20") int maxPerRoute,
        @DefaultValue("5s") Duration connectTimeout,
        @DefaultValue("10s") Duration socketTimeout,
        @DefaultValue("3s") Duration connectionRequestTimeout,
        @DefaultValue("10s") Duration responseTimeout) {
}
