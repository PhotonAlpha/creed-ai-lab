package com.creed.resource.lbhealth.lb;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.creed.resource.lbhealth.lb.ScriptedProbes.Mode;
import com.creed.resource.lbhealth.lb.ScriptedProbes.Outcome;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.cloud.client.ServiceInstance;
import org.springframework.cloud.client.loadbalancer.LoadBalancerProperties;
import org.springframework.cloud.client.loadbalancer.reactive.ReactiveLoadBalancer;
import org.springframework.cloud.loadbalancer.core.HealthCheckServiceInstanceListSupplier;
import org.slf4j.LoggerFactory;
import reactor.test.StepVerifier;
import reactor.test.scheduler.VirtualTimeScheduler;

import java.time.Duration;
import java.util.List;
import java.util.stream.IntStream;

import static java.time.Duration.ofMillis;
import static java.time.Duration.ofMinutes;
import static java.time.Duration.ofSeconds;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Spring Cloud LoadBalancer 5.0.3's own {@link HealthCheckServiceInstanceListSupplier} (and the
 * {@link ObservableHealthCheckServiceInstanceListSupplier} subclass, which must behave identically) over 10
 * instances, on virtual time. Each test pins one rule from the README:
 * <ol>
 *   <li>{@code update-results-list=false}: one emission per round, always complete (and the {@code true} default
 *   for contrast);</li>
 *   <li>{@code interval < refetch-instances-interval}: rounds follow {@code interval};</li>
 *   <li>{@code refetch < interval}: every refetch cancels the interval timer, rounds follow the refetch instead;</li>
 *   <li>a round longer than the refetch interval is cancelled every time and {@code get()} stays on a stale list;</li>
 *   <li>unhealthy instances: {@code get()} is the healthy subset, emitted once per round.</li>
 * </ol>
 * StepVerifier fails on any onNext the script does not expect, so every {@code assertNext} chain below also proves
 * that no other (partial) list was emitted in between.
 */
class HealthCheckRoundTest {

    private static final int INSTANCES = 10;
    private static final Duration INTERVAL = ofSeconds(30);
    private static final Duration REFETCH = ofSeconds(120);

    private VirtualTimeScheduler scheduler;
    private FakeDiscovery discovery;
    private HealthCheckServiceInstanceListSupplier supplier;

    @BeforeEach
    void virtualTime() {
        // Before the supplier is built: its constructor captures Schedulers.parallel() for delaySubscription.
        scheduler = VirtualTimeScheduler.getOrSet();
        discovery = new FakeDiscovery(scheduler, INSTANCES);
    }

    @AfterEach
    void stop() {
        if (supplier != null) {
            supplier.destroy();
        }
        VirtualTimeScheduler.reset();
    }

    // ---------------------------------------------------------------------------------------- 1. complete rounds

    @Test
    void updateResultsListFalseEmitsOnlyCompleteRounds() {
        ScriptedProbes probes = staggered(Mode.ASYNC); // i00 answers after 0.5 s ... i09 after 5 s
        supplier = start(properties(false, INTERVAL, REFETCH), probes);

        StepVerifier.withVirtualTime(supplier::get)
                .expectSubscription()
                .expectNoEvent(ofMillis(4_999))         // round 1 in flight: 9 instances answered, nothing emitted
                .thenAwait(ofMillis(1))
                .assertNext(HealthCheckRoundTest::assertAll10) // t=5s, all at once
                .expectNoEvent(ofMillis(34_999))        // interval 30s + round 5s; get() keeps returning round 1
                .then(() -> assertThat(current()).hasSize(INSTANCES))
                .thenAwait(ofMillis(1))
                .assertNext(HealthCheckRoundTest::assertAll10) // t=40s
                .thenCancel()
                .verify();
    }

    @Test
    void updateResultsListTrueEmitsAGrowingPartialListEveryRound() {
        supplier = start(properties(true, INTERVAL, REFETCH), staggered(Mode.ASYNC));

        StepVerifier.Step<List<ServiceInstance>> step = StepVerifier.withVirtualTime(supplier::get).expectSubscription();
        for (int size = 1; size <= INSTANCES; size++) { // round 1: 1, 2, ... 10, one every 0.5 s
            int expected = size;
            step = step.thenAwait(ofMillis(500)).assertNext(list -> assertThat(list).hasSize(expected));
        }
        step.thenAwait(INTERVAL.plusMillis(500))         // round 2 starts from an empty list again
                .assertNext(list -> assertThat(list).as("the list shrinks back to one instance").hasSize(1))
                .then(() -> assertThat(current()).as("what RoundRobinLoadBalancer would get now").hasSize(1))
                .thenCancel()
                .verify();
    }

