package com.creed.simple.lb;

import io.micrometer.observation.ObservationRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.cloud.client.loadbalancer.LoadBalancerInterceptor;
import org.springframework.http.client.ClientHttpRequestFactory;
import org.springframework.http.client.ClientHttpRequestInterceptor;
import org.springframework.web.client.RestTemplate;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

/**
 * Unit tests for {@link LoadBalancedRestTemplateConfiguration}: the business template wires the
 * interceptors in explicit execution order — {@code [loadBalancerInterceptor, auditInterceptor]}, index
 * 0 outermost so the LB rewrites the service-id before audit sees the resolved host — while the
 * health-check template carries no interceptors.
 */
class LoadBalancedRestTemplateConfigurationTest {

    private final LoadBalancedRestTemplateConfiguration config = new LoadBalancedRestTemplateConfiguration();

    @Test
    void clusterTemplateOrdersLbInterceptorOutermostThenAudit() {
        ClientHttpRequestFactory factory = mock(ClientHttpRequestFactory.class);
        LoadBalancerInterceptor lb = mock(LoadBalancerInterceptor.class);
        LoadBalancerAuditInterceptor audit = new LoadBalancerAuditInterceptor();

        RestTemplate template = config.clusterRestTemplate(factory, lb, audit, ObservationRegistry.NOOP);

        List<ClientHttpRequestInterceptor> interceptors = template.getInterceptors();
        assertThat(interceptors).containsExactly(lb, audit);
    }

    @Test
    void healthCheckTemplateHasNoInterceptors() {
        ClientHttpRequestFactory factory = mock(ClientHttpRequestFactory.class);

        RestTemplate template = config.healthCheckRestTemplate(factory, ObservationRegistry.NOOP);

        assertThat(template.getInterceptors()).isEmpty();
        assertThat(template.getRequestFactory()).isSameAs(factory);
    }
}
