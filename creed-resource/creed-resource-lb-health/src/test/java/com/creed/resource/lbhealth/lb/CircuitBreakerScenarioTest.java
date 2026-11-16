package com.creed.resource.lbhealth.lb;

import com.creed.resource.lbhealth.lb.ScriptedProbes.Mode;
import com.creed.resource.lbhealth.lb.ScriptedProbes.Outcome;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.cloud.client.ServiceInstance;
import org.springframework.cloud.client.loadbalancer.LoadBalancerProperties;
import org.springframework.cloud.client.loadbalancer.reactive.ReactiveLoadBalancer;
import org.springframework.cloud.loadbalancer.core.HealthCheckServiceInstanceListSupplier;
import reactor.test.scheduler.VirtualTimeScheduler;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * The question of docs/resilience4j-circuit-breaker.md, run: {@code interval 60s}, {@code refetch-instances-interval
 * 180s}, a health check that succeeds at 0 s, the server down at 1 s, and the backend calling it all the time.
 *
 * <p>Everything real except the network: Spring Cloud LoadBalancer 5.0.3's {@link HealthCheckServiceInstanceListSupplier}
 * (scripted probes, {@code update-results-list=false}), resilience4j 2.3.0's {@link CircuitBreaker} built with the same
 * builder calls the Spring binding makes from application.yml ({@code LbHealthApplicationTest} pins that the bound
 * configs carry these values), and round robin over the supplier's current list, as {@code RoundRobinLoadBalancer}
 * does. One virtual clock drives all of it: the breaker's {@code clock} (TIME_BASED buckets, OPEN wait) is the
 * Reactor virtual time.
 *
 * <p>"0 s" is the round at virtual 60 s; traffic flows from virtual 0 s, so both windows are full of successes when the
 * server goes down - the steady state, not a cold start. A down instance refuses connections: its probe errors at once,
 * and so does a call the load balancer sends to it. Run with {@code -Dscenario.print} to print each timeline.
 */
class CircuitBreakerScenarioTest {

    private static final int INSTANCES = 10;
    private static final Duration INTERVAL = Duration.ofSeconds(60);
    private static final Duration REFETCH = Duration.ofSeconds(180);
    /** Scenario second 0 = this virtual second: the interval round after the first one. */
    private static final long ZERO_AT_MS = 60_000;
    /** Wall-clock origin of the breaker's clock, on a whole second so TIME_BASED buckets fall on scenario seconds. */
    private static final long EPOCH_MS = 1_800_000_000_000L;

    private VirtualTimeScheduler scheduler;
    private FakeDiscovery discovery;
    private ScriptedProbes probes;
    private HealthCheckServiceInstanceListSupplier supplier;

    @BeforeEach
    void virtualTime() {
        scheduler = VirtualTimeScheduler.getOrSet();
        discovery = new FakeDiscovery(scheduler, INSTANCES);
        probes = new ScriptedProbes(scheduler, Mode.ASYNC).all(Outcome.HEALTHY, Duration.ZERO);
    }

    @AfterEach
    void stop() {
        if (supplier != null) {
            supplier.destroy();
        }
        VirtualTimeScheduler.reset();
    }

    // ------------------------------------------------------------------------------------------ the config, mirrored

    /** {@code backendA: slidingWindowSize 100} - everything else the default. */
    private CircuitBreakerConfig backendA() {
        return onVirtualTime(CircuitBreakerConfig.custom().slidingWindowSize(100));
    }

    /** {@code backendB: slidingWindowSize 10, permittedNumberOfCallsInHalfOpenState 3, TIME_BASED}. */
    private CircuitBreakerConfig backendB() {
        return onVirtualTime(CircuitBreakerConfig.custom().slidingWindowSize(10).permittedNumberOfCallsInHalfOpenState(3)
                .slidingWindowType(CircuitBreakerConfig.SlidingWindowType.TIME_BASED));
    }

    private CircuitBreakerConfig onVirtualTime(CircuitBreakerConfig.Builder builder) {
        Clock clock = new Clock() {
            @Override
            public ZoneId getZone() {
                return ZoneOffset.UTC;
            }

            @Override
            public Clock withZone(ZoneId zone) {
                return this;
            }

            @Override
            public Instant instant() {
                return Instant.ofEpochMilli(EPOCH_MS + scheduler.now(TimeUnit.MILLISECONDS));
            }
        };
        return builder.clock(clock).currentTimestampFunction(c -> scheduler.now(TimeUnit.NANOSECONDS),
                TimeUnit.NANOSECONDS).build();
    }

