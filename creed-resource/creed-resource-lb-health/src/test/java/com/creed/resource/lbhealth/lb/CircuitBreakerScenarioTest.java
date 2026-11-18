package com.creed.resource.lbhealth.lb;

import com.creed.resource.lbhealth.downstream.DownstreamCircuitBreakerFailurePredicate;
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

    /**
     * {@code resilience4j.circuitbreaker.configs.default} of application.yml, key by key - except
     * {@code automatic-transition-from-open-to-half-open-enabled}: the automatic switch is a task on a REAL-time
     * scheduler (15 s of wall clock), which virtual time cannot drive. Under continuous traffic the lazy switch happens
     * on the first call after the wait instead - at most one call period (50 ms at 20/s) later; docs §4.2.
     * {@code LbHealthApplicationTest} pins that the bound config carries these values.
     */
    private CircuitBreakerConfig configured() {
        return onVirtualTime(configuredBuilder());
    }

    static CircuitBreakerConfig.Builder configuredBuilder() {
        return CircuitBreakerConfig.custom()
                .slidingWindowType(CircuitBreakerConfig.SlidingWindowType.COUNT_BASED)
                .slidingWindowSize(200)
                .minimumNumberOfCalls(50)
                .failureRateThreshold(60)
                .waitDurationInOpenState(Duration.ofSeconds(15))
                .permittedNumberOfCallsInHalfOpenState(10)
                .recordException(new DownstreamCircuitBreakerFailurePredicate())
                .slowCallRateThreshold(100)
                .slowCallDurationThreshold(Duration.ofSeconds(60));
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
    void theMinimumIsAFloorNotAWindowAndOnlyTheFourArgBuilderCapsIt() {
        assertThat(configured().getMinimumNumberOfCalls()).isEqualTo(50);
        assertThat(configured().getSlidingWindowSize()).isEqualTo(200);
        // separate setters (what the Spring binding calls) never cap: COUNT_BASED 10 + default minimum 100 never opens
        assertThat(CircuitBreakerConfig.custom().slidingWindowSize(10).build().getMinimumNumberOfCalls()).isEqualTo(100);
        // only slidingWindow(size, minimum, type, strategy) caps a COUNT_BASED minimum at the window size
        assertThat(CircuitBreakerConfig.custom().slidingWindow(10, 100,
                CircuitBreakerConfig.SlidingWindowType.COUNT_BASED,
                CircuitBreakerConfig.DEFAULT_SLIDING_WINDOW_SYNCHRONIZATION_STRATEGY).build().getMinimumNumberOfCalls())
                .isEqualTo(10);
    }

    // ----------------------------------------------------------------------------------- the whole cluster goes down

    /**
     * Window: the last 200 calls, all successes. 60 % of 200 = 120 failures - at 20/s that is 6 s. Then OPEN 15 s,
     * and every HALF_OPEN's 10 trials go to the STALE list (the 0 s round's 10 instances, dead) until the 60 s round:
     * a 15 s wait shorter than the 60 s health-check interval makes each trial cost 10 real downstream failures.
     */
    @Test
    void allDown_20rps_opensAfter120FailuresAndEveryTrialBeforeThe60sRoundHitsTheDeadInstances() {
        Result r = run(configured(), 20, all(), 1, null, 200);

        assertThat(r.transitions()).startsWith(
                "t=6.95s CLOSED -> OPEN",
                "t=22.00s OPEN -> HALF_OPEN", "t=22.45s HALF_OPEN -> OPEN",
                "t=37.50s OPEN -> HALF_OPEN", "t=37.95s HALF_OPEN -> OPEN",
                "t=53.00s OPEN -> HALF_OPEN", "t=53.45s HALF_OPEN -> OPEN",
                "t=68.50s OPEN -> HALF_OPEN", "t=68.95s HALF_OPEN -> OPEN");
        assertThat(r.failedDownstream()).as("120 to open + 3 trials x 10 against the stale list").isEqualTo(150);
        assertThat(r.noInstance()).as("from 68.5 s the trials meet the empty list: 9 x 10").isEqualTo(90);
        assertThat(r.rejected()).isEqualTo(3740);
        assertThat(r.lbSizeAt(59)).isEqualTo(10);
        assertThat(r.lbSizeAt(60)).isZero();
    }

    /** A count, not a rate: still 120 failures, so 24 s at 5/s - and a HALF_OPEN's 10 trials take 2 s. */
    @Test
    void allDown_5rps_needsTheSame120FailuresSoItTakes24s() {
        Result r = run(configured(), 5, all(), 1, null, 200);

        assertThat(r.transitions()).startsWith("t=24.80s CLOSED -> OPEN", "t=40.00s OPEN -> HALF_OPEN",
                "t=41.80s HALF_OPEN -> OPEN");
        assertThat(r.failedDownstream()).isEqualTo(120 + 2 * 10);
    }

    @Test
    void withoutABreaker_allDown_20rps_everyCallUntilThe60sRoundHitsADeadInstance() {
        Result r = run(null, 20, all(), 1, null, 200);

        assertThat(r.failedDownstream()).isEqualTo(20 * 59);
        assertThat(r.noInstance()).isEqualTo(20 * 140);
    }

    // ------------------------------------------------------------------------------------------ one instance of ten

    /** Round robin: 1 call in 10 lands on i03 - 10 %, 20 failures in a full window of 200. */
    @Test
    void oneOfTenDown_20rps_neverOpens_10PercentIsFarBelow60() {
        Result r = run(configured(), 20, Set.of("i03"), 1, null, 200);
        assertThat(r.transitions()).isEmpty();
        assertThat(r.seconds().get(30).shape()).isEqualTo("CLOSED lb=10 ok=18 failed=2 noInstance=0 rejected=0");
        assertThat(r.failedDownstream()).isEqualTo(2 * 59);
        assertThat(r.lbSizeAt(60)).isEqualTo(9);
        assertThat(r.seconds().get(60).shape()).isEqualTo("CLOSED lb=9 ok=20 failed=0 noInstance=0 rejected=0");
    }

    /** 2 in 10: 20 %, 40 failures in the window - still a third of the 60 % threshold. Twice the failures of one. */
    @Test
    void twoOfTenDown_20rps_neverOpens_20PercentAndTwiceTheFailures() {
        Result r = run(configured(), 20, Set.of("i03", "i07"), 1, null, 200);
        assertThat(r.transitions()).isEmpty();
        assertThat(r.seconds().get(30).shape()).isEqualTo("CLOSED lb=10 ok=16 failed=4 noInstance=0 rejected=0");
        assertThat(r.failedDownstream()).isEqualTo(4 * 59);
        assertThat(r.lbSizeAt(60)).isEqualTo(8);
    }

    /** Where it starts to open: 5 of 10 = 50 % never; 6 of 10 = 60 % opens - and then also blocks the 4 healthy ones. */
    @Test
    void theThreshold_fiveOfTenNeverOpens_sixOfTenOpensAndBlocksTheHealthyFourToo() {
        Result five = run(configured(), 20, Set.of("i01", "i03", "i05", "i07", "i09"), 1, null, 200);
        assertThat(five.transitions()).isEmpty();
        assertThat(five.failedDownstream()).isEqualTo(10 * 59);

        stop();
        virtualTime();
        Result six = run(configured(), 20, Set.of("i00", "i01", "i03", "i05", "i07", "i09"), 1, null, 200);
        assertThat(six.transitions()).startsWith("t=10.95s CLOSED -> OPEN")
                .endsWith("t=72.50s OPEN -> HALF_OPEN", "t=72.95s HALF_OPEN -> CLOSED");
        assertThat(six.failedDownstream()).isEqualTo(138);
        // 60..71 s: the list holds the 4 healthy instances, but the breaker is still OPEN
        assertThat(six.seconds().get(65).shape()).isEqualTo("OPEN lb=4 ok=0 failed=0 noInstance=0 rejected=20");
    }

    // -------------------------------------------------------------------------------------------------------- recovery

    /**
     * Back at 90 s, in the list again at the 120 s round; the breaker re-opened at 115.45 s (a trial against the empty
     * list), so traffic flows at its next trial, 130.50 s - 10.5 s after the load balancer.
     */
    @Test
    void recovery_serverBackAt90s_trafficFlowsAtTheNextTrialAfterThe120sRound() {
        Result r = run(configured(), 20, all(), 1, 90.0, 200);
        assertThat(r.lbSizeAt(119)).isZero();
        assertThat(r.lbSizeAt(120)).isEqualTo(10);
        assertThat(r.transitions()).endsWith("t=115.45s HALF_OPEN -> OPEN", "t=130.50s OPEN -> HALF_OPEN",
                "t=130.95s HALF_OPEN -> CLOSED");
        assertThat(r.firstOkAfter(1)).isEqualTo(130.5);
    }

    /**
     * With "No instances available" IGNORED (not answered false by the predicate - false would be a success and close
     * the breaker on an empty list), a trial that meets the empty list releases its permit: HALF_OPEN stays and lets
     * every call through to the load balancer, which fails it locally, until the 120 s round. First success at 120 s,
     * like no breaker at all - at the price of 1030 local failures instead of rejections.
     */
    @Test
    void recovery_ignoringNoInstancesAvailable_closesRightAfterTheListComesBack() {
        Result r = run(onVirtualTime(configuredBuilder().ignoreException(e -> e instanceof IllegalStateException
                && String.valueOf(e.getMessage()).startsWith("No instances available"))), 20, all(), 1, 90.0, 200);

        assertThat(r.transitions()).endsWith("t=68.50s OPEN -> HALF_OPEN", "t=120.45s HALF_OPEN -> CLOSED");
        assertThat(r.firstOkAfter(1)).isEqualTo(120.0);
        assertThat(r.noInstance()).isEqualTo(10 + 51 * 20);
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
