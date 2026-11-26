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
                    "creed.partner.rest-clients.catalogbulk.max-per-route=5",
                    "creed.partner.rest-clients.orderreport.max-total=8");

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
            for (String name : List.of("catalogbulk", "orderreport")) {
                assertThat(context).hasBean(name + "Pool").hasBean(name + "RestClient");
                context.getBean(name + "Pool", ManagedHttpClientPool.class).bindTo(registry);
            }
            assertThat(registry.find("httpcomponents.httpclient.pool.total.max").gauges())
                    .extracting(g -> g.getId().getTag("httpclient") + "=" + (int) g.value())
                    .containsExactlyInAnyOrder("loadBalancedPool-catalogbulk=40", "loadBalancedPool-orderreport=8");

            PoolingHttpClientConnectionManager bulk =
                    context.getBean("catalogbulkPool", ManagedHttpClientPool.class).connectionManager();
            assertThat(bulk.getDefaultMaxPerRoute()).as("its own key").isEqualTo(5);
            assertThat(bulk.getMaxTotal()).as("creed.partner.http's").isEqualTo(40);
        });
    }

    @Test
    void theClientsCloneTheLoadBalancedBuilderAndAuditInnermost() {
        runner.run(context -> {
            LoadBalancerAuditInterceptor audit = context.getBean(LoadBalancerAuditInterceptor.class);
            for (String name : List.of("catalogbulk", "orderreport")) {
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
        // '-' would give catalog-bulkRestClient, which fails Sonar's bean-name rule; camelCase cannot be bound
        for (String bad : List.of("Catalog_Bulk", "catalog-bulk", "catalogBulk")) {
            runner.withPropertyValues("creed.partner.rest-clients." + bad + ".max-total=4")
                    .run(context -> assertThat(context).as(bad).hasFailed().getFailure()
                            .hasMessageContaining("creed.partner.rest-clients." + bad));
        }
    }

    @Test
    void theDefaultPoolsKeepTheirBaseNames() {
        assertThat(PoolMetricNames.of(PoolMetricNames.LOAD_BALANCED)).isEqualTo("loadBalancedPool");
        assertThat(PoolMetricNames.of(PoolMetricNames.HEALTH_CHECK)).isEqualTo("healthCheckPool");
        assertThat(PoolMetricNames.of(PoolMetricNames.CAMEL_HTTP, "catalogbulk")).isEqualTo("camelHttpPool-catalogbulk");
    }
}
