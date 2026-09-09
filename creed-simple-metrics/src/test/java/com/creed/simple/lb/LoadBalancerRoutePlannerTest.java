package com.creed.simple.lb;

import org.apache.hc.client5.http.HttpRoute;
import org.apache.hc.client5.http.protocol.HttpClientContext;
import org.apache.hc.core5.http.HttpException;
import org.apache.hc.core5.http.HttpHost;
import org.apache.hc.core5.http.message.BasicClassicHttpRequest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.cloud.client.DefaultServiceInstance;
import org.springframework.cloud.client.ServiceInstance;
import org.springframework.cloud.client.discovery.DiscoveryClient;
import org.springframework.cloud.client.loadbalancer.LoadBalancerClient;
import org.springframework.cloud.client.loadbalancer.Request;
import org.springframework.cloud.client.loadbalancer.RequestDataContext;
import org.springframework.http.HttpMethod;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link LoadBalancerRoutePlanner}: the service-id → LoadBalancer resolution path, the
 * direct fall-through for unknown hosts, the "no alive instance" failure, the TLS-layering of the
 * resolved route (the {@code secure} flag that plain {@code HttpRoute(HttpHost)} would default to
 * false), and the {@link RequestDataContext} the three-arg overload hands to {@code choose(...)}.
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

    private static BasicClassicHttpRequest request(String cookieHeader) {
        BasicClassicHttpRequest request = new BasicClassicHttpRequest("GET",
                new HttpHost("https", "catalog-resource", -1), "/api/catalog/items");
        if (cookieHeader != null) {
            request.addHeader("Cookie", cookieHeader);
        }
        return request;
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

    @Test
    void theThreeArgOverloadPassesTheRequestsMethodUriAndCookiesToChoose() throws Exception {
        when(discoveryClient.getServices()).thenReturn(List.of("catalog-resource"));
        when(loadBalancer.choose(eq("catalog-resource"), any(Request.class))).thenReturn(instance(true));

        planner.determineRoute(new HttpHost("https", "catalog-resource", -1),
                request("JSESSIONID=abc123; stickyId=STICKY-1; theme=dark"), null);

        ArgumentCaptor<Request<?>> captor = ArgumentCaptor.captor();
        org.mockito.Mockito.verify(loadBalancer).choose(eq("catalog-resource"), captor.capture());
        RequestDataContext context = (RequestDataContext) captor.getValue().getContext();
        assertThat(context.getClientRequest().getHttpMethod()).isEqualTo(HttpMethod.GET);
        assertThat(context.getClientRequest().getUrl())
                .hasToString("https://catalog-resource/api/catalog/items");
        // Every cookie of the header is parsed, not just the first — Spring's own RequestData(HttpRequest)
        // splits the raw header on '=' alone and would stop at JSESSIONID.
        assertThat(context.getClientRequest().getCookies().getFirst("stickyId")).isEqualTo("STICKY-1");
        assertThat(context.getClientRequest().getCookies().getFirst("theme")).isEqualTo("dark");
    }

    @Test
    void aRequestWithoutCookiesStillResolves() throws Exception {
        when(discoveryClient.getServices()).thenReturn(List.of("catalog-resource"));
        when(loadBalancer.choose(eq("catalog-resource"), any(Request.class))).thenReturn(instance(true));

        HttpRoute route = planner.determineRoute(new HttpHost("https", "catalog-resource", -1),
                request(null), null);

        assertThat(route.getTargetHost()).isEqualTo(new HttpHost("https", "localhost", 8081));
    }
}
