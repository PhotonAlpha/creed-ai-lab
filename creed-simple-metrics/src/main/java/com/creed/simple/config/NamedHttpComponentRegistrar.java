package com.creed.simple.config;

import com.creed.metrics.ConnectionPoolMetrics;
import com.creed.simple.lb.HttpPoolProperties;
import com.creed.simple.lb.LoadBalancerRoutePlanner;
import com.creed.simple.lb.RestClientSuppliers;
import io.micrometer.core.instrument.binder.MeterBinder;
import io.micrometer.observation.ObservationRegistry;
import org.apache.camel.component.http.HttpComponent;
import org.apache.hc.client5.http.impl.IdleConnectionEvictor;
import org.apache.hc.client5.http.impl.io.PoolingHttpClientConnectionManager;
import org.springframework.beans.BeansException;
import org.springframework.beans.factory.BeanFactory;
import org.springframework.beans.factory.BeanFactoryAware;
import org.springframework.beans.factory.config.ConfigurableListableBeanFactory;
import org.springframework.beans.factory.support.AbstractBeanDefinition;
import org.springframework.beans.factory.support.BeanDefinitionBuilder;
import org.springframework.beans.factory.support.BeanDefinitionRegistry;
import org.springframework.beans.factory.support.BeanDefinitionRegistryPostProcessor;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.ssl.SslBundles;
import org.springframework.context.EnvironmentAware;
import org.springframework.core.env.Environment;
import org.zalando.logbook.Logbook;

import java.time.Duration;
import java.util.function.Supplier;

/**
 * One camel-http component per {@code creed.camel.http-components.<name>} - per-endpoint isolation (asked for,
 * 2026-11-11): a cluster's bulk and real-time calls on separate pools, so a slow bulk export cannot take the
 * connections the real-time path needs. A route picks one by its scheme: {@code catalog-bulk:https://catalog-resource/...}.
 *
 * <p>For {@code catalog-bulk} it registers:
 * <ul>
 *   <li>{@code catalog-bulk} - the {@link HttpComponent}, built by {@link CamelHttpComponents} like the default one;</li>
 *   <li>{@code catalog-bulkConnectionManager} - its mTLS pool ({@code destroyMethod=""}: the component's
 *       {@code doStop()} closes it, as for the default pool);</li>
 *   <li>{@code catalog-bulkPoolEvictor} - its idle / expired sweep;</li>
 *   <li>{@code catalog-bulkPoolMetrics} - gauges tagged {@code camelHttpPool-catalog-bulk}.</li>
 * </ul>
 *
 * <p>A {@link BeanDefinitionRegistryPostProcessor} ({@code static @Bean}), as {@code ClusterClientBeanRegistrar} in
 * creed-gateway-partner: the names come straight off the {@link Environment}; every collaborator is resolved lazily
 * from the {@link BeanFactory} when the bean is created. Camel finds the component by bean name, as it finds
 * {@code https}.
 */
public class NamedHttpComponentRegistrar
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
        for (String name : CamelHttpComponents.names(environment)) {
            if (registry.containsBeanDefinition(name)) {
                throw new IllegalStateException(CamelHttpComponents.NAMED + "." + name
                        + ": a bean of that name already exists - pick another component name");
            }
            register(registry, name);
        }
    }

    @Override
    public void postProcessBeanFactory(ConfigurableListableBeanFactory beanFactory) {
        // all registration happens in postProcessBeanDefinitionRegistry
    }

    private void register(BeanDefinitionRegistry registry, String name) {
        HttpPoolProperties props = CamelHttpComponents.named(environment, name);
        String pool = name + "ConnectionManager";

        define(registry, pool, PoolingHttpClientConnectionManager.class, () -> RestClientSuppliers.connectionManagerFrom(
                RestClientSuppliers.resolveClientBundleOrNull(beanFactory.getBean(SslBundles.class),
                        environment.getProperty("creed.partner.client-bundle", "creed-partner-server")), props),
                null, "");
        define(registry, name + "PoolEvictor", IdleConnectionEvictor.class,
                () -> RestClientSuppliers.evictorFor(bean(pool), props), "start", "shutdown");
        define(registry, name + "PoolMetrics", MeterBinder.class,
                () -> new ConnectionPoolMetrics(bean(pool), "camelHttpPool-" + name), null, null);
        // Binder, not Environment.getProperty: "1s" -> Duration needs Boot's conversion
        Binder binder = Binder.get(environment);
        define(registry, name, HttpComponent.class, () -> CamelHttpComponents.createNamed(name, bean(pool), props,
                new CamelHttpComponents.Shared(
                        beanFactory.getBean(LoadBalancerRoutePlanner.class),
                        beanFactory.getBean(ObservationRegistry.class),
                        beanFactory.getBean(Logbook.class),
                        binder.bind("creed.audit.pool-stats.every", Integer.class).orElse(100),
                        binder.bind("creed.audit.pool-stats.slow-threshold", Duration.class)
                                .orElse(Duration.ofSeconds(1)))),
                null, null);
    }

    private PoolingHttpClientConnectionManager bean(String name) {
        return beanFactory.getBean(name, PoolingHttpClientConnectionManager.class);
    }

    private static <T> void define(BeanDefinitionRegistry registry, String name, Class<T> type, Supplier<T> supplier,
                                   String initMethod, String destroyMethod) {
        AbstractBeanDefinition definition = BeanDefinitionBuilder.genericBeanDefinition(type, supplier).getBeanDefinition();
        if (initMethod != null) {
            definition.setInitMethodName(initMethod);
        }
        if (destroyMethod != null) {
            // "" = none: the pool is closed by its component's doStop(), not by Spring
            definition.setDestroyMethodName(destroyMethod);
        }
        registry.registerBeanDefinition(name, definition);
    }
}
