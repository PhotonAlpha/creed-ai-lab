package com.creed.resource.lbhealth.lb;

import com.creed.resource.lbhealth.config.LbHealthProperties;
import lombok.extern.slf4j.Slf4j;
import org.springframework.cloud.loadbalancer.core.ServiceInstanceListSupplier;
import org.springframework.cloud.loadbalancer.core.ServiceInstanceListSupplierBuilder;
import org.springframework.cloud.loadbalancer.support.LoadBalancerClientFactory;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.reactive.function.client.WebClient;
import org.zalando.logbook.Logbook;

/**
 * LoadBalancer child-context configuration of {@code lb-health-target}. No {@code @Configuration}: it is imported
 * by {@code @LoadBalancerClient}, and a stereotype would let component scanning register it in the main context
 * too.
 *
 * <p>Chain (bottom to top):
 * <pre>
 *   blocking DiscoveryClient            emits the registry once per subscription
 *   [RefetchLogging]                    debug only - [REFETCH]
 *   HealthCheck                         refetch → switchMap(round, repeat every interval) → replay(1)
 * </pre>
 * Nothing above the health check: {@code withCaching()} caches {@code delegate.get().take(1)} for 35 s, and the
 * health check's {@code replay(1)} already is the cache. The health check is the outermost bean, so Spring calls its
 * {@code afterPropertiesSet()} (the permanent subscription that keeps rounds running between requests) and
 * {@code destroy()}.
 */
@Slf4j
public class TargetLoadBalancerClientConfiguration {

    @Bean
    ServiceInstanceListSupplier healthCheckedServiceInstanceListSupplier(ConfigurableApplicationContext context) {
        LbHealthProperties properties = context.getBean(LbHealthProperties.class);
        LbHealthProperties.HealthCheck settings = properties.healthCheck();
        boolean debug = properties.debug().enabled();
        // the starter's bean, from the main context (this child context sees its parent's beans); null = no Logbook
        Logbook logbook = properties.logbook().enabled() ? context.getBean(Logbook.class) : null;
        log.info("[LB-HEALTH] health-check supplier: mode={} probe timeout={} (connect {} + read {}) debug={}"
                        + " logbook={}", settings.mode(), settings.probeTimeout(), settings.connectTimeout(),
                settings.readTimeout(), debug, logbook != null);

        ServiceInstanceListSupplierBuilder builder = ServiceInstanceListSupplier.builder().withBlockingDiscoveryClient();
        if (debug) {
            builder.with((ctx, delegate) -> new RefetchLoggingServiceInstanceListSupplier(delegate));
        }

        switch (settings.mode()) {
            case BLOCKING -> {
                RestTemplate restTemplate = HealthCheckClients.restTemplate(settings, debug, logbook);
                if (debug) {
                    builder.with((ctx, delegate) -> new ObservableHealthCheckServiceInstanceListSupplier(delegate,
                            ctx.getBean(LoadBalancerClientFactory.class),
                            HealthCheckClients.blockingAliveFunction(restTemplate)));
                } else {
                    builder.withBlockingHealthChecks(restTemplate);
                }
            }
            case WEBCLIENT -> {
                WebClient webClient = HealthCheckClients.webClient(settings, debug, logbook);
                if (debug) {
                    HealthCheckMdc.enableForWebClient();
                    builder.with((ctx, delegate) -> new ObservableHealthCheckServiceInstanceListSupplier(delegate,
                            ctx.getBean(LoadBalancerClientFactory.class),
                            HealthCheckClients.webClientAliveFunction(webClient)));
                } else {
                    builder.withHealthChecks(webClient);
                }
            }
        }
        return builder.build(context);
    }
}
