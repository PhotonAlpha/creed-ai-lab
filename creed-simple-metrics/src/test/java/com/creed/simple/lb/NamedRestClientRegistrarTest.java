package com.creed.simple.lb;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.apache.hc.client5.http.impl.io.PoolingHttpClientConnectionManager;
import org.junit.jupiter.api.Test;
import org.springframework.boot.ssl.NoSuchSslBundleException;
import org.springframework.boot.ssl.SslBundles;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.http.client.ClientHttpRequestInterceptor;
import org.springframework.web.client.RestClient;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * {@code creed.partner.rest-clients.<name>}: a pool and a RestClient per name, the pool's metrics tagged by
 * {@link PoolMetricNames} — {@code loadBalancedPool-<name>} — with nothing typed per client.
 */
class NamedRestClientRegistrarTest {

    /** Stands in for the LB post-processor's interceptor on the {@code @LoadBalanced} builder. */
    private static final ClientHttpRequestInterceptor LB = (request, body, execution) -> execution.execute(request, body);

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withBean(NamedRestClientRegistrar.class)
            .withBean(SslBundles.class, NamedRestClientRegistrarTest::missingBundles)
            .withBean("clusterRestClientBuilder", RestClient.Builder.class, () -> RestClient.builder().requestInterceptor(LB))
            .withBean(LoadBalancerAuditInterceptor.class)
            .withPropertyValues(
                    "creed.partner.http.max-total=40",
                    "creed.partner.rest-clients.catalog-bulk.max-per-route=5",
                    "creed.partner.rest-clients.order-report.max-total=8");

    private static SslBundles missingBundles() {
        SslBundles bundles = mock(SslBundles.class);
        when(bundles.getBundle(anyString())).thenThrow(new NoSuchSslBundleException("creed-partner-server", "test"));
        return bundles;
    }

    @Test
    void eachNameGetsAPoolAndAClientWhosePoolMetricsAreNamedByTheRule() {
        runner.run(context -> {
            assertThat(context).hasNotFailed();
            SimpleMeterRegistry registry = new SimpleMeterRegistry();
            for (String name : List.of("catalog-bulk", "order-report")) {
                assertThat(context).hasBean(name + "Pool").hasBean(name + "RestClient");
                context.getBean(name + "Pool", ManagedHttpClientPool.class).bindTo(registry);
            }
            assertThat(registry.find("httpcomponents.httpclient.pool.total.max").gauges())
                    .extracting(g -> g.getId().getTag("httpclient") + "=" + (int) g.value())
                    .containsExactlyInAnyOrder("loadBalancedPool-catalog-bulk=40", "loadBalancedPool-order-report=8");

            PoolingHttpClientConnectionManager bulk =
                    context.getBean("catalog-bulkPool", ManagedHttpClientPool.class).connectionManager();
            assertThat(bulk.getDefaultMaxPerRoute()).as("its own key").isEqualTo(5);
            assertThat(bulk.getMaxTotal()).as("creed.partner.http's").isEqualTo(40);
        });
    }

    @Test
    void theClientsCloneTheLoadBalancedBuilderAndAuditInnermost() {
        runner.run(context -> {
            LoadBalancerAuditInterceptor audit = context.getBean(LoadBalancerAuditInterceptor.class);
            for (String name : List.of("catalog-bulk", "order-report")) {
                List<ClientHttpRequestInterceptor> interceptors = new ArrayList<>();
                context.getBean(name + "RestClient", RestClient.class).mutate().requestInterceptors(interceptors::addAll);
                assertThat(interceptors).containsExactly(LB, audit);
            }
            List<ClientHttpRequestInterceptor> shared = new ArrayList<>();
            context.getBean("clusterRestClientBuilder", RestClient.Builder.class).requestInterceptors(shared::addAll);
            assertThat(shared).as("the shared builder is left alone").containsExactly(LB);
        });
    }

    @Test
    void aNameThatCannotBeABeanOrTagNameFailsStartup() {
        runner.withPropertyValues("creed.partner.rest-clients.Catalog_Bulk.max-total=4")
                .run(context -> assertThat(context).hasFailed().getFailure()
                        .hasMessageContaining("creed.partner.rest-clients.Catalog_Bulk"));
    }

    @Test
    void theDefaultPoolsKeepTheirBaseNames() {
        assertThat(PoolMetricNames.of(PoolMetricNames.LOAD_BALANCED)).isEqualTo("loadBalancedPool");
        assertThat(PoolMetricNames.of(PoolMetricNames.HEALTH_CHECK)).isEqualTo("healthCheckPool");
        assertThat(PoolMetricNames.of(PoolMetricNames.CAMEL_HTTP, "catalog-bulk")).isEqualTo("camelHttpPool-catalog-bulk");
    }
}