    @Test
    void theMirroredConfigsKeepTheDefaultMinimumOf100Calls() {
        assertThat(backendA().getMinimumNumberOfCalls()).isEqualTo(100);
        assertThat(backendB().getMinimumNumberOfCalls()).as("TIME_BASED: never capped").isEqualTo(100);
        // the same setters on a COUNT_BASED window of 10 do not cap it either: 100 calls never fit, it never opens
        assertThat(CircuitBreakerConfig.custom().slidingWindowSize(10).build().getMinimumNumberOfCalls()).isEqualTo(100);
        // only slidingWindow(size, minimum, type, strategy) caps a COUNT_BASED minimum at the window size
        assertThat(CircuitBreakerConfig.custom().slidingWindow(10, 100,
                CircuitBreakerConfig.SlidingWindowType.COUNT_BASED,
                CircuitBreakerConfig.DEFAULT_SLIDING_WINDOW_SYNCHRONIZATION_STRATEGY).build().getMinimumNumberOfCalls())
                .isEqualTo(10);
    }

    // ----------------------------------------------------------------------------------- the whole service goes down

    @Test
    void backendA_allDown_20rps_opensAfter50FailuresThenTheDownstreamIsLeftAlone() {
        Result r = run(backendA(), 20, all(), 1, null, 200);

        // the window held the last 100 calls, all successes; 50 failures make 50 % - at 20/s that is 2.5 s
        assertThat(r.transitions()).containsExactly(
                "t=3.45s CLOSED -> OPEN",
                "t=63.50s OPEN -> HALF_OPEN", "t=63.95s HALF_OPEN -> OPEN",       // 10 trial calls, empty list
                "t=124.00s OPEN -> HALF_OPEN", "t=124.45s HALF_OPEN -> OPEN",
                "t=184.50s OPEN -> HALF_OPEN", "t=184.95s HALF_OPEN -> OPEN");
        assertThat(r.failedDownstream()).as("calls that reached a dead instance").isEqualTo(50);
        assertThat(r.noInstance()).as("trial calls with an empty list: recorded, nothing sent").isEqualTo(30);
        assertThat(r.lbSizeAt(59)).as("the 0 s round's list: stale for 60 s").isEqualTo(10);
        assertThat(r.lbSizeAt(60)).isZero();
    }

    @Test
    void backendB_allDown_20rps_opensWhenHalfTheLast10SecondsFailed() {
        Result r = run(backendB(), 20, all(), 1, null, 200);

        // 10 one-second buckets of 20 calls. At 5.95 s the window is seconds -4..5: 100 successes (-4..0) and 100
        // failures (1..5) - 50 %, and the check is >=
        assertThat(r.transitions()).first().isEqualTo("t=5.95s CLOSED -> OPEN");
        assertThat(r.failedDownstream()).isEqualTo(100);
        assertThat(r.transitions()).contains("t=66.00s OPEN -> HALF_OPEN", "t=66.10s HALF_OPEN -> OPEN");
        assertThat(r.noInstance()).as("3 trial calls per HALF_OPEN").isEqualTo(9);
    }

    @Test
    void backendB_allDown_5rps_neverOpens_TheMinimumOf100CallsNeverFitsIn10Seconds() {
        Result r = run(backendB(), 5, all(), 1, null, 200);

        assertThat(r.transitions()).isEmpty();
        assertThat(r.failedDownstream()).as("every call from 1 s to the 60 s round").isEqualTo(5 * 59);
        assertThat(r.noInstance()).as("then every call fails at the load balancer").isEqualTo(5 * 140);
        assertThat(r.rejected()).isZero();
    }

    @Test
    void backendA_allDown_5rps_stillOpens_ItsMinimumIsACountNotARate() {
        Result r = run(backendA(), 5, all(), 1, null, 200);

        assertThat(r.transitions()).first().isEqualTo("t=10.80s CLOSED -> OPEN");
        assertThat(r.failedDownstream()).isEqualTo(50);
    }

    @Test
    void withoutABreaker_allDown_20rps_everyCallUntilThe60sRoundHitsADeadInstance() {
        Result r = run(null, 20, all(), 1, null, 200);

        assertThat(r.failedDownstream()).isEqualTo(20 * 59);
        assertThat(r.noInstance()).isEqualTo(20 * 140);
    }

    // ------------------------------------------------------------------------------------------ one instance of ten

    @Test
    void oneOfTenDown_20rps_neitherBreakerOpens_10PercentFailUntilThe60sRound() {
        for (CircuitBreakerConfig config : List.of(backendA(), backendB())) {
            Result r = run(config, 20, Set.of("i03"), 1, null, 200);
            assertThat(r.transitions()).isEmpty();
            assertThat(r.failedDownstream()).isEqualTo(2 * 59);
            assertThat(r.lbSizeAt(60)).isEqualTo(9);
            stop();
            virtualTime();
        }
    }

    // -------------------------------------------------------------------------------------------------------- recovery

