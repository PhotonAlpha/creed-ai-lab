package com.creed.resource.lbhealth.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

import java.time.Duration;

/**
 * Settings of this lab that Spring Cloud LoadBalancer itself does not have. Everything the
 * health-check supplier reads ({@code interval}, {@code refetch-instances-interval}, ...) stays under
 * {@code spring.cloud.loadbalancer.health-check.*} - only the probe client and the debug switch live here.
 *
 * @param healthCheck the HTTP client the probes use
 * @param debug       observability switch ({@code [REFETCH]} / {@code [HC]} / {@code [HC-STREAM]} logs)
 * @param target      whether this app also serves the target instances on extra connectors
 */
@ConfigurationProperties("creed.lb-health")
public record LbHealthProperties(
        @DefaultValue HealthCheck healthCheck,
        @DefaultValue Debug debug,
        @DefaultValue Target target) {

    /**
     * @param mode           {@code blocking} (RestTemplate, serial probes) or {@code webclient}
     *                       (WebClient, concurrent probes)
     * @param connectTimeout TCP connect timeout of one probe
     * @param readTimeout    response timeout of one probe
     */
    public record HealthCheck(
            @DefaultValue("blocking") Mode mode,
            @DefaultValue("2s") Duration connectTimeout,
            @DefaultValue("3s") Duration readTimeout) {

        /** Worst case of a single probe: it can spend the whole connect timeout and then the whole read timeout. */
        public Duration probeTimeout() {
            return connectTimeout.plus(readTimeout);
        }
    }

    public enum Mode { BLOCKING, WEBCLIENT }

    public record Debug(@DefaultValue("false") boolean enabled) {
    }

    /** @param embedded open one extra HTTP connector per localhost instance of the target service */
    public record Target(@DefaultValue("true") boolean embedded) {
    }
}
