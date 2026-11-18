package com.creed.resource.lbhealth;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.creed.resource.lbhealth.api.LbHealthController;
import com.creed.resource.lbhealth.downstream.DownstreamCaller;
import com.creed.resource.lbhealth.downstream.LoadRun;
import io.github.resilience4j.bulkhead.Bulkhead;
import io.github.resilience4j.bulkhead.BulkheadConfig;
import io.github.resilience4j.bulkhead.BulkheadFullException;
import io.github.resilience4j.bulkhead.BulkheadRegistry;
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
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.web.client.RestClient;
import org.springframework.cloud.client.ServiceInstance;
import org.springframework.cloud.loadbalancer.core.ServiceInstanceListSupplier;
import org.springframework.cloud.loadbalancer.support.LoadBalancerClientFactory;
import org.slf4j.LoggerFactory;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.HttpServerErrorException;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.ServerSocket;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;
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
    private static final String BULKHEAD_CLUSTER = "lb-health-bulkhead";
    /** The bulkhead cluster's 3 instances - also this app's own connectors. */
    private static final List<Integer> BULKHEAD_PORTS = IntStream.range(0, 3).mapToObj(i -> freePort()).sorted().toList();

    @DynamicPropertySource
    static void targets(DynamicPropertyRegistry registry) {
        String prefix = "spring.cloud.discovery.client.simple.instances." + SERVICE_ID;
        for (int i = 0; i < PORTS.size(); i++) {
            int port = PORTS.get(i);
            String instanceId = "i%02d".formatted(i);
            registry.add(prefix + "[" + i + "].instance-id", () -> instanceId);
            registry.add(prefix + "[" + i + "].uri", () -> "http://localhost:" + port);
        }
        for (int i = 0; i < BULKHEAD_PORTS.size(); i++) {
            int port = BULKHEAD_PORTS.get(i);
            String instanceId = "b%02d".formatted(i);
            String bulkheadPrefix = "spring.cloud.discovery.client.simple.instances." + BULKHEAD_CLUSTER;
            registry.add(bulkheadPrefix + "[" + i + "].instance-id", () -> instanceId);
            registry.add(bulkheadPrefix + "[" + i + "].uri", () -> "http://localhost:" + port);
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

    @LocalServerPort
    int port;
    @Autowired
    LoadBalancerClientFactory loadBalancerClientFactory;
    @Autowired
    TargetInstanceStates states;
    @Autowired
    LbHealthController controller;

    @Autowired
    CircuitBreakerRegistry circuitBreakers;
    @Autowired
    BulkheadRegistry bulkheads;
    @Autowired
    DownstreamCaller downstream;
    @Autowired
    LoadRun loadRun;

    @AfterEach
    void resetTargets() {
        states.reset();
        circuitBreakers.getAllCircuitBreakers().forEach(CircuitBreaker::reset);
        downstream.guards().values().forEach(g -> g.peak().set(0));
    }

    /**
     * application.yml's {@code configs.default}, as bound on Boot 4 by resilience4j-spring-boot3 2.3.0 - and the claim
     * that each cluster has its OWN breaker and bulkhead, named after it, built from that default (the bulkhead cluster's
     * bulkhead overridden by {@code bulkhead.instances}). docs/resilience4j-circuit-breaker.md §4 rests on these values.
     */
    @Test
    void everyClusterGetsItsOwnBreakerAndBulkheadFromConfigsDefault() {
        assertThat(downstream.guards()).containsOnlyKeys(SERVICE_ID, BULKHEAD_CLUSTER);
        CircuitBreaker target = circuitBreakers.find(SERVICE_ID).orElseThrow();
        CircuitBreaker other = circuitBreakers.find(BULKHEAD_CLUSTER).orElseThrow();
        assertThat(target).isNotSameAs(other);
        assertThat(circuitBreakers.getAllCircuitBreakers()).extracting(CircuitBreaker::getName)
                .containsExactlyInAnyOrder(SERVICE_ID, BULKHEAD_CLUSTER);

        for (CircuitBreaker breaker : List.of(target, other)) {
            CircuitBreakerConfig c = breaker.getCircuitBreakerConfig();
            assertThat(c.getSlidingWindowType()).isEqualTo(CircuitBreakerConfig.SlidingWindowType.COUNT_BASED);
            assertThat(c.getSlidingWindowSize()).isEqualTo(200);
            assertThat(c.getMinimumNumberOfCalls()).isEqualTo(50);
            assertThat(c.getFailureRateThreshold()).isEqualTo(60f);
            assertThat(c.getWaitIntervalFunctionInOpenState().apply(1)).isEqualTo(15_000L);
            assertThat(c.getPermittedNumberOfCallsInHalfOpenState()).isEqualTo(10);
            assertThat(c.isAutomaticTransitionFromOpenToHalfOpenEnabled()).isTrue();
            assertThat(c.getSlowCallRateThreshold()).isEqualTo(100f);
            assertThat(c.getSlowCallDurationThreshold()).isEqualTo(Duration.ofSeconds(60));
            assertThat(c.getMaxWaitDurationInHalfOpenState()).as("default: wait for the trials forever")
                    .isEqualTo(Duration.ZERO);
            // the predicate class from the yml: a 404 is a success, a 503 a failure
            assertThat(c.getRecordExceptionPredicate().test(HttpClientErrorException.create(HttpStatus.NOT_FOUND,
                    "", HttpHeaders.EMPTY, new byte[0], null))).isFalse();
            assertThat(c.getRecordExceptionPredicate().test(HttpServerErrorException.create(
                    HttpStatus.SERVICE_UNAVAILABLE, "", HttpHeaders.EMPTY, new byte[0], null))).isTrue();
            assertThat(c.getIgnoreExceptionPredicate().test(new IllegalStateException())).as("nothing ignored")
                    .isFalse();
        }
        assertThat(bulkheads.bulkhead(SERVICE_ID).getBulkheadConfig().getMaxConcurrentCalls()).isEqualTo(20);
        assertThat(bulkheads.bulkhead(BULKHEAD_CLUSTER).getBulkheadConfig().getMaxConcurrentCalls()).isEqualTo(10);
        assertThat(bulkheads.bulkhead(BULKHEAD_CLUSTER).getBulkheadConfig().getMaxWaitDuration())
                .isEqualTo(Duration.ofSeconds(60));
        assertThat(bulkheads.bulkhead(BULKHEAD_CLUSTER).getBulkheadConfig().isFairCallHandlingEnabled()).isTrue();
        assertThat(bulkheads.bulkhead(SERVICE_ID).getBulkheadConfig().getMaxWaitDuration()).isZero();
    }

    /**
     * {@code automatic-transition-from-open-to-half-open-enabled: true}: OPEN -> HALF_OPEN with no call at all. The
     * bound config with the wait shortened to 300 ms - 15 s of real time is too long for a test.
     */
    @Test
    void automaticTransitionMovesToHalfOpenWithoutAnyCall() {
        CircuitBreaker breaker = CircuitBreaker.of("auto", CircuitBreakerConfig
                .from(circuitBreakers.find(SERVICE_ID).orElseThrow().getCircuitBreakerConfig())
                .waitDurationInOpenState(Duration.ofMillis(300)).build());
        breaker.transitionToOpenState();
        await().atMost(Duration.ofSeconds(3)).until(() -> breaker.getState() == CircuitBreaker.State.HALF_OPEN);
    }

    @Test
    void aLoadRunCountsEachSecondByWhatTheBreakerLetThrough() {
        await().atMost(Duration.ofSeconds(5)).until(() -> ports(current()).size() == PORTS.size());
        PORTS.forEach(port -> states.set(port, new TargetInstanceStates.State(false, Duration.ZERO)));

        loadRun.start(SERVICE_ID, 50, 3, false);
        await().atMost(Duration.ofSeconds(10)).until(() -> !loadRun.status().running());

        List<LoadRun.Second> timeline = loadRun.status().timeline();
        assertThat(timeline).hasSize(3);
        assertThat(timeline).allSatisfy(s -> assertThat(s.ok()).isZero());
        // empty window, minimum 50: the 50th failure is the first evaluation, 100 % >= 60 % - every call after it is
        // rejected (15 s OPEN, longer than the run)
        assertThat(timeline.stream().mapToInt(s -> s.failed() + s.noInstance()).sum()).isEqualTo(50);
        assertThat(timeline.stream().mapToInt(LoadRun.Second::rejected).sum()).isEqualTo(100);
        assertThat(timeline.getLast().state()).isEqualTo("OPEN");
    }

    @Test
    void aDownClusterOpensOnlyItsOwnBreaker() {
        await().atMost(Duration.ofSeconds(5)).until(() -> ports(current()).size() == PORTS.size());
        PORTS.forEach(port -> states.set(port, new TargetInstanceStates.State(false, Duration.ZERO)));

        CircuitBreaker breaker = circuitBreakers.find(SERVICE_ID).orElseThrow();
        for (int i = 0; i < 50; i++) {
            assertThat(breaker.getState()).isEqualTo(CircuitBreaker.State.CLOSED);
            assertThat(breaker.getMetrics().getFailureRate()).as("below minimum-number-of-calls: not evaluated")
                    .isEqualTo(-1f);
            // 503 from a dead instance, or - once a health-check round (1 s here) has emptied the list - no instance at all
            assertThatThrownBy(() -> downstream.call(SERVICE_ID))
                    .isInstanceOfAny(HttpServerErrorException.class, IllegalStateException.class);
        }
        assertThat(breaker.getState()).isEqualTo(CircuitBreaker.State.OPEN);
        assertThatThrownBy(() -> downstream.call(SERVICE_ID)).isInstanceOf(CallNotPermittedException.class);

        // the other cluster is untouched and still answers
        assertThat(circuitBreakers.find(BULKHEAD_CLUSTER).orElseThrow().getState())
                .isEqualTo(CircuitBreaker.State.CLOSED);
        assertThat(downstream.call(BULKHEAD_CLUSTER).get("port")).isIn(BULKHEAD_PORTS);
    }

    /**
     * The bulkhead cluster answers after 1 s; 40 calls/s for 3 s, each on its own thread. Its bulkhead admits 10 at a
     * time and, with {@code max-wait-duration: 60s}, QUEUES the rest instead of rejecting them: all 120 end up
     * succeeding, 10 per second, so the run needs ~12 s and the last calls waited ~9 s for a permit. The concurrency at
     * the cluster still never exceeds 10. The bulkhead is OUTSIDE the breaker, so the breaker's clock starts only once a
     * call has its permit: every call is recorded with ~1 s, however long it queued. The other cluster's breaker and
     * bulkhead see nothing.
     */
    @Test
    void aSlowClusterQueuesInItsBulkheadForUpToMaxWaitDurationWithoutTouchingTheBreaker() {
        BULKHEAD_PORTS.forEach(port -> states.set(port, new TargetInstanceStates.State(true, Duration.ofSeconds(1))));
        AtomicLong longestRecordedMs = new AtomicLong();
        downstream.guards().get(BULKHEAD_CLUSTER).breaker().getEventPublisher().onSuccess(
                e -> longestRecordedMs.accumulateAndGet(e.getElapsedDuration().toMillis(), Math::max));

        loadRun.start(BULKHEAD_CLUSTER, 40, 3, true);
        await().atMost(Duration.ofSeconds(30)).until(() -> !loadRun.status().running());

        List<LoadRun.Second> timeline = loadRun.status().timeline();
        assertThat(timeline.stream().mapToInt(LoadRun.Second::ok).sum()).as("nobody turned away").isEqualTo(120);
        assertThat(timeline).allSatisfy(s -> assertThat(s.bulkheadFull() + s.failed() + s.noInstance() + s.rejected())
                .isZero());
        assertThat(timeline).allSatisfy(s -> assertThat(s.ok()).isLessThanOrEqualTo(11));
        // the queue: at the end of second 3, 120 started, ~20 done, 10 in flight, ~90 waiting
        assertThat(timeline.get(2).waiting()).isBetween(80, 95);
        assertThat(timeline.get(2).inFlight()).isEqualTo(10);
        assertThat(timeline.size()).as("drain rows after the 3 s of sending").isBetween(11, 14);
        assertThat(timeline.getLast().waiting() + timeline.getLast().inFlight()).isZero();

        DownstreamCaller.Guard guard = downstream.guards().get(BULKHEAD_CLUSTER);
        assertThat(guard.peak().get()).as("never more than max-concurrent-calls at the cluster").isEqualTo(10);
        CircuitBreaker breaker = guard.breaker();
        assertThat(breaker.getState()).isEqualTo(CircuitBreaker.State.CLOSED);
        assertThat(breaker.getMetrics().getNumberOfBufferedCalls()).isEqualTo(120);
        assertThat(breaker.getMetrics().getNumberOfFailedCalls()).isZero();
        assertThat(longestRecordedMs.get()).as("the bulkhead wait is outside the breaker's clock")
                .isBetween(900L, 3_000L);
        assertThat(breaker.getMetrics().getNumberOfSlowCalls()).isZero();
        assertThat(downstream.guards().get(SERVICE_ID).breaker().getMetrics().getNumberOfBufferedCalls()).isZero();
        assertThat(downstream.guards().get(SERVICE_ID).peak().get()).isZero();
    }

    /**
     * {@code lb-health-target}'s bulkhead (20, no wait) turns calls away at once, and with the bulkhead OUTSIDE the
     * breaker those rejections never reach it: the window holds exactly the calls that went out. (With the bulkhead
     * inside - resilience4j's annotation order - all 100 were in the window, the 44 rejections as successes.)
     */
    @Test
    void withTheBulkheadOutsideTheBreakerARejectionIsNotInTheWindow() {
        await().atMost(Duration.ofSeconds(5)).until(() -> ports(current()).size() == PORTS.size());
        // 400 ms: below the probes' 500 ms read timeout, so the health check keeps all 10 in the list
        PORTS.forEach(port -> states.set(port, new TargetInstanceStates.State(true, Duration.ofMillis(400))));

        loadRun.start(SERVICE_ID, 100, 1, true);
        await().atMost(Duration.ofSeconds(10)).until(() -> !loadRun.status().running());

        List<LoadRun.Second> timeline = loadRun.status().timeline();
        int ok = timeline.stream().mapToInt(LoadRun.Second::ok).sum();
        int full = timeline.stream().mapToInt(LoadRun.Second::bulkheadFull).sum();
        assertThat(ok + full).isEqualTo(100);
        assertThat(full).as("~40 in flight wanted, 20 allowed").isGreaterThan(20);
        CircuitBreaker.Metrics m = downstream.guards().get(SERVICE_ID).breaker().getMetrics();
        assertThat(m.getNumberOfBufferedCalls()).as("only the calls that went out").isEqualTo(ok);
        assertThat(m.getNumberOfSuccessfulCalls()).isEqualTo(ok);
        assertThat(m.getNumberOfFailedCalls()).isZero();
        assertThat(downstream.guards().get(SERVICE_ID).peak().get()).isEqualTo(20);
    }

    /**
     * After {@code max-wait-duration} without a permit the call is turned away: {@code BulkheadFullException}. The bound
     * config with the wait shortened to 300 ms - 60 s of real time is too long for a test.
     */
    @Test
    void aCallStillWithoutAPermitAfterMaxWaitDurationIsRejected() throws Exception {
        Bulkhead bulkhead = Bulkhead.of("wait", BulkheadConfig
                .from(bulkheads.bulkhead(BULKHEAD_CLUSTER).getBulkheadConfig())
                .maxConcurrentCalls(1).maxWaitDuration(Duration.ofMillis(300)).build());
        bulkhead.acquirePermission(); // the only permit, never released here
        long started = System.nanoTime();
        assertThatThrownBy(bulkhead::acquirePermission).isInstanceOf(BulkheadFullException.class);
        assertThat(Duration.ofNanos(System.nanoTime() - started)).isBetween(Duration.ofMillis(250),
                Duration.ofSeconds(2));
    }

    /**
     * The actuator: resilience4j's endpoints and metrics work on Boot 4; the health component is our replacement for
     * its Boot 3 indicator - an OPEN breaker shows as OPEN in the details but does NOT take this app's health DOWN
     * ({@code allow-health-indicator-to-fail} is false).
     */
    @Test
    @SuppressWarnings("unchecked")
    void theActuatorShowsEveryClustersBreakerAndAnOpenOneDoesNotTakeTheAppDown() {
        await().atMost(Duration.ofSeconds(5)).until(() -> ports(current()).size() == PORTS.size());
        PORTS.forEach(port -> states.set(port, new TargetInstanceStates.State(false, Duration.ZERO)));
        for (int i = 0; i < 50; i++) {
            // 503 from a dead instance, or - once a health-check round (1 s here) has emptied the list - no instance at all
            assertThatThrownBy(() -> downstream.call(SERVICE_ID))
                    .isInstanceOfAny(HttpServerErrorException.class, IllegalStateException.class);
        }
        RestClient actuator = RestClient.create("http://localhost:" + port + "/api/lb-health/actuator");

        Map<String, Object> health = actuator.get().uri("/health").retrieve().body(Map.class);
        assertThat(health).containsEntry("status", "UP");
        Map<String, Object> components = (Map<String, Object>) health.get("components");
        assertThat(components).doesNotContainKeys("discoveryComposite", "reactiveDiscoveryClients");
        Map<String, Object> breakers = (Map<String, Object>) components.get("circuitBreakers");
        assertThat(breakers).containsEntry("status", "UP");
        Map<String, Map<String, Object>> details = (Map<String, Map<String, Object>>) breakers.get("details");
        assertThat(details).containsOnlyKeys(SERVICE_ID, BULKHEAD_CLUSTER);
        assertThat(details.get(SERVICE_ID)).containsEntry("state", "OPEN").containsEntry("failedCalls", 50);
        assertThat(details.get(BULKHEAD_CLUSTER)).containsEntry("state", "CLOSED");

        assertThat(actuator.get().uri("/circuitbreakers").retrieve().body(String.class))
                .contains("\"" + SERVICE_ID + "\"", "\"" + BULKHEAD_CLUSTER + "\"");
        assertThat(actuator.get().uri("/bulkheads").retrieve().body(String.class))
                .contains(SERVICE_ID, BULKHEAD_CLUSTER);
        assertThat(actuator.get().uri("/circuitbreakerevents/{name}/STATE_TRANSITION", SERVICE_ID).retrieve()
                .body(String.class)).contains("CLOSED_TO_OPEN");
        Map<String, Object> open = actuator.get()
                .uri("/metrics/resilience4j.circuitbreaker.state?tag=name:{n}&tag=state:open", SERVICE_ID)
                .retrieve().body(Map.class);
        assertThat((List<Map<String, Object>>) open.get("measurements")).singleElement()
                .satisfies(m -> assertThat(((Number) m.get("value")).doubleValue()).isEqualTo(1.0));
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
