package com.creed.resource.lbhealth.lb;

import lombok.extern.slf4j.Slf4j;
import org.springframework.cloud.client.ServiceInstance;
import org.springframework.cloud.loadbalancer.core.DelegatingServiceInstanceListSupplier;
import org.springframework.cloud.loadbalancer.core.ServiceInstanceListSupplier;
import reactor.core.publisher.Flux;

import java.util.List;

/**
 * Sits between discovery and the health check and logs every list discovery hands up: {@code [REFETCH]}.
 *
 * <p>The health check subscribes to its delegate through {@code Flux.defer(delegate).repeatWhen(refetch)}, so
 * {@link #get()} is called once at start and once per {@code refetch-instances-interval}. Each line therefore
 * marks the moment the health check's {@code switchMap} drops whatever it was doing and starts a new round.
 */
@Slf4j
public class RefetchLoggingServiceInstanceListSupplier extends DelegatingServiceInstanceListSupplier {

    public RefetchLoggingServiceInstanceListSupplier(ServiceInstanceListSupplier delegate) {
        super(delegate);
    }

    @Override
    public Flux<List<ServiceInstance>> get() {
        return delegate.get().doOnNext(instances -> log.info("[REFETCH] {} service={} size={}",
                DebugLog.now(), getServiceId(), instances.size()));
    }
}
