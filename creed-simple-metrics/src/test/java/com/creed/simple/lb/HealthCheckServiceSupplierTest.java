package com.creed.simple.lb;

import org.junit.jupiter.api.Test;
import org.springframework.cloud.client.DefaultServiceInstance;
import org.springframework.cloud.client.ServiceInstance;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.client.RestClient;

import java.net.URI;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Unit tests for the {@link HealthCheckServiceSupplier} default methods: URI reconstruction (leading-slash
 * tolerance, empty path) and the {@code isAlive} probe verdict — {@code 200 OK} → alive, any other status
 * or a thrown exception → not alive (the exception is caught and logged, never propagated).
 */
class HealthCheckServiceSupplierTest {

    /** Concrete no-op implementation exposing the interface's default methods for testing. */
    private final HealthCheckServiceSupplier supplier = new HealthCheckServiceSupplier() {
    };

    private static ServiceInstance instance() {
        return new DefaultServiceInstance("catalog-1", "catalog-resource", "localhost", 8081, true);
    }

    // ---------------------------------------------------------------- healthCheckUri

    @Test
    void healthCheckUriAppendsPathWithLeadingSlash() {
        URI uri = supplier.healthCheckUri(instance(), "/actuator/health");
        assertThat(uri).hasToString("https://localhost:8081/actuator/health");
    }

    @Test
    void healthCheckUriToleratesPathWithoutLeadingSlash() {
        URI uri = supplier.healthCheckUri(instance(), "actuator/health");
        assertThat(uri).hasToString("https://localhost:8081/actuator/health");
    }

    @Test
    void healthCheckUriWithBlankPathReturnsTheInstanceUri() {
        URI uri = supplier.healthCheckUri(instance(), "  ");
        assertThat(uri).hasToString("https://localhost:8081");
    }

    // ---------------------------------------------------------------- isAlive

    @SuppressWarnings({"unchecked", "rawtypes"})
    private static RestClient restClientReturning(HttpStatus status) {
        RestClient restClient = mock(RestClient.class);
        RestClient.RequestHeadersUriSpec uriSpec = mock(RestClient.RequestHeadersUriSpec.class);
        RestClient.ResponseSpec responseSpec = mock(RestClient.ResponseSpec.class);
        when(restClient.get()).thenReturn(uriSpec);
        when(uriSpec.uri(any(URI.class))).thenReturn(uriSpec);
        when(uriSpec.retrieve()).thenReturn(responseSpec);
        when(responseSpec.toBodilessEntity()).thenReturn(ResponseEntity.status(status).build());
        return restClient;
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private static RestClient restClientThrowing(RuntimeException ex) {
        RestClient restClient = mock(RestClient.class);
        RestClient.RequestHeadersUriSpec uriSpec = mock(RestClient.RequestHeadersUriSpec.class);
        RestClient.ResponseSpec responseSpec = mock(RestClient.ResponseSpec.class);
        when(restClient.get()).thenReturn(uriSpec);
        when(uriSpec.uri(any(URI.class))).thenReturn(uriSpec);
        when(uriSpec.retrieve()).thenReturn(responseSpec);
        when(responseSpec.toBodilessEntity()).thenThrow(ex);
        return restClient;
    }

    @Test
    void isAliveTrueOnHttp200() {
        Boolean alive = supplier.isAlive(instance(), "/actuator/health", restClientReturning(HttpStatus.OK)).block();
        assertThat(alive).isTrue();
    }

    @Test
    void isAliveFalseOnNon200() {
        Boolean alive = supplier.isAlive(instance(), "/actuator/health",
                restClientReturning(HttpStatus.SERVICE_UNAVAILABLE)).block();
        assertThat(alive).isFalse();
    }

    @Test
    void isAliveFalseAndSwallowsProbeException() {
        Boolean alive = supplier.isAlive(instance(), "/actuator/health",
                restClientThrowing(new RuntimeException("connection refused"))).block();
        // The probe exception is caught and logged; the instance is simply marked DOWN.
        assertThat(alive).isFalse();
    }
}
