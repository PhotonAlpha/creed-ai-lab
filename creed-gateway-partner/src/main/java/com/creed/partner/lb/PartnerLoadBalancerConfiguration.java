package com.creed.partner.lb;

import lombok.extern.slf4j.Slf4j;
import org.springframework.cloud.client.ServiceInstance;
import org.springframework.cloud.loadbalancer.core.HealthCheckServiceInstanceListSupplier;
import org.springframework.cloud.loadbalancer.core.ServiceInstanceListSupplier;
import org.springframework.cloud.loadbalancer.support.LoadBalancerClientFactory;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.util.StopWatch;
import org.springframework.util.StringUtils;
import org.springframework.web.client.RestClient;
import org.springframework.web.util.UriComponentsBuilder;
import reactor.core.publisher.Mono;

import java.net.URI;
import java.util.function.BiFunction;

/**
 * Requirement 2: per-client Spring Cloud LoadBalancer configuration that adds health checks on top of
 * the {@code SimpleDiscoveryClient} registry. Referenced from {@code @LoadBalancerClients(
 * defaultConfiguration = PartnerLoadBalancerConfiguration.class)} so it applies to every {@code lb://}
 * service.
 *
 * <p>This class is intentionally <strong>not</strong> annotated with {@code @Configuration}/
 * {@code @Component}: Spring Cloud imports it into each load-balancer child context, and keeping it out
 * of the main {@code @ComponentScan} avoids the supplier leaking into the primary application context.
 *
 * <p>Instead of the stock {@code ServiceInstanceListSupplier.builder().withBlockingHealthChecks(RestClient)}
 * — whose alive-probe lambda swallows every error in a bare {@code catch (Exception ignored)} and reports
 * no status — we plug in our own {@link HealthCheckServiceInstanceListSupplier} via {@code .with(...)}. The
 * probe is a faithful copy of Spring Cloud's blocking RestClient health check
 * ({@code ServiceInstanceListSupplierBuilder#blockingHealthCheckServiceInstanceListSupplier}) plus logging:
 * it prints the HTTP status returned by each instance and, when the call throws, logs the otherwise-ignored
 * exception. Only instances answering {@code 200 OK} are handed to the load balancer.
 */
@Slf4j
public class PartnerLoadBalancerConfiguration {

    @Bean
    ServiceInstanceListSupplier partnerServiceInstanceListSupplier(ConfigurableApplicationContext context) {
        RestClient healthCheckRestClient = context.getBean("healthCheckRestClient", RestClient.class);
        return ServiceInstanceListSupplier.builder()
                .withBlockingDiscoveryClient()
                .with((ctx, delegate) -> {
                    LoadBalancerClientFactory loadBalancerClientFactory =
                            ctx.getBean(LoadBalancerClientFactory.class);
                    return new LoggingHealthCheckServiceInstanceListSupplier(
                            delegate, loadBalancerClientFactory, loggingAliveFunction(healthCheckRestClient));
                })
                .withCaching()
                .build(context);
    }

    /**
     * Mirrors Spring Cloud's blocking RestClient alive-probe but logs what the stock lambda hides: the
     * actual HTTP status of each {@code Mono.defer} probe, and any exception it would otherwise drop in
     * {@code catch (Exception ignored)}. Returns {@code true} only for {@code 200 OK}, matching upstream.
     */
    private BiFunction<ServiceInstance, String, Mono<Boolean>> loggingAliveFunction(RestClient restClient) {
        return (serviceInstance, healthCheckPath) -> Mono.defer(() -> {
            URI uri = healthCheckUri(serviceInstance, healthCheckPath);
            StopWatch stopWatch = new StopWatch(healthCheckPath);
            stopWatch.start();
            try {
                HttpStatusCode statusCode =
                        restClient.get().uri(uri).retrieve().toBodilessEntity().getStatusCode();
                boolean alive = HttpStatus.OK.equals(statusCode);
                stopWatch.stop();
                log.info("[LB-HEALTH] {} ({}) -> status={}, alive={}, cost={} ms",
                        serviceInstance.getServiceId(), uri, statusCode, alive, stopWatch.getTotalTimeMillis());
                return Mono.just(alive);
            }
            catch (Exception ex) {
                stopWatch.stop();
                // Spring Cloud's stock probe swallows this in `catch (Exception ignored)`; we log it.
                log.warn("[LB-HEALTH] {} ({}) -> probe failed, marking DOWN: {}, cost={} ms",
                        serviceInstance.getServiceId(), uri, ex.getMessage(), stopWatch.getTotalTimeMillis(), ex);
                return Mono.just(false);
            }
        });
    }

    /**
     * Reconstructs the probe URI exactly like {@code ServiceInstanceListSupplierBuilder#getUri}: append the
     * configured health-check path (default {@code /actuator/health}) to the instance URI, tolerating a
     * leading slash either way.
     */
    private static URI healthCheckUri(ServiceInstance serviceInstance, String healthCheckPath) {
        String base = serviceInstance.getUri().toString();
        String uriString = StringUtils.hasText(healthCheckPath)
                ? base + (healthCheckPath.startsWith("/") ? healthCheckPath : "/" + healthCheckPath)
                : base;
        return UriComponentsBuilder.fromUriString(uriString).build().toUri();
    }

    /**
     * Subclass of {@link HealthCheckServiceInstanceListSupplier} that overrides {@link #isAlive} purely to
     * trace, per probe cycle, the <em>full {@link ServiceInstance}</em> currently under health check —
     * serviceId, instanceId, resolved URI, host/port, scheme and metadata — and the resulting verdict. The
     * actual probe (HTTP status, latency, swallowed-exception logging) still happens in
     * {@link #loggingAliveFunction}, which the superclass invokes via the supplied alive-function.
     */
    static class LoggingHealthCheckServiceInstanceListSupplier extends HealthCheckServiceInstanceListSupplier {

        LoggingHealthCheckServiceInstanceListSupplier(ServiceInstanceListSupplier delegate,
                LoadBalancerClientFactory loadBalancerClientFactory,
                BiFunction<ServiceInstance, String, Mono<Boolean>> aliveFunction) {
            super(delegate, loadBalancerClientFactory, aliveFunction);
        }

        @Override
        protected Mono<Boolean> isAlive(ServiceInstance serviceInstance) {
            log.info("[LB-HEALTH] checking instance serviceId={} instanceId={} uri={} host={} port={} "
                            + "secure={} metadata={}",
                    serviceInstance.getServiceId(), serviceInstance.getInstanceId(), serviceInstance.getUri(),
                    serviceInstance.getHost(), serviceInstance.getPort(), serviceInstance.isSecure(),
                    serviceInstance.getMetadata());
            return super.isAlive(serviceInstance)
                    .doOnNext(alive -> log.info("[LB-HEALTH] instance serviceId={} instanceId={} uri={} -> alive={}",
                            serviceInstance.getServiceId(), serviceInstance.getInstanceId(),
                            serviceInstance.getUri(), alive));
        }
    }
}
