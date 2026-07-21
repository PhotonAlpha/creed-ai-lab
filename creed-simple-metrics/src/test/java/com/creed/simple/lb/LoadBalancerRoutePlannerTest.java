package com.creed.simple.lb;

import org.apache.hc.client5.http.HttpRoute;
import org.apache.hc.client5.http.protocol.HttpClientContext;
import org.apache.hc.core5.http.HttpException;
import org.apache.hc.core5.http.HttpHost;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.cloud.client.DefaultServiceInstance;
import org.springframework.cloud.client.ServiceInstance;
import org.springframework.cloud.client.discovery.DiscoveryClient;
import org.springframework.cloud.client.loadbalancer.LoadBalancerClient;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link LoadBalancerRoutePlanner}: the service-id → LoadBalancer resolution path, the
 * direct fall-through for unknown hosts, the "no alive instance" failure, and the TLS-layering of the
 * resolved route (the {@code secure} flag that plain {@code HttpRoute(HttpHost)} would default to false).
 */
@ExtendWith(MockitoExtension.class)
class LoadBalancerRoutePlannerTest {

    @Mock
    private LoadBalancerClient loadBalancer;
    @Mock
    private DiscoveryClient discoveryClient;
    @InjectMocks
    private LoadBalancerRoutePlanner planner;

    private static ServiceInstance instance(boolean secure) {
        return new DefaultServiceInstance("catalog-1", "catalog-resource", "localhost", 8081, secure);
    }

    @Test
    void unknownServiceFallsThroughToTheDirectPlanner() throws Exception {
        when(discoveryClient.getServices()).thenReturn(List.of("catalog-resource"));
        HttpHost target = new HttpHost("https", "example.com", 8443);

        // The stock DefaultRoutePlanner reads request config off the context, so hand it a real one.
        HttpRoute route = planner.determineRoute(target, HttpClientContext.create());

        // Direct connection to the literal target — LoadBalancerClient is never consulted.
        assertThat(route.getTargetHost()).isEqualTo(target);
    }

    @Test
    void knownServiceIsResolvedThroughTheLoadBalancerAndLayeredSecureForHttps() throws Exception {
        when(discoveryClient.getServices()).thenReturn(List.of("catalog-resource"));
        when(loadBalancer.choose("catalog-resource")).thenReturn(instance(true));
        HttpHost target = new HttpHost("https", "catalog-resource", -1);

        HttpRoute route = planner.determineRoute(target, null);

        assertThat(route.getTargetHost()).isEqualTo(new HttpHost("https", "localhost", 8081));
        // The critical bit: TLS is layered on the resolved route (not sent as plaintext).
        assertThat(route.isSecure()).isTrue();
    }

    @Test
    void insecureInstanceResolvesToAPlainHttpRoute() throws Exception {
        when(discoveryClient.getServices()).thenReturn(List.of("catalog-resource"));
        when(loadBalancer.choose("catalog-resource")).thenReturn(instance(false));
        HttpHost target = new HttpHost("https", "catalog-resource", -1);

        HttpRoute route = planner.determineRoute(target, null);

        assertThat(route.getTargetHost()).isEqualTo(new HttpHost("http", "localhost", 8081));
        assertThat(route.isSecure()).isFalse();
    }

    @Test
    void noAliveInstanceThrowsHttpException() {
        when(discoveryClient.getServices()).thenReturn(List.of("catalog-resource"));
        when(loadBalancer.choose("catalog-resource")).thenReturn(null);
        HttpHost target = new HttpHost("https", "catalog-resource", -1);

        assertThatThrownBy(() -> planner.determineRoute(target, null))
                .isInstanceOf(HttpException.class)
                .hasMessageContaining("catalog-resource");
    }
}
