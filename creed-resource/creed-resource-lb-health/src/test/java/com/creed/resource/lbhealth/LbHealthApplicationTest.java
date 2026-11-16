package com.creed.resource.lbhealth;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.creed.resource.lbhealth.api.LbHealthController;
import com.creed.resource.lbhealth.downstream.DownstreamCaller;
import com.creed.resource.lbhealth.downstream.LoadRun;
import io.github.resilience4j.circuitbreaker.CallNotPermittedException;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import com.creed.resource.lbhealth.config.LbHealthConfiguration;
import com.creed.resource.lbhealth.lb.ObservableHealthCheckServiceInstanceListSupplier;
import com.creed.resource.lbhealth.upstream.TargetInstanceStates;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.cloud.client.ServiceInstance;
import org.springframework.cloud.loadbalancer.core.ServiceInstanceListSupplier;
import org.springframework.cloud.loadbalancer.support.LoadBalancerClientFactory;
import org.slf4j.LoggerFactory;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.ServerSocket;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.awaitility.Awaitility.await;

/**
 * The whole app on loopback: the 10 target instances are this app's own extra connectors on free ports, probed
 * for real over HTTP by the blocking RestTemplate. Timings are shrunk (interval 1 s, refetch 10 s, probe timeout
 * 300 + 500 ms → worst-case round 8 s) but keep the shipped ratios.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class LbHealthApplicationTest {

    private static final String SERVICE_ID = LbHealthConfiguration.TARGET_SERVICE_ID;
    /** Sorted, like {@link #ports}, so lists compare by content. */
    private static final List<Integer> PORTS = IntStream.range(0, 10).mapToObj(i -> freePort()).sorted().toList();

    @DynamicPropertySource
    static void targets(DynamicPropertyRegistry registry) {
        String prefix = "spring.cloud.discovery.client.simple.instances." + SERVICE_ID;
        for (int i = 0; i < PORTS.size(); i++) {
            int port = PORTS.get(i);
            String instanceId = "i%02d".formatted(i);
            registry.add(prefix + "[" + i + "].instance-id", () -> instanceId);
            registry.add(prefix + "[" + i + "].uri", () -> "http://localhost:" + port);
        }
        // pinned, not taken from application.yml: what these tests check needs them, whatever the yml is set to
        registry.add("spring.cloud.loadbalancer.health-check.initial-delay", () -> "0");
        registry.add("spring.cloud.loadbalancer.health-check.update-results-list", () -> "false");
        registry.add("creed.lb-health.logbook.enabled", () -> "true");
        registry.add("spring.cloud.loadbalancer.health-check.interval", () -> "1s");
        registry.add("spring.cloud.loadbalancer.health-check.refetch-instances-interval", () -> "10s");
        registry.add("creed.lb-health.health-check.connect-timeout", () -> "300ms");
        registry.add("creed.lb-health.health-check.read-timeout", () -> "500ms");
        registry.add("creed.lb-health.debug.enabled", () -> "true");
    }

    @Autowired
    LoadBalancerClientFactory loadBalancerClientFactory;
    @Autowired
    TargetInstanceStates states;
    @Autowired
    LbHealthController controller;

    @Autowired
    CircuitBreakerRegistry circuitBreakers;
    @Autowired
    DownstreamCaller downstream;
    @Autowired
    LoadRun loadRun;

    @AfterEach
    void resetTargets() {
        states.reset();
        circuitBreakers.getAllCircuitBreakers().forEach(CircuitBreaker::reset);
    }

    /**
     * The YAML as written binds on Boot 4 through resilience4j-spring-boot3 - and the defaults it leaves are what the
     * docs/resilience4j-circuit-breaker.md scenario rests on: minimumNumberOfCalls stays 100 for both (the binding sets
     * it apart from the window, so nothing caps it at backendB's 10), threshold 50 %, 60 s open, no automatic
     * OPEN -> HALF_OPEN.
     */
    @Test
    void theCircuitBreakersRunWithTheConfiguredWindowsAndTheDefaultsAroundThem() {
        CircuitBreakerConfig a = circuitBreakers.circuitBreaker("backendA").getCircuitBreakerConfig();
        CircuitBreakerConfig b = circuitBreakers.circuitBreaker("backendB").getCircuitBreakerConfig();
        assertThat(circuitBreakers.getAllCircuitBreakers()).extracting(CircuitBreaker::getName)
                .contains("backendA", "backendB");
        assertThat(a.getSlidingWindowType()).isEqualTo(CircuitBreakerConfig.SlidingWindowType.COUNT_BASED);
        assertThat(a.getSlidingWindowSize()).isEqualTo(100);
        assertThat(a.getPermittedNumberOfCallsInHalfOpenState()).isEqualTo(10);
        assertThat(b.getSlidingWindowType()).isEqualTo(CircuitBreakerConfig.SlidingWindowType.TIME_BASED);
        assertThat(b.getSlidingWindowSize()).isEqualTo(10);
        assertThat(b.getPermittedNumberOfCallsInHalfOpenState()).isEqualTo(3);
        for (CircuitBreakerConfig c : List.of(a, b)) {
            assertThat(c.getMinimumNumberOfCalls()).isEqualTo(100);
            assertThat(c.getFailureRateThreshold()).isEqualTo(50f);
            assertThat(c.getWaitIntervalFunctionInOpenState().apply(1)).isEqualTo(60_000L);
            assertThat(c.isAutomaticTransitionFromOpenToHalfOpenEnabled()).isFalse();
        }
    }

    @Test
    void aLoadRunCountsEachSecondByWhatTheBreakerLetThrough() {
        await().atMost(Duration.ofSeconds(5)).until(() -> ports(current()).size() == PORTS.size());
        PORTS.forEach(port -> states.set(port, new TargetInstanceStates.State(false, Duration.ZERO)));

        loadRun.start("backendA", 50, 3);
        await().atMost(Duration.ofSeconds(10)).until(() -> !loadRun.status().running());

        List<LoadRun.Second> timeline = loadRun.status().timeline();
        assertThat(timeline).hasSize(3);
        assertThat(timeline).allSatisfy(s -> assertThat(s.ok()).isZero());
        // 100 calls fail first (count window 100, minimum 100), every call after that is rejected
        assertThat(timeline.stream().mapToInt(s -> s.failed() + s.noInstance()).sum()).isEqualTo(100);
        assertThat(timeline.stream().mapToInt(LoadRun.Second::rejected).sum()).isEqualTo(50);
        assertThat(timeline.getLast().state()).isEqualTo("OPEN");
    }

    @Test
    void aDownServiceOpensTheBreakerAndThenItRejectsWithoutCalling() {
        await().atMost(Duration.ofSeconds(5)).until(() -> ports(current()).size() == PORTS.size());
        PORTS.forEach(port -> states.set(port, new TargetInstanceStates.State(false, Duration.ZERO)));

        // backendA: count window of 100, minimumNumberOfCalls 100 - the 100th failure is the first evaluation
        CircuitBreaker breaker = circuitBreakers.circuitBreaker("backendA");
        for (int i = 0; i < 100; i++) {
            assertThat(breaker.getState()).isEqualTo(CircuitBreaker.State.CLOSED);
            assertThatThrownBy(() -> downstream.call("backendA")).isNotInstanceOf(CallNotPermittedException.class);
        }
        assertThat(breaker.getState()).isEqualTo(CircuitBreaker.State.OPEN);
        assertThatThrownBy(() -> downstream.call("backendA")).isInstanceOf(CallNotPermittedException.class);
        assertThat(circuitBreakers.circuitBreaker("backendB").getState()).as("one breaker per backend")
                .isEqualTo(CircuitBreaker.State.CLOSED);
    }

    @Test
    void eagerLoadBuildsTheSupplierBeforeAnyRequest() {
        // Nothing has asked for an instance yet; LoadBalancerEagerContextInitializer did it on ApplicationReadyEvent.
        assertThat(loadBalancerClientFactory.getContextNames()).contains(SERVICE_ID);

        assertThat(supplier()).isInstanceOf(ObservableHealthCheckServiceInstanceListSupplier.class);
        await().atMost(Duration.ofSeconds(5)).until(() -> ports(current()).size() == PORTS.size());
    }

    @Test
    void getOnlyEverSeesCompleteRoundsWhileInstancesGoDown() {
        await().atMost(Duration.ofSeconds(5)).until(() -> ports(current()).size() == PORTS.size());
        int down = PORTS.get(3);
        int slow = PORTS.get(7);
        states.set(down, new TargetInstanceStates.State(false, Duration.ZERO));
        states.set(slow, new TargetInstanceStates.State(true, Duration.ofSeconds(2))); // > 500 ms read timeout

        List<List<Integer>> seen = new ArrayList<>();
        await().atMost(Duration.ofSeconds(8)).pollInterval(Duration.ofMillis(20)).until(() -> {
            List<Integer> ports = ports(current());
            seen.add(ports);
            return ports.size() == PORTS.size() - 2;
        });

        List<Integer> healthy = PORTS.stream().filter(p -> p != down && p != slow).toList();
        assertThat(seen).as("every read is the previous complete round or the new complete round")
                .allSatisfy(ports -> assertThat(ports).isIn(PORTS, healthy, without(slow), without(down)));
        assertThat(seen.getLast()).containsExactlyInAnyOrderElementsOf(healthy);

        Map<Integer, Integer> chosen = controller.choose(50);
        assertThat(chosen.keySet()).doesNotContain(down, slow).allMatch(healthy::contains);
    }

    @Test
    void theLoadBalancedBusinessClientReachesATargetInstance() {
        await().atMost(Duration.ofSeconds(5)).until(() -> ports(current()).size() == PORTS.size());

        Object body = controller.call();

        assertThat(body).isInstanceOf(Map.class);
        assertThat(PORTS).contains((Integer) ((Map<?, ?>) body).get("port"));
    }

    /**
     * Read from a logback appender on Logbook's logger, not from captured stdout: the console appender keeps the
     * stream it had when the (cached) context started, so an OutputCaptureExtension test sees only part of it.
     */
    @Test
    void everyProbeIsLoggedByLogbookInSplunkStyle() {
        Logger logger = (Logger) LoggerFactory.getLogger("org.zalando.logbook.Logbook");
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);
        try {
            int down = PORTS.get(5);
            states.set(down, new TargetInstanceStates.State(false, Duration.ZERO));
            String uri = "uri=http://localhost:" + down + "/api/lb-health/target/health";

            // key=value pairs; the request and its response share one correlation id; a DOWN target answers 503
            await().atMost(Duration.ofSeconds(5)).until(() -> lines(appender)
                    .anyMatch(l -> l.contains("status=503") && l.contains("\"port\":" + down)));
            String response = lines(appender).filter(l -> l.contains("status=503") && l.contains("\"port\":" + down))
                    .findFirst().orElseThrow();
            assertThat(response).startsWith("origin=remote type=response correlation=")
                    .contains("\"status\":\"DOWN\""); // the body: a Map, its key order is not fixed
            String correlation = response.split(" ")[2];
            assertThat(lines(appender).filter(l -> l.contains(correlation) && l.contains("type=request")))
                    .singleElement().asString()
                    .startsWith("origin=local type=request " + correlation)
                    .contains("method=GET", uri);

            // both lines carry the probe's round in the MDC (debug is on here): its number and what started it
            List<ILoggingEvent> pair = List.copyOf(appender.list).stream()
                    .filter(e -> e.getFormattedMessage().contains(correlation)).toList();
            assertThat(pair).hasSize(2).allSatisfy(e -> {
                assertThat(e.getMDCPropertyMap().get(ObservableHealthCheckServiceInstanceListSupplier.MDC_TRIGGER))
                        .isIn("REFETCH", "INTERVAL");
                assertThat(e.getMDCPropertyMap().get(ObservableHealthCheckServiceInstanceListSupplier.MDC_ROUND))
                        .matches("\\d+");
            });
            assertThat(pair.get(0).getMDCPropertyMap()).isEqualTo(pair.get(1).getMDCPropertyMap());
        } finally {
            logger.detachAppender(appender);
        }
    }

    private static java.util.stream.Stream<String> lines(ListAppender<ILoggingEvent> appender) {
        return List.copyOf(appender.list).stream().map(ILoggingEvent::getFormattedMessage);
    }

    private ServiceInstanceListSupplier supplier() {
        return loadBalancerClientFactory.getInstance(SERVICE_ID, ServiceInstanceListSupplier.class);
    }

    private List<ServiceInstance> current() {
        return supplier().get().next().block(Duration.ofSeconds(10));
    }

    private static List<Integer> ports(List<ServiceInstance> instances) {
        return instances == null ? List.of() : instances.stream().map(ServiceInstance::getPort).sorted().toList();
    }

    private static List<Integer> without(int port) {
        return PORTS.stream().filter(p -> p != port).toList();
    }

    /**
     * Free on LOOPBACK, which is where the probes go: a wildcard ServerSocket(0) can be handed a port another process
     * holds on 127.0.0.1 only (IntelliJ listens on 127.0.0.1:50000-50002) - our connector then binds it too, but
     * {@code localhost:<port>} reaches the other process, and those instances look DOWN from the first round.
     */
    private static int freePort() {
        try (ServerSocket socket = new ServerSocket(0, 0, java.net.InetAddress.getLoopbackAddress())) {
            return socket.getLocalPort();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