    // ------------------------------------------------------------------------------ 2. interval < refetch interval

    @Test
    void withIntervalShorterThanRefetchRoundsFollowTheInterval() {
        ScriptedProbes probes = staggered(Mode.ASYNC);
        supplier = start(properties(false, INTERVAL, REFETCH), probes);

        StepVerifier.withVirtualTime(supplier::get)
                .expectSubscription()
                .thenAwait(ofSeconds(130))
                .expectNextCount(5) // complete rounds at 5, 40, 75, 110, 125 s
                .thenCancel()
                .verify();

        // round + interval = 35 s apart; the refetch at 120 s replaces the timer that would have fired at 140 s
        assertThat(probes.startsOf("i00")).containsExactly(ofSeconds(0), ofSeconds(35), ofSeconds(70), ofSeconds(105),
                ofSeconds(120));
        assertThat(discovery.fetches()).containsExactly(ofSeconds(0), ofSeconds(120));
    }

    // ------------------------------------------------------------------------------ 3. refetch interval < interval

    @Test
    void withRefetchShorterThanIntervalTheIntervalTimerNeverFires() {
        ScriptedProbes probes = staggered(Mode.ASYNC);
        ObservableHealthCheckServiceInstanceListSupplier observable =
                startObservable(properties(false, INTERVAL, ofSeconds(20)), probes);

        StepVerifier.withVirtualTime(observable::get)
                .expectSubscription()
                .thenAwait(ofSeconds(110))
                .expectNextCount(6) // 5, 25, 45, 65, 85, 105 s
                .thenCancel()
                .verify();

        // A 30 s interval after the 5 s round would start the next round at 35 s. It never does: the refetch at 20 s
        // swaps the inner stream (switchMap) and cancels that timer - every time.
        assertThat(probes.startsOf("i00")).containsExactly(ofSeconds(0), ofSeconds(20), ofSeconds(40), ofSeconds(60),
                ofSeconds(80), ofSeconds(100));
        assertThat(discovery.fetches()).as("each check also re-reads discovery").hasSize(6);
        assertThat(observable.midRoundCancellations()).as("cancelled between rounds, results still land").isZero();
    }

