package com.creed.simple.lb;

import org.springframework.beans.BeansException;
import org.springframework.beans.factory.BeanFactory;
import org.springframework.beans.factory.BeanFactoryAware;
import org.springframework.beans.factory.config.ConfigurableListableBeanFactory;
import org.springframework.beans.factory.support.AbstractBeanDefinition;
import org.springframework.beans.factory.support.BeanDefinitionBuilder;
import org.springframework.beans.factory.support.BeanDefinitionRegistry;
import org.springframework.beans.factory.support.BeanDefinitionRegistryPostProcessor;
import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.ssl.SslBundles;
import org.springframework.context.EnvironmentAware;
import org.springframework.core.env.Environment;
import org.springframework.web.client.RestClient;

import java.util.List;
import java.util.Map;
import java.util.function.Supplier;
import java.util.regex.Pattern;

/**
 * One {@code @LoadBalanced} RestClient stack per {@code creed.partner.rest-clients.<name>} — the batch form of
 * {@code clusterPool} / {@code clusterRestClient}, for when one cluster's calls need a pool of their own. Each
 * block takes {@link HttpPoolProperties} keys; whatever it leaves out comes from {@code creed.partner.http}.
 *
 * <p>For {@code catalog-bulk} it registers:
 * <ul>
 *   <li>{@code catalog-bulkPool} — a {@link ManagedHttpClientPool} (mTLS pool, request factory, idle evictor),
 *       closed by Spring; its gauges are tagged {@code loadBalancedPool-catalog-bulk} by
 *       {@link PoolMetricNames} — never typed by hand — and bound by Boot because the pool is a
 *       {@code MeterBinder};</li>
 *   <li>{@code catalog-bulkRestClient} — a clone of the {@code @LoadBalanced} {@code clusterRestClientBuilder}
 *       (so the load-balancer interceptor comes first) on that pool, with {@link LoadBalancerAuditInterceptor}
 *       appended to run innermost, exactly as {@code clusterRestClient}.</li>
 * </ul>
 * Inject one with {@code @Qualifier("catalog-bulkRestClient")}.
 *
 * <p>A {@link BeanDefinitionRegistryPostProcessor} ({@code static @Bean}), as {@code NamedHttpComponentRegistrar}:
 * the names come off the {@link Environment}, every collaborator is resolved lazily when the bean is created.
 */
public class NamedRestClientRegistrar
        implements BeanDefinitionRegistryPostProcessor, EnvironmentAware, BeanFactoryAware {

    public static final String NAMED = "creed.partner.rest-clients";
    /** The fallback of every named block: the default business pool's keys. */
    public static final String DEFAULTS = "creed.partner.http";

    /** Becomes part of a bean name and of a metric tag: lower case, digits, '-'. */
    private static final Pattern NAME = Pattern.compile("[a-z][a-z0-9-]*");

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

    /** The names declared under {@link #NAMED}, validated. */
    static List<String> names(Environment environment) {
        Map<String, Object> declared = Binder.get(environment)
                .bind(NAMED, Bindable.mapOf(String.class, Object.class))
                .orElse(Map.of());
        for (String name : declared.keySet()) {
            if (!NAME.matcher(name).matches()) {
                throw new IllegalStateException(NAMED + "." + name + ": a client name is lower-case letters, digits"
                        + " and '-', starting with a letter (e.g. catalog-bulk)");
            }
        }
        return List.copyOf(declared.keySet());
    }

    @Override
    public void postProcessBeanDefinitionRegistry(BeanDefinitionRegistry registry) {
        for (String name : names(environment)) {
            String pool = name + "Pool";
            String client = name + "RestClient";
            for (String bean : new String[] {pool, client}) {
                if (registry.containsBeanDefinition(bean)) {
                    throw new IllegalStateException(NAMED + "." + name + ": a bean named '" + bean
                            + "' already exists - pick another client name");
                }
            }
            HttpPoolProperties props = HttpPoolProperties.layered(environment, NAMED + "." + name, DEFAULTS);
            define(registry, pool, ManagedHttpClientPool.class, () -> RestClientSuppliers.pool(
                    beanFactory.getBean(SslBundles.class),
                    environment.getProperty("creed.partner.client-bundle", "creed-partner-server"),
                    props, PoolMetricNames.of(PoolMetricNames.LOAD_BALANCED, name)), "close");
            define(registry, client, RestClient.class, () ->
                    // clone(): the shared @LoadBalanced builder must not collect every client's settings
                    beanFactory.getBean("clusterRestClientBuilder", RestClient.Builder.class).clone()
                            .requestFactory(beanFactory.getBean(pool, ManagedHttpClientPool.class).requestFactory())
                            .requestInterceptor(beanFactory.getBean(LoadBalancerAuditInterceptor.class))
                            .build(), null);
        }
    }

    @Override
    public void postProcessBeanFactory(ConfigurableListableBeanFactory beanFactory) {
        // all registration happens in postProcessBeanDefinitionRegistry
    }

    private static <T> void define(BeanDefinitionRegistry registry, String name, Class<T> type, Supplier<T> supplier,
                                   String destroyMethod) {
        AbstractBeanDefinition definition = BeanDefinitionBuilder.genericBeanDefinition(type, supplier).getBeanDefinition();
        if (destroyMethod != null) {
            definition.setDestroyMethodName(destroyMethod);
        }
        registry.registerBeanDefinition(name, definition);
    }
}
