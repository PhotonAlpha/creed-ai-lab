package com.creed.resource.lbhealth.lb;

import org.springframework.cloud.client.DefaultServiceInstance;
import org.springframework.cloud.client.ServiceInstance;
import org.springframework.cloud.loadbalancer.core.ServiceInstanceListSupplier;
import reactor.core.publisher.Flux;
import reactor.test.scheduler.VirtualTimeScheduler;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.stream.IntStream;

/**
 * Stands in for the blocking DiscoveryClient supplier: one list per subscription, then complete - which is what
 * makes {@code refetch-instances} necessary. Records the virtual time of every fetch.
 */
final class FakeDiscovery implements ServiceInstanceListSupplier {

    static final String SERVICE_ID = "lb-health-target";

    private final VirtualTimeScheduler scheduler;
    private final List<Duration> fetches = new ArrayList<>();
    private volatile List<ServiceInstance> instances;

    FakeDiscovery(VirtualTimeScheduler scheduler, int count) {
        this.scheduler = scheduler;
        this.instances = instances(count);
    }

    static List<ServiceInstance> instances(int count) {
        return IntStream.range(0, count)
                .mapToObj(i -> (ServiceInstance) new DefaultServiceInstance(id(i), SERVICE_ID, "10.0.0." + (i + 1),
                        8080, false))
                .toList();
    }

    static String id(int i) {
        return "i%02d".formatted(i);
    }

    @Override
    public String getServiceId() {
        return SERVICE_ID;
    }

    @Override
    public Flux<List<ServiceInstance>> get() {
        return Flux.defer(() -> {
            fetches.add(Duration.ofMillis(scheduler.now(TimeUnit.MILLISECONDS)));
            return Flux.just(instances);
        });
    }

    List<Duration> fetches() {
        return List.copyOf(fetches);
    }
}
