package com.creed.partner.web;

import com.creed.partner.lb.RestClientSuppliers;
import com.creed.partner.web.PartnerClusterProperties.ClusterSpec;
import com.creed.partner.web.PartnerClusterProperties.PoolSpec;
import io.micrometer.core.instrument.binder.MeterBinder;
import com.creed.metrics.ConnectionPoolMetrics;
import org.apache.hc.client5.http.impl.io.PoolingHttpClientConnectionManager;
import org.springframework.beans.BeansException;
import org.springframework.beans.factory.BeanFactory;
import org.springframework.beans.factory.BeanFactoryAware;
import org.springframework.beans.factory.config.ConfigurableListableBeanFactory;
import org.springframework.beans.factory.support.BeanDefinitionBuilder;
import org.springframework.beans.factory.support.BeanDefinitionRegistry;
import org.springframework.beans.factory.support.BeanDefinitionRegistryPostProcessor;
import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.ssl.SslBundle;
import org.springframework.boot.ssl.SslBundles;
import org.springframework.context.EnvironmentAware;
import org.springframework.core.env.Environment;
import org.springframework.http.client.BufferingClientHttpRequestFactory;
import org.springframework.http.client.ClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

import java.util.Collections;
import java.util.Map;
import java.util.function.Supplier;

/**
 * Registers, <strong>per cluster declared under {@code creed.partner.clusters.<name>}</strong>, the full
 * set of HTTP-client beans as <em>real, dynamically created Spring beans</em> — instead of a static
 * {@code @Bean} method (or eight) per cluster. For a cluster named {@code catalog} it registers:
 *
 * <ul>
 *   <li>{@code catalogHttpConnectionManager} — business pool ({@code destroyMethod="close"});</li>
 *   <li>{@code catalogClientHttpRequestFactory} — buffered factory (audit re-reads the body);</li>
 *   <li>{@code catalogRestClient} — load-balanced + audited client (clones the {@code @LoadBalanced}
 *       builder so it keeps the load-balancer interceptor, then appends the audit interceptor);</li>
 *   <li>{@code catalogPoolMetrics} — {@link MeterBinder} for the business pool;</li>
 *   <li>and the {@code catalogHealthCheck*} quartet for the (smaller) health-check pool/client.</li>
 * </ul>
 *
 * <p>This is a {@link BeanDefinitionRegistryPostProcessor} (declared {@code static @Bean} so it runs before
 * regular bean instantiation). Because {@code @ConfigurationProperties} beans are not yet available at this
 * phase, the cluster map is bound straight off the {@link Environment} with {@link Binder}. Each bean uses
 * an {@code instanceSupplier} that resolves its collaborators (SSL bundle, pools, the {@code @LoadBalanced}
 * builder, the audit interceptor) lazily from the {@link BeanFactory} at instantiation time — by then the
 * context is refreshing and those beans (and the {@code @LoadBalanced} post-processor) are ready.
 *
 * <p>{@link MeterBinder} beans are auto-bound to the {@code MeterRegistry} by Spring Boot, and the pools are
 * created with {@code setConnectionManagerShared(true)} so the {@code destroyMethod="close"} on the pool
 * bean is the single owner of the pool lifecycle.
 */