    @Test
    void eachRoundSaysWhetherTheRefetchOrTheIntervalStartedIt() {
        Logger logger = (Logger) LoggerFactory.getLogger(ObservableHealthCheckServiceInstanceListSupplier.class);
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);
        try {
            ObservableHealthCheckServiceInstanceListSupplier observable =
                    startObservable(properties(false, INTERVAL, REFETCH), staggered(Mode.ASYNC));

            StepVerifier.withVirtualTime(observable::get)
                    .expectSubscription()
                    .thenAwait(ofSeconds(126))
                    .expectNextCount(5) // rounds end at 5, 40, 75, 110, 125 s
                    .thenCancel()
                    .verify();

            // rounds start at 0 (the first list), 35, 70, 105 (interval 30 s after each 5 s round) and 120 s: the
            // refetch, which also drops the timer that would have started a round at 140 s
            assertThat(messages(appender, "[HC-ROUND]")).map(m -> m.replaceAll(".* start ", "")).containsExactly(
                    "round=1 trigger=REFETCH", "round=2 trigger=INTERVAL", "round=3 trigger=INTERVAL",
                    "round=4 trigger=INTERVAL", "round=5 trigger=REFETCH");
            assertThat(messages(appender, "[HC-RESULT]")).map(m -> m.replaceAll(".*(round=\\d+ trigger=\\w+).*", "$1"))
                    .containsExactly("round=1 trigger=REFETCH", "round=2 trigger=INTERVAL", "round=3 trigger=INTERVAL",
                            "round=4 trigger=INTERVAL", "round=5 trigger=REFETCH");
        } finally {
            logger.detachAppender(appender);
        }
    }

    private static List<String> messages(ListAppender<ILoggingEvent> appender, String prefix) {
        return List.copyOf(appender.list).stream().map(ILoggingEvent::getFormattedMessage)
                .filter(m -> m.startsWith(prefix)).toList();
    }

    // ---------------------------------------------------------------------------------- 4. round > refetch interval

    @Test
    void aRoundLongerThanTheRefetchIntervalNeverCompletesAndGetStaysStale() {
        ScriptedProbes probes = new ScriptedProbes(scheduler, Mode.SERIAL_BLOCKING).all(Outcome.HEALTHY, ofSeconds(1));
        ObservableHealthCheckServiceInstanceListSupplier observable =
                startObservable(properties(false, INTERVAL, REFETCH), probes);

        StepVerifier.withVirtualTime(observable::get)
                .expectSubscription()
                .thenAwait(ofSeconds(10))                 // 10 serial probes × 1 s
                .assertNext(HealthCheckRoundTest::assertAll10)
                // Now i03 goes down and every instance answers only at its 15 s timeout: 10 × 15 s = 150 s > 120 s.
                .then(() -> probes.all(Outcome.HEALTHY, ofSeconds(15)).set("i03", Outcome.UNHEALTHY, ofSeconds(15)))
                .expectNoEvent(ofMinutes(10))             // every round is cancelled by the next refetch
                .then(() -> assertThat(ids(current())).as("stale: i03 is down but still handed out").contains("i03"))
                .thenCancel()
                .verify();

        assertThat(observable.completedRounds()).isOne();
        assertThat(observable.midRoundCancellations()).as("refetches at 120, 240 ... 600 s").isEqualTo(5);
    }

    @Test
    void theSameSlowRoundWithinTheRefetchIntervalStillUpdates() {
        ScriptedProbes probes = new ScriptedProbes(scheduler, Mode.SERIAL_BLOCKING).all(Outcome.HEALTHY, ofSeconds(1));
        supplier = start(properties(false, INTERVAL, REFETCH), probes);

        StepVerifier.withVirtualTime(supplier::get)
                .expectSubscription()
                .thenAwait(ofSeconds(10))
                .assertNext(HealthCheckRoundTest::assertAll10)
                // 10 × 5 s = 50 s < 120 s: the round started at 40 s ends at 90 s
                .then(() -> probes.all(Outcome.HEALTHY, ofSeconds(5)).set("i03", Outcome.UNHEALTHY, ofSeconds(5)))
                .expectNoEvent(ofMillis(79_999))
                .thenAwait(ofMillis(1))
                .assertNext(list -> assertThat(ids(list)).hasSize(9).doesNotContain("i03"))
                .thenCancel()
                .verify();
    }

    @Test
    void concurrentProbesKeepTheRoundShortEvenWhenEachIsSlow() {
        // webclient mode: the same 15 s probes run in parallel, so the round lasts 15 s, not 150 s
        ScriptedProbes probes = new ScriptedProbes(scheduler, Mode.ASYNC).all(Outcome.HEALTHY, ofSeconds(1));
        supplier = start(properties(false, INTERVAL, REFETCH), probes);

        StepVerifier.withVirtualTime(supplier::get)
                .expectSubscription()
                .thenAwait(ofSeconds(1))
                .assertNext(HealthCheckRoundTest::assertAll10)
                .then(() -> probes.all(Outcome.HEALTHY, ofSeconds(15)).set("i03", Outcome.UNHEALTHY, ofSeconds(15)))
                .expectNoEvent(ofMillis(44_999))         // round 2 starts at 31 s, ends at 46 s
                .thenAwait(ofMillis(1))
                .assertNext(list -> assertThat(ids(list)).hasSize(9).doesNotContain("i03"))
                .thenCancel()
                .verify();
    }

    // ------------------------------------------------------------------------------------ 5. unhealthy instances

    @ParameterizedTest
    @EnumSource(Mode.class)
    void unhealthyInstancesLeaveTheHealthySubsetEmittedOnce(Mode mode) {
        ScriptedProbes probes = new ScriptedProbes(scheduler, mode).all(Outcome.HEALTHY, ofSeconds(1))
                .set("i02", Outcome.UNHEALTHY, ofSeconds(1))
                .set("i05", Outcome.ERROR, ofSeconds(1));
        // A hung instance: async probes are cut by Spring's timeout(interval) = 30 s; a blocking probe can only be
        // cut by the HTTP client's own timeout, modelled as "answers DOWN after 5 s".
        Duration roundEnd;
        if (mode == Mode.ASYNC) {
            probes.set("i07", Outcome.HANG, Duration.ZERO);
            roundEnd = INTERVAL;
        } else {
            probes.set("i07", Outcome.UNHEALTHY, ofSeconds(5));
            roundEnd = ofSeconds(9 + 5);
        }
        supplier = start(properties(false, INTERVAL, REFETCH), probes);
        List<String> healthy = List.of("i00", "i01", "i03", "i04", "i06", "i08", "i09");

        StepVerifier.withVirtualTime(supplier::get)
                .expectSubscription()
                .expectNoEvent(roundEnd.minusMillis(1))   // the hung/slow one holds back the whole round ...
                .thenAwait(ofMillis(1))
                .assertNext(list -> assertThat(ids(list)).containsExactlyInAnyOrderElementsOf(healthy)) // ... then 7
                .then(() -> assertThat(ids(current())).containsExactlyInAnyOrderElementsOf(healthy))
                .expectNoEvent(INTERVAL.plus(roundEnd).minusMillis(1))
                .thenAwait(ofMillis(1))
                .assertNext(list -> assertThat(ids(list)).containsExactlyInAnyOrderElementsOf(healthy))
                .thenCancel()
                .verify();
    }

    // ------------------------------------------------------------------------------------------------- helpers

    /** i00 answers after 0.5 s, i01 after 1 s ... i09 after 5 s - every instance healthy. */
    private ScriptedProbes staggered(Mode mode) {
        ScriptedProbes probes = new ScriptedProbes(scheduler, mode);
        IntStream.range(0, INSTANCES).forEach(i ->
                probes.set(FakeDiscovery.id(i), Outcome.HEALTHY, ofMillis(500L * (i + 1))));
        return probes;
    }

    private static LoadBalancerProperties properties(boolean updateResultsList, Duration interval, Duration refetch) {
        LoadBalancerProperties properties = new LoadBalancerProperties();
        LoadBalancerProperties.HealthCheck healthCheck = properties.getHealthCheck();
        healthCheck.setUpdateResultsList(updateResultsList);
        healthCheck.setInitialDelay(Duration.ZERO);
        healthCheck.setInterval(interval);
        healthCheck.setRefetchInstances(true);
        healthCheck.setRefetchInstancesInterval(refetch);
        return properties;
    }

    @SuppressWarnings("unchecked")
    private static ReactiveLoadBalancer.Factory<ServiceInstance> factory(LoadBalancerProperties properties) {
        ReactiveLoadBalancer.Factory<ServiceInstance> factory = mock(ReactiveLoadBalancer.Factory.class);
        when(factory.getProperties(FakeDiscovery.SERVICE_ID)).thenReturn(properties);
        return factory;
    }

    /** As in the application: the bean's afterPropertiesSet() opens the permanent subscription. */
    private HealthCheckServiceInstanceListSupplier start(LoadBalancerProperties properties, ScriptedProbes probes) {
        HealthCheckServiceInstanceListSupplier started =
                new HealthCheckServiceInstanceListSupplier(discovery, factory(properties), probes);
        started.afterPropertiesSet();
        return started;
    }

    private ObservableHealthCheckServiceInstanceListSupplier startObservable(LoadBalancerProperties properties,
            ScriptedProbes probes) {
        ObservableHealthCheckServiceInstanceListSupplier started =
                new ObservableHealthCheckServiceInstanceListSupplier(discovery, factory(properties), probes);
        started.afterPropertiesSet();
        supplier = started;
        return started;
    }

    /** What RoundRobinLoadBalancer reads: {@code get().next()} - served from replay(1), so it returns at once. */
    private List<ServiceInstance> current() {
        return supplier.get().next().block(Duration.ZERO);
    }

    private static List<String> ids(List<ServiceInstance> instances) {
        return instances.stream().map(ServiceInstance::getInstanceId).toList();
    }

    private static void assertAll10(List<ServiceInstance> instances) {
        assertThat(instances).hasSize(INSTANCES);
    }
}