    @Test
    void recovery_serverBackAt90s_trafficFlowsOnlyAtTheBreakersNextTrialAfterThe120sRound() {
        Result a = run(backendA(), 20, all(), 1, 90.0, 200);
        assertThat(a.lbSizeAt(119)).as("the 60 s round emptied it, the 120 s round refills it").isZero();
        assertThat(a.lbSizeAt(120)).isEqualTo(10);
        assertThat(a.transitions()).contains("t=124.00s OPEN -> HALF_OPEN", "t=124.45s HALF_OPEN -> CLOSED");
        assertThat(a.firstOkAfter(1)).isEqualTo(124.0);

        stop();
        virtualTime();
        Result b = run(backendB(), 20, all(), 1, 90.0, 200);
        // OPEN again at 66.10 s, so the wait ends at 126.10 s; the switch needs a call strictly AFTER it (isAfter):
        // the calls at 126.00, 126.05 and 126.10 s are still rejected
        assertThat(b.transitions()).contains("t=126.15s OPEN -> HALF_OPEN", "t=126.25s HALF_OPEN -> CLOSED");
        assertThat(b.firstOkAfter(1)).isEqualTo(126.15);
    }

    /**
     * The doc's advice, measured: with "No instances available" ignored, HALF_OPEN trials that meet an empty list are not
     * counted - the permit is released, HALF_OPEN stays, and the first call after the 120 s round closes it. Without
     * it, every trial against the empty list re-opens the breaker for another 60 s (124.00 s above).
     */
    @Test
    void recovery_ignoringNoInstancesAvailable_closesRightAfterTheListComesBack() {
        CircuitBreakerConfig ignoring = onVirtualTime(CircuitBreakerConfig.custom().slidingWindowSize(100)
                .ignoreExceptions(IllegalStateException.class));
        Result r = run(ignoring, 20, all(), 1, 90.0, 200);

        assertThat(r.transitions()).containsExactly("t=3.45s CLOSED -> OPEN", "t=63.50s OPEN -> HALF_OPEN",
                "t=120.45s HALF_OPEN -> CLOSED");
        assertThat(r.firstOkAfter(1)).isEqualTo(120.0);
        // HALF_OPEN from 63.50 s lets every call through to the empty list (cheap, local): 10 in second 63, then 20/s
        assertThat(r.noInstance()).isEqualTo(10 + 56 * 20);
    }

    // -------------------------------------------------------------------------------------------------------- the run

    /**
     * One scenario second: what the calls of that second did, the breaker's state after its last call and the load
     * balancer's list size.
     */
    record Second(int t, String state, int ok, int failed, int noInstance, int rejected, int lb) {
        String shape() {
            return "%s lb=%d ok=%d failed=%d noInstance=%d rejected=%d".formatted(state, lb, ok, failed, noInstance,
                    rejected);
        }
    }

    record Result(List<Second> seconds, List<String> transitions, List<Double> okTimes) {
        int failedDownstream() {
            return seconds.stream().mapToInt(Second::failed).sum();
        }

        int noInstance() {
            return seconds.stream().mapToInt(Second::noInstance).sum();
        }

        int rejected() {
            return seconds.stream().mapToInt(Second::rejected).sum();
        }

        int lbSizeAt(int t) {
            return seconds.get(t).lb();
        }

        double firstOkAfter(double t) {
            return okTimes.stream().filter(x -> x > t).findFirst().orElse(Double.NaN);
        }

        /** Consecutive seconds that look the same, folded: {@code t=4..59 OPEN lb=10 ok=0 ... rejected=20}. */
        String timeline() {
            List<String> lines = new ArrayList<>();
            int from = 0;
            for (int i = 1; i <= seconds.size(); i++) {
                if (i == seconds.size() || !seconds.get(i).shape().equals(seconds.get(from).shape())) {
                    lines.add((from == i - 1 ? "t=" + from : "t=" + from + ".." + (i - 1)) + "  "
                            + seconds.get(from).shape());
                    from = i;
                }
            }
            return String.join("\n", lines);
        }
    }

