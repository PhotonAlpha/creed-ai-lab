package com.creed.simple.lb.config;

import com.creed.simple.lb.StickyMetadataServiceInstanceListSupplier;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;
import org.springframework.cloud.client.DefaultServiceInstance;
import org.springframework.cloud.client.ServiceInstance;
import org.springframework.cloud.client.loadbalancer.DefaultRequest;
import org.springframework.cloud.client.loadbalancer.Request;
import org.springframework.cloud.client.loadbalancer.RequestData;
import org.springframework.cloud.client.loadbalancer.RequestDataContext;
import org.springframework.cloud.loadbalancer.core.ServiceInstanceListSupplier;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import reactor.core.publisher.Flux;

import java.net.URI;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;

/**
 * Unit tests for {@link PaymentStickyLoadBalancerConfiguration}: it stacks a
 * {@link StickyMetadataServiceInstanceListSupplier} on top of the shared health-checked base chain
 * ({@link PartnerLoadBalancerConfiguration#healthCheckedSupplier}). The base chain is static-mocked so
 * the wiring — and the sticky filter actually running over the base's alive list — is verified in
 * isolation, without standing up a load-balancer child context.
 */
class PaymentStickyLoadBalancerConfigurationTest {

    private static final String STICKY = "STICKY-PRIMARY";

    private final PaymentStickyLoadBalancerConfiguration config = new PaymentStickyLoadBalancerConfiguration();
    private final ConfigurableApplicationContext context = mock(ConfigurableApplicationContext.class);

    /** A request carrying the given sticky cookie, shaped like the one the route planner builds. */
    private static Request<RequestDataContext> requestWithSticky(String stickyId) {
        MultiValueMap<String, String> cookies = new LinkedMultiValueMap<>();
        if (stickyId != null) {
            cookies.add(StickyMetadataServiceInstanceListSupplier.STICKY_COOKIE, stickyId);
        }
        return new DefaultRequest<>(new RequestDataContext(new RequestData(HttpMethod.GET,
                URI.create("https://payment-resource/api/payment"), new HttpHeaders(), cookies, new HashMap<>())));
    }

    private static ServiceInstance instance(String id, int port, String stickyId) {
        return new DefaultServiceInstance(id, "payment-resource", "localhost", port, true,
                Map.of(StickyMetadataServiceInstanceListSupplier.STICKY_METADATA_KEY, stickyId));
    }

    @Test
    void wrapsTheHealthCheckedBaseInAStickyMetadataSupplier() {
        ServiceInstance primary = instance("payment-1", 18093, STICKY);
        ServiceInstance secondary = instance("payment-2", 18094, "OTHER");
        List<ServiceInstance> alive = List.of(primary, secondary);

        ServiceInstanceListSupplier base = mock(ServiceInstanceListSupplier.class);
        org.mockito.Mockito.when(base.get(org.mockito.ArgumentMatchers.any(Request.class)))
                .thenReturn(Flux.just(alive));

        try (MockedStatic<PartnerLoadBalancerConfiguration> mocked =
                     mockStatic(PartnerLoadBalancerConfiguration.class)) {
            mocked.when(() -> PartnerLoadBalancerConfiguration.healthCheckedSupplier(context)).thenReturn(base);

            ServiceInstanceListSupplier supplier = config.paymentStickyServiceInstanceListSupplier(context);

            assertThat(supplier).isInstanceOf(StickyMetadataServiceInstanceListSupplier.class);

            // With a sticky cookie on the request, the sticky layer narrows the base's alive list.
            assertThat(supplier.get(requestWithSticky(STICKY)).blockFirst()).containsExactly(primary);

            // Without one it passes the full alive list straight through.
            assertThat(supplier.get(requestWithSticky(null)).blockFirst()).isEqualTo(alive);
        }
    }
}
