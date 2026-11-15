package com.creed.simple.lb.config;

import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.cloud.client.loadbalancer.LoadBalancerProperties;
import org.springframework.cloud.loadbalancer.config.LoadBalancerAutoConfiguration;
import org.springframework.cloud.loadbalancer.support.LoadBalancerClientFactory;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@code health-check.update-results-list} for one cluster only: {@code spring.cloud.loadbalancer.clients.<service-id>.*},
 * read by {@code HealthCheckServiceInstanceListSupplier} through {@code LoadBalancerClientFactory.getProperties(serviceId)}.
 *
 * <p><b>A client's block does NOT inherit the global settings</b>, whatever the comment in
 * {@code LoadBalancerClientFactory.getProperties} says ("specifics are overlayed on top of defaults"): it binds into a
 * fresh {@code LoadBalancerProperties}, so every key it leaves out is the LIBRARY default (interval 25 s, not our 1m) -
 * 4.2.1 to 5.0.3 alike. A cluster listed under {@code clients} must repeat every global {@code spring.cloud.loadbalancer}
 * setting it needs.
 */
class PerClusterHealthCheckPropertiesTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(LoadBalancerAutoConfiguration.class))
            .withPropertyValues("spring.cloud.loadbalancer.health-check.interval=1m");

    @Test
    void aClientBlockDropsTheGlobalSettingsItDoesNotRepeat() {
        runner.withPropertyValues(
                        "spring.cloud.loadbalancer.clients.catalog-resource.health-check.update-results-list=false")
                .run(context -> {
                    LoadBalancerClientFactory factory = context.getBean(LoadBalancerClientFactory.class);
                    LoadBalancerProperties.HealthCheck catalog = healthCheck(factory, "catalog-resource");

                    assertThat(catalog.isUpdateResultsList()).isFalse();
                    assertThat(catalog.getInterval()).as("the library default, not the global 1m")
                            .isEqualTo(Duration.ofSeconds(25));
                });
    }

    @Test
    void repeatingTheGlobalSettingsChangesOnlyTheNamedCluster() {
        runner.withPropertyValues(
                        "spring.cloud.loadbalancer.clients.catalog-resource.health-check.interval=1m",
                        "spring.cloud.loadbalancer.clients.catalog-resource.health-check.update-results-list=false")
                .run(context -> {
                    LoadBalancerClientFactory factory = context.getBean(LoadBalancerClientFactory.class);
                    LoadBalancerProperties.HealthCheck catalog = healthCheck(factory, "catalog-resource");
                    LoadBalancerProperties.HealthCheck order = healthCheck(factory, "order-resource");

                    assertThat(catalog.isUpdateResultsList()).isFalse();
                    assertThat(catalog.getInterval()).isEqualTo(Duration.ofMinutes(1));
                    assertThat(order.isUpdateResultsList()).as("not listed: the global block, library default")
                            .isTrue();
                    assertThat(order.getInterval()).isEqualTo(Duration.ofMinutes(1));
                });
    }

    private static LoadBalancerProperties.HealthCheck healthCheck(LoadBalancerClientFactory factory, String serviceId) {
        return factory.getProperties(serviceId).getHealthCheck();
    }
}