    /**
     * @param config breaker config, {@code null} = no breaker
     * @param down   instance ids that go down at {@code downAt} (scenario seconds) and, if {@code upAt} is set, come
     *               back then
     * @param until  scenario seconds to record
     */
    private Result run(CircuitBreakerConfig config, int rps, Set<String> down, double downAt, Double upAt, int until) {
        supplier = new HealthCheckServiceInstanceListSupplier(discovery, factory(), probes);
        supplier.afterPropertiesSet();
        scheduler.advanceTime(); // round 0 at virtual 0 s

        CircuitBreaker breaker = config == null ? null : CircuitBreaker.of("scenario", config);
        List<String> transitions = new ArrayList<>();
        if (breaker != null) {
            breaker.getEventPublisher().onStateTransition(e -> transitions.add("t=%.2fs %s -> %s".formatted(
                    scenarioSeconds(), e.getStateTransition().getFromState(), e.getStateTransition().getToState())));
        }
        Map<Integer, int[]> counts = new LinkedHashMap<>();
        Map<Integer, String> states = new LinkedHashMap<>();
        Map<Integer, Integer> lbSizes = new LinkedHashMap<>();
        List<Double> okTimes = new ArrayList<>();
        boolean isDown = false;
        int position = 0;
        long periodNanos = TimeUnit.SECONDS.toNanos(1) / rps;
        long endNanos = TimeUnit.MILLISECONDS.toNanos(ZERO_AT_MS) + TimeUnit.SECONDS.toNanos(until);
        for (long at = 0; at < endNanos; at += periodNanos) {
            scheduler.advanceTimeTo(Instant.ofEpochMilli(0).plusNanos(at));
            double t = scenarioSeconds();
            if (!isDown && t >= downAt && (upAt == null || t < upAt)) {
                down.forEach(id -> probes.set(id, Outcome.ERROR, Duration.ZERO));
                isDown = true;
            } else if (isDown && upAt != null && t >= upAt) {
                down.forEach(id -> probes.set(id, Outcome.HEALTHY, Duration.ZERO));
                isDown = false;
            }

            List<ServiceInstance> list = Objects.requireNonNullElse(supplier.get().next().block(Duration.ZERO),
                    List.of());
            int second = (int) Math.floor(t);
            int[] c = second >= 0 ? counts.computeIfAbsent(second, k -> new int[4]) : new int[4];
            if (breaker != null && !breaker.tryAcquirePermission()) {
                c[3]++;
            } else if (list.isEmpty()) {
                c[2]++;
                if (breaker != null) {
                    breaker.onError(0, TimeUnit.NANOSECONDS, new IllegalStateException("No instances available"));
                }
            } else {
                ServiceInstance chosen = list.get(position++ % list.size());
                if (isDown && down.contains(chosen.getInstanceId())) {
                    c[1]++;
                    if (breaker != null) {
                        breaker.onError(0, TimeUnit.NANOSECONDS, new java.net.ConnectException("Connection refused"));
                    }
                } else {
                    c[0]++;
                    if (second >= 0) {
                        okTimes.add(t);
                    }
                    if (breaker != null) {
                        breaker.onSuccess(0, TimeUnit.NANOSECONDS);
                    }
                }
            }
            if (second >= 0) {
                states.put(second, breaker == null ? "-" : breaker.getState().name());
                lbSizes.put(second, list.size());
            }
        }
        List<Second> seconds = IntStream.range(0, until).mapToObj(s -> {
            int[] c = counts.getOrDefault(s, new int[4]);
            return new Second(s, states.get(s), c[0], c[1], c[2], c[3], lbSizes.getOrDefault(s, -1));
        }).toList();
        Result result = new Result(seconds, transitions, okTimes);
        if (System.getProperty("scenario.print") != null) {
            System.out.println("=== " + (config == null ? "no breaker" : config.getSlidingWindowType() + "/"
                    + config.getSlidingWindowSize()) + " rps=" + rps + " down=" + down.stream().sorted()
                    .collect(Collectors.joining(",")) + "@" + downAt + (upAt == null ? "" : " up@" + upAt)
                    + "\n" + String.join("\n", transitions) + "\n" + result.timeline()
                    + "\nfailedDownstream=" + result.failedDownstream() + " noInstance=" + result.noInstance()
                    + " rejected=" + result.rejected());
        }
        return result;
    }

    private double scenarioSeconds() {
        return (scheduler.now(TimeUnit.NANOSECONDS) - TimeUnit.MILLISECONDS.toNanos(ZERO_AT_MS)) / 1e9;
    }

    private static Set<String> all() {
        return IntStream.range(0, INSTANCES).mapToObj(FakeDiscovery::id).collect(Collectors.toSet());
    }

    @SuppressWarnings("unchecked")
    private static ReactiveLoadBalancer.Factory<ServiceInstance> factory() {
        LoadBalancerProperties properties = new LoadBalancerProperties();
        LoadBalancerProperties.HealthCheck healthCheck = properties.getHealthCheck();
        healthCheck.setUpdateResultsList(false);
        healthCheck.setInitialDelay(Duration.ZERO);
        healthCheck.setInterval(INTERVAL);
        healthCheck.setRefetchInstances(true);
        healthCheck.setRefetchInstancesInterval(REFETCH);
        ReactiveLoadBalancer.Factory<ServiceInstance> factory = mock(ReactiveLoadBalancer.Factory.class);
        when(factory.getProperties(FakeDiscovery.SERVICE_ID)).thenReturn(properties);
        return factory;
    }
}
