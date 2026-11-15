package com.creed.simple.lb.config;

import com.creed.simple.lb.StickyMetadataServiceInstanceListSupplier;
import com.creed.simple.lb.config.PartnerLoadBalancerConfiguration.LoggingHealthCheckServiceInstanceListSupplier;
import lombok.extern.slf4j.Slf4j;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.cloud.client.DefaultServiceInstance;
import org.springframework.cloud.client.ServiceInstance;
import org.springframework.cloud.client.loadbalancer.DefaultRequest;
import org.springframework.cloud.client.loadbalancer.RequestData;
import org.springframework.cloud.client.loadbalancer.RequestDataContext;
import org.springframework.cloud.client.loadbalancer.LoadBalancerProperties;
import org.springframework.cloud.loadbalancer.cache.DefaultLoadBalancerCacheManager;
import org.springframework.cloud.loadbalancer.cache.LoadBalancerCacheProperties;
import org.springframework.cloud.loadbalancer.core.CachingServiceInstanceListSupplier;
import org.springframework.cloud.loadbalancer.core.HealthCheckServiceInstanceListSupplier;
import org.springframework.cloud.loadbalancer.core.ServiceInstanceListSupplier;
import org.springframework.cloud.loadbalancer.support.LoadBalancerClientFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.net.URI;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Repro: {@code ServiceInstanceListSupplier#get} returns fewer instances than are registered although every health
 * check succeeds. The chain is the one {@link PartnerLoadBalancerConfiguration#healthCheckedSupplier} builds -
 * discovery → {@link LoggingHealthCheckServiceInstanceListSupplier} → {@link CachingServiceInstanceListSupplier} -
 * minus the toggle, over 16 instances that all answer healthy, the n-th after n × 15 ms.
 *
 * <p>Two mechanisms, both Spring Cloud LoadBalancer's own (5.0.3):
 * <ul>
 *   <li>{@code health-check.update-results-list} (default {@code true}): each round starts from an empty list and
 *   emits it again after every instance that answers - [1], [1,2] ... [1..16]. {@code replay(1)} keeps only the last
 *   emission, so until a round has heard from every instance, {@code get()} sees part of the list. Rounds repeat
 *   every {@code interval}, so this comes back each time.</li>
 *   <li>{@code withCaching()} reads {@code delegate.get().take(1)} on a miss - the FIRST emission - and keeps it for
 *   the cache TTL (35 s by default), even after the health check has reached all 16.</li>
 * </ul>
 * The log lines ({@code [LB-PARTIAL]}) show what the load balancer would have been handed at each moment.
 *
 * <p>It also breaks stickiness: payment's {@link StickyMetadataServiceInstanceListSupplier} filters that same list, so
 * a request pinned to a HEALTHY instance that the partial list lacks falls back to another instance.
 */
@Slf4j
class HealthCheckPartialListTest {

    private static final String SERVICE_ID = "catalog-resource";
    private static final int INSTANCES = 16;
    private static final Duration INTERVAL = Duration.ofMillis(500);

    private HealthCheckServiceInstanceListSupplier healthCheck;

    @AfterEach
    void stopHealthCheck() {
        if (healthCheck != null) {
            healthCheck.destroy();
        }
    }

    @Test
    void defaultSettingsHandTheLoadBalancerAPartialListThoughEveryInstanceIsHealthy() throws InterruptedException {
        healthCheck = healthCheck(true);
        ServiceInstanceListSupplier cached = cached(healthCheck);

        int firstCached = size(cached);
        log.info("[LB-PARTIAL] update-results-list=true: first get() through the cache = {}/{} instances",
                firstCached, INSTANCES);

        Thread.sleep(400); // round 1 has heard from all 16 by now (the last answers at 240 ms)
        int healthCheckNow = size(healthCheck);
        int cachedNow = size(cached);
        log.info("[LB-PARTIAL] update-results-list=true: 400 ms later the health check has {}/{}, the cache still"
                + " hands out {}/{} (kept for the TTL)", healthCheckNow, INSTANCES, cachedNow, INSTANCES);

        List<Integer> sampled = sample(healthCheck, "update-results-list=true");

        assertThat(firstCached).as("the cache stored the health check's first emission").isLessThan(INSTANCES);
        assertThat(healthCheckNow).as("a finished round does reach every instance").isEqualTo(INSTANCES);
        assertThat(cachedNow).as("the cache keeps the partial list after that").isEqualTo(firstCached);
        assertThat(sampled.subList(sampled.indexOf(INSTANCES), sampled.size()))
                .as("after reaching %d, a new round shrinks the list again", INSTANCES)
                .anyMatch(size -> size < INSTANCES);
    }

    @Test
    void withUpdateResultsListOffEveryReadIsTheFullList() throws InterruptedException {
        healthCheck = healthCheck(false);
        ServiceInstanceListSupplier cached = cached(healthCheck);

        int firstCached = size(cached);
        log.info("[LB-PARTIAL] update-results-list=false: first get() through the cache = {}/{} instances",
                firstCached, INSTANCES);

        List<Integer> sampled = sample(healthCheck, "update-results-list=false");

        assertThat(firstCached).isEqualTo(INSTANCES);
        assertThat(sampled).allMatch(size -> size == INSTANCES);
    }

    @Test
    void aPartialListSendsAStickyRequestAwayFromItsHealthyInstance() {
        // payment-resource's chain: health check -> cache -> sticky filter; p2 answers its health check after p1
        healthCheck = healthCheck(true, payment());
        ServiceInstanceListSupplier sticky = new StickyMetadataServiceInstanceListSupplier(cached(healthCheck));

        List<ServiceInstance> routed = sticky.get(pinnedTo("sticky-p2")).next().block(Duration.ofSeconds(5));
        List<String> ids = routed.stream().map(ServiceInstance::getInstanceId).toList();
        log.info("[LB-PARTIAL] stickyId=sticky-p2 (p2 is healthy) was routed to {} - the cache holds {}", ids, ids);

        assertThat(ids).as("p2 is healthy but missing from the cached partial list: the sticky filter falls back")
                .containsExactly("p1");
    }

    /** get() every 25 ms for 2.5 s (about four health-check rounds), logged one line per read. */
    private static List<Integer> sample(ServiceInstanceListSupplier supplier, String label)
            throws InterruptedException {
        List<Integer> sizes = new ArrayList<>();
        long start = System.nanoTime();
        for (int i = 0; i < 100; i++) {
            int size = size(supplier);
            sizes.add(size);
            log.info("[LB-PARTIAL] {} t={}ms health-check get() = {}/{}{}", label,
                    (System.nanoTime() - start) / 1_000_000, size, INSTANCES, size < INSTANCES ? "  <-- partial" : "");
            Thread.sleep(25);
        }
        return sizes;
    }

    /** What RoundRobinLoadBalancer takes: {@code supplier.get(request).next()}. */
    private static int size(ServiceInstanceListSupplier supplier) {
        List<ServiceInstance> instances = supplier.get().next().block(Duration.ofSeconds(5));
        return instances == null ? 0 : instances.size();
    }

    private static HealthCheckServiceInstanceListSupplier healthCheck(boolean updateResultsList) {
        return healthCheck(updateResultsList, discovery());
    }

    private static HealthCheckServiceInstanceListSupplier healthCheck(boolean updateResultsList,
            ServiceInstanceListSupplier discovery) {
        LoadBalancerProperties properties = new LoadBalancerProperties();
        properties.getHealthCheck().setInitialDelay(Duration.ZERO);
        properties.getHealthCheck().setInterval(INTERVAL);
        properties.getHealthCheck().setUpdateResultsList(updateResultsList);
        LoadBalancerClientFactory factory = mock(LoadBalancerClientFactory.class);
        when(factory.getProperties(SERVICE_ID)).thenReturn(properties);

        HealthCheckServiceInstanceListSupplier supplier = new LoggingHealthCheckServiceInstanceListSupplier(
                discovery, factory,
                // every instance is healthy; instance n answers after n x 15 ms, so a round lasts ~240 ms
                (instance, path) -> Mono.just(true).delayElement(
                        Duration.ofMillis(15L * Integer.parseInt(instance.getInstanceId().substring(1)))));
        supplier.afterPropertiesSet(); // what the LB child context does: starts the health-check loop
        return supplier;
    }

    /** Spring Cloud's own caching layer with its defaults (TTL 35 s), as {@code withCaching()} adds it. */
    private static ServiceInstanceListSupplier cached(ServiceInstanceListSupplier delegate) {
        return new CachingServiceInstanceListSupplier(delegate,
                new DefaultLoadBalancerCacheManager(new LoadBalancerCacheProperties()));
    }

    private static ServiceInstanceListSupplier discovery() {
        List<ServiceInstance> instances = new ArrayList<>();
        for (int n = 1; n <= INSTANCES; n++) {
            instances.add(new DefaultServiceInstance("i" + n, SERVICE_ID, "localhost", 18000 + n, true));
        }
        return registry(instances);
    }

    /** Two instances pinned by metadata, like payment-resource's in application.yml. */
    private static ServiceInstanceListSupplier payment() {
        return registry(List.of(
                new DefaultServiceInstance("p1", SERVICE_ID, "localhost", 18093, true,
                        Map.of(StickyMetadataServiceInstanceListSupplier.STICKY_METADATA_KEY, "sticky-p1")),
                new DefaultServiceInstance("p2", SERVICE_ID, "localhost", 18094, true,
                        Map.of(StickyMetadataServiceInstanceListSupplier.STICKY_METADATA_KEY, "sticky-p2"))));
    }

    /** A request carrying the sticky cookie, shaped like the one the route planner builds. */
    private static DefaultRequest<RequestDataContext> pinnedTo(String stickyId) {
        MultiValueMap<String, String> cookies = new LinkedMultiValueMap<>();
        cookies.add(StickyMetadataServiceInstanceListSupplier.STICKY_COOKIE, stickyId);
        return new DefaultRequest<>(new RequestDataContext(new RequestData(HttpMethod.GET,
                URI.create("https://" + SERVICE_ID + "/api"), new HttpHeaders(), cookies, new HashMap<>())));
    }

    private static ServiceInstanceListSupplier registry(List<ServiceInstance> instances) {
        return new ServiceInstanceListSupplier() {
            @Override
            public String getServiceId() {
                return SERVICE_ID;
            }

            @Override
            public Flux<List<ServiceInstance>> get() {
                return Flux.just(List.copyOf(instances));
            }
        };
    }
}
