package com.creed.simple.lb;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.cloud.client.ServiceInstance;
import org.springframework.cloud.loadbalancer.core.HealthCheckServiceInstanceListSupplier;
import org.springframework.cloud.loadbalancer.core.ServiceInstanceListSupplier;
import org.springframework.cloud.loadbalancer.support.LoadBalancerClientFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.util.StopWatch;
import org.springframework.util.StringUtils;
import org.springframework.web.client.RestClient;
import org.springframework.web.util.UriComponentsBuilder;
import reactor.core.publisher.Mono;

import java.net.URI;
import java.util.function.BiConsumer;

public interface HealthCheckServiceSupplier {
    Logger LOGGER = LoggerFactory.getLogger(HealthCheckServiceSupplier.class);
    default ServiceInstanceListSupplier healthCheckServiceInstanceListSupplierBuilder(RestClient restClient,
                                                                                      ServiceInstanceListSupplier delegate,
                                                                                      LoadBalancerClientFactory loadBalancerClientFactory,
                                                                                      BiConsumer<ServiceInstance, Boolean> nextConsumer) {
        return new HealthCheckServiceInstanceListSupplier(delegate, loadBalancerClientFactory,
                (serviceInstance, healthCheckPath) -> Mono.defer(() ->
                                isAlive(serviceInstance, healthCheckPath, restClient)
                        )
                        .doOnNext(alive -> nextConsumer.accept(serviceInstance, alive))
        );
    }

    default Mono<Boolean> isAlive(ServiceInstance serviceInstance, String healthCheckPath, RestClient restClient) {
        URI uri = healthCheckUri(serviceInstance, healthCheckPath);
        StopWatch stopWatch = new StopWatch(healthCheckPath);
        stopWatch.start();
        try {
            HttpStatusCode statusCode =
                    restClient.get().uri(uri).retrieve().toBodilessEntity().getStatusCode();
            boolean alive = HttpStatus.OK.equals(statusCode);
            stopWatch.stop();
            LOGGER.info("[LB-HEALTH] {} ({}) -> status={}, alive={}, cost={} ms",
                    serviceInstance.getServiceId(), uri, statusCode, alive, stopWatch.getTotalTimeMillis());
            return Mono.just(alive);
        }
        catch (Exception ex) {
            stopWatch.stop();
            // Spring Cloud's stock probe swallows this in `catch (Exception ignored)`; we log it.
            LOGGER.warn("[LB-HEALTH] {} ({}) -> probe failed, marking DOWN: {}, cost={} ms",
                    serviceInstance.getServiceId(), uri, ex.getMessage(), stopWatch.getTotalTimeMillis(), ex);
            return Mono.just(false);
        }
    }

    /**
     * Reconstructs the probe URI exactly like {@code ServiceInstanceListSupplierBuilder#getUri}: append the
     * configured health-check path (default {@code /actuator/health}) to the instance URI, tolerating a
     * leading slash either way.
     */
    default URI healthCheckUri(ServiceInstance serviceInstance, String healthCheckPath) {
        String base = serviceInstance.getUri().toString();
        String uriString = StringUtils.hasText(healthCheckPath)
                ? base + (healthCheckPath.startsWith("/") ? healthCheckPath : "/" + healthCheckPath)
                : base;
        return UriComponentsBuilder.fromUriString(uriString).build().toUri();
    }
}
