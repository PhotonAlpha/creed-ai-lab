package com.creed.simple.config;

import com.creed.simple.lb.LoadBalancerRoutePlanner;
import io.micrometer.core.instrument.binder.MeterBinder;
import io.micrometer.observation.ObservationRegistry;
import org.apache.camel.component.http.HttpComponent;
import org.apache.camel.component.http.HttpEndpoint;
import org.apache.camel.spring.SpringCamelContext;
import org.apache.hc.client5.http.impl.IdleConnectionEvictor;
import org.apache.hc.client5.http.impl.io.PoolingHttpClientConnectionManager;
import org.junit.jupiter.api.Test;
import org.springframework.boot.ssl.NoSuchSslBundleException;
import org.springframework.boot.ssl.SslBundles;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.cloud.client.discovery.DiscoveryClient;
import org.springframework.cloud.client.loadbalancer.LoadBalancerClient;
import org.zalando.logbook.Logbook;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * The registrar's beans as Camel sees them: a Spring-registry Camel context resolves {@code catalogbulk:https://...}
 * to the dynamically registered component, on the pool bean of the same name.
 */
class NamedHttpComponentRegistrarTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withBean(NamedHttpComponentRegistrar.class)
            .withBean(SslBundles.class, NamedHttpComponentRegistrarTest::missingBundles)
            .withBean(LoadBalancerRoutePlanner.class,
                    () -> new LoadBalancerRoutePlanner(mock(LoadBalancerClient.class), mock(DiscoveryClient.class)))
            .withBean(ObservationRegistry.class, () -> ObservationRegistry.NOOP)
            .withBean(Logbook.class, Logbook::create)
            .withPropertyValues(
                    "creed.camel.http.max-total=40",
                    "creed.camel.http-components.catalogbulk.max-per-route=5",
                    "creed.camel.http-components.catalogrealtime.max-total=8");

    private static SslBundles missingBundles() {
        SslBundles bundles = mock(SslBundles.class);
        when(bundles.getBundle(anyString())).thenThrow(new NoSuchSslBundleException("creed-partner-server", "test"));
        return bundles;
    }

    @Test
    void eachNamedComponentGetsItsOwnPoolEvictorAndMetrics() {
        runner.run(context -> {
            assertThat(context).hasNotFailed();
            for (String name : new String[] {"catalogbulk", "catalogrealtime"}) {
                assertThat(context).hasBean(name).hasBean(name + "ConnectionManager")
                        .hasBean(name + "PoolEvictor").hasBean(name + "PoolMetrics");
                assertThat(context.getBean(name + "PoolEvictor", IdleConnectionEvictor.class).isRunning()).isTrue();
                assertThat(context.getBean(name + "PoolMetrics")).isInstanceOf(MeterBinder.class);
            }
            PoolingHttpClientConnectionManager bulk = context.getBean("catalogbulkConnectionManager",
                    PoolingHttpClientConnectionManager.class);
            assertThat(bulk.getDefaultMaxPerRoute()).isEqualTo(5);
            assertThat(bulk.getMaxTotal()).isEqualTo(40);
            assertThat(context.getBean("catalogrealtimeConnectionManager", PoolingHttpClientConnectionManager.class)
                    .getMaxTotal()).isEqualTo(8);
        });
    }

    @Test
    void camelFindsTheComponentByItsBeanName() {
        runner.run(context -> {
            SpringCamelContext camel = new SpringCamelContext(context);
            camel.start();
            try {
                HttpEndpoint endpoint = camel.getEndpoint(
                        "catalogbulk:https://catalog-resource/api/catalog/bulk", HttpEndpoint.class);
                assertThat(endpoint.getComponent()).isSameAs(context.getBean("catalogbulk", HttpComponent.class));
                assertThat(endpoint.getClientConnectionManager())
                        .isSameAs(context.getBean("catalogbulkConnectionManager"));
                assertThat(endpoint.getHttpUri()).hasScheme("https");
            } finally {
                camel.stop();
            }
        });
    }

    @Test
    void nothingDeclaredRegistersNothing() {
        new ApplicationContextRunner().withBean(NamedHttpComponentRegistrar.class)
                .run(context -> assertThat(context.getBeansOfType(HttpComponent.class)).isEmpty());
    }
}