public class ClusterClientBeanRegistrar
        implements BeanDefinitionRegistryPostProcessor, EnvironmentAware, BeanFactoryAware {

    private Environment environment;
    private BeanFactory beanFactory;

    @Override
    public void setEnvironment(Environment environment) {
        this.environment = environment;
    }

    @Override
    public void setBeanFactory(BeanFactory beanFactory) throws BeansException {
        this.beanFactory = beanFactory;
    }

    @Override
    public void postProcessBeanDefinitionRegistry(BeanDefinitionRegistry registry) {
        Map<String, ClusterSpec> clusters = Binder.get(environment)
                .bind("creed.partner.clusters", Bindable.mapOf(String.class, ClusterSpec.class))
                .orElse(Collections.emptyMap());
        clusters.forEach((name, spec) -> registerCluster(registry, name, spec));
    }

    @Override
    public void postProcessBeanFactory(ConfigurableListableBeanFactory beanFactory) {
        // No-op: all registration happens in postProcessBeanDefinitionRegistry.
    }

    private void registerCluster(BeanDefinitionRegistry registry, String name, ClusterSpec spec) {
        String businessPool = name + "HttpConnectionManager";
        String businessFactory = name + "ClientHttpRequestFactory";
        String businessClient = name + "RestClient";
        String businessMetrics = name + "PoolMetrics";
        String healthPool = name + "HealthCheckHttpConnectionManager";
        String healthFactory = name + "HealthCheckClientHttpRequestFactory";
        String healthClient = name + "HealthCheckRestClient";
        String healthMetrics = name + "HealthCheckPoolMetrics";

        // ---- business pool: load-balanced + audited ----------------------------------------------
        register(registry, businessPool, PoolingHttpClientConnectionManager.class,
                () -> connectionManager(spec, spec.http()), "close");
        register(registry, businessFactory, ClientHttpRequestFactory.class, () -> {
            PoolSpec http = spec.http();
            return new BufferingClientHttpRequestFactory(RestClientSuppliers.requestFactoryFrom(
                    bean(businessPool, PoolingHttpClientConnectionManager.class),
                    http.connectionRequestTimeout(), http.responseTimeout()));
        }, null);
        register(registry, businessClient, RestClient.class, () ->
                // clone() keeps the load-balancer interceptor added by @LoadBalanced (outermost); the audit
                // interceptor is appended so it runs innermost and sees the resolved instance URL.
                beanFactory.getBean(RestClient.Builder.class).clone()
                        .requestFactory(bean(businessFactory, ClientHttpRequestFactory.class))
                        .requestInterceptor(beanFactory.getBean(LoadBalancerAuditInterceptor.class))
                        .build(), null);
        register(registry, businessMetrics, MeterBinder.class, () ->
                new ConnectionPoolMetrics(
                        bean(businessPool, PoolingHttpClientConnectionManager.class),
                        "creed-partner-" + name + "-aggregate"), null);

        // ---- health-check pool: plain client -----------------------------------------------------
        register(registry, healthPool, PoolingHttpClientConnectionManager.class,
                () -> connectionManager(spec, spec.healthCheck().http()), "close");
        register(registry, healthFactory, ClientHttpRequestFactory.class, () -> {
            PoolSpec http = spec.healthCheck().http();
            return RestClientSuppliers.requestFactoryFrom(
                    bean(healthPool, PoolingHttpClientConnectionManager.class),
                    http.connectionRequestTimeout(), http.responseTimeout());
        }, null);
        register(registry, healthClient, RestClient.class, () ->
                RestClient.builder().requestFactory(bean(healthFactory, ClientHttpRequestFactory.class)).build(), null);
        register(registry, healthMetrics, MeterBinder.class, () ->
                new ConnectionPoolMetrics(
                        bean(healthPool, PoolingHttpClientConnectionManager.class),
                        "creed-partner-" + name + "-health"), null);
    }

    private PoolingHttpClientConnectionManager connectionManager(ClusterSpec spec, PoolSpec pool) {
        SslBundle bundle = beanFactory.getBean(SslBundles.class).getBundle(spec.clientBundle());
        return RestClientSuppliers.connectionManagerFrom(
                bundle, pool.maxTotal(), pool.maxPerRoute(), pool.connectTimeout(), pool.socketTimeout());
    }

    private <T> T bean(String name, Class<T> type) {
        return beanFactory.getBean(name, type);
    }

    private static <T> void register(
            BeanDefinitionRegistry registry, String beanName, Class<T> type,
            Supplier<T> instanceSupplier, String destroyMethod) {
        BeanDefinitionBuilder builder = BeanDefinitionBuilder.genericBeanDefinition(type, instanceSupplier);
        if (destroyMethod != null) {
            builder.setDestroyMethodName(destroyMethod);
        }
        registry.registerBeanDefinition(beanName, builder.getBeanDefinition());
    }
}
