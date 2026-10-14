package com.creed.simple.lb;

import org.springframework.boot.context.properties.bind.DefaultValue;

import java.time.Duration;

/**
 * The full set of tunables for one HTTP client "stack" (pool + request factory): pool sizing, the four
 * timeouts, and the five connection-lifecycle knobs. One instance backs the business/cluster pool
 * ({@code creed.partner.http.*}), another the health-check pool ({@code creed.partner.health-check.http.*})
 * — see {@link PartnerProps} — and a third the camel-http pool ({@code creed.camel.http.*}, bound in
 * {@code CamelConfig}).
 *
 * <p>The {@link DefaultValue defaults} here mirror the historical business-pool {@code @Value} fallbacks.
 * The health-check pool's smaller/tighter values live in {@code application.yml} so both stacks read from
 * a single, type-safe place instead of scattered {@code @Value} strings.
 *
 * <p>Lifecycle knobs (HttpClient 5.5 leaves all of these unbounded or unenforced by default):
 * <ul>
 *   <li>{@code timeToLive} — hard cap on a connection's age, checked at lease. Without it a connection
 *       lives forever and stays pinned to whichever instance it first hit, so new/recovered instances
 *       never get rebalanced onto.</li>
 *   <li>{@code validateAfterInactivity} — idle longer than this, a leased connection gets a stale check
 *       first. Catches a server-side FIN, NOT a NAT/firewall silently dropping the flow.</li>
 *   <li>{@code keepAlive} — upper bound on how long a returned connection is considered reusable. Tomcat
 *       downstreams send no {@code Keep-Alive} header, so HttpClient would otherwise assume 3 min — longer
 *       than Tomcat's own 60 s idle close. Must stay below the shortest idle timeout on the path.</li>
 *   <li>{@code maxIdleTime} / {@code evictionInterval} — the background evictor's {@code closeIdle} cut-off
 *       and sweep period; without it idle dead sockets are only noticed when someone leases them.</li>
 * </ul>
 */
public record HttpPoolProperties(
        @DefaultValue("50") int maxTotal,
        @DefaultValue("20") int maxPerRoute,
        @DefaultValue("5s") Duration connectTimeout,
        @DefaultValue("10s") Duration socketTimeout,
        @DefaultValue("3s") Duration connectionRequestTimeout,
        @DefaultValue("10s") Duration responseTimeout,
        @DefaultValue("5m") Duration timeToLive,
        @DefaultValue("2s") Duration validateAfterInactivity,
        @DefaultValue("30s") Duration keepAlive,
        @DefaultValue("30s") Duration maxIdleTime,
        @DefaultValue("5s") Duration evictionInterval) {

    /** Pool sizing + timeouts only; the lifecycle knobs take their {@link DefaultValue defaults}. */
    public static HttpPoolProperties of(int maxTotal, int maxPerRoute, Duration connectTimeout,
                                        Duration socketTimeout, Duration connectionRequestTimeout,
                                        Duration responseTimeout) {
        return new HttpPoolProperties(maxTotal, maxPerRoute, connectTimeout, socketTimeout,
                connectionRequestTimeout, responseTimeout, Duration.ofMinutes(5), Duration.ofSeconds(2),
                Duration.ofSeconds(30), Duration.ofSeconds(30), Duration.ofSeconds(5));
    }
}
