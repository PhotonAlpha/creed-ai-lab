package com.creed.simple.lb;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Type-safe binding for the partner outbound-HTTP configuration under {@code creed.partner.*}. Replaces
 * the dozen scattered {@code @Value("${creed.partner...}")} injections that every pool/request-factory
 * bean used to repeat (a copy-paste hazard when a new client stack was added).
 *
 * <ul>
 *   <li>{@code creed.partner.client-bundle} — the mTLS SSL bundle shared by both pools.</li>
 *   <li>{@code creed.partner.http.*} — the business/cluster pool ({@link #http()}).</li>
 *   <li>{@code creed.partner.health-check.http.*} — the health-check pool ({@link #healthCheck()}).</li>
 * </ul>
 */
@ConfigurationProperties("creed.partner")
public record PartnerProps(
        @DefaultValue("creed-partner-server") String clientBundle,
        @DefaultValue HttpPoolProperties http,
        @DefaultValue HealthCheck healthCheck) {

    /** Nested holder so {@code creed.partner.health-check.http.*} keeps its existing key shape. */
    public record HealthCheck(@DefaultValue HttpPoolProperties http) {
    }
}
