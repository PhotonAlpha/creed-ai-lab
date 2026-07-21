package com.creed.simple.web;

import io.micrometer.core.instrument.Meter;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.core.task.TaskDecorator;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for {@link CamelThreadPoolConfiguration}: both pools are usable {@link ExecutorService}s and
 * are instrumented with {@code executor.*} meters tagged {@code name=<bean id>}, so the actuator picks
 * them up (which the former Camel-internal {@code <threadPool>}s never were).
 */
class CamelThreadPoolConfigurationTest {

    private final CamelThreadPoolConfiguration config = new CamelThreadPoolConfiguration();
    private final TaskDecorator identityDecorator = runnable -> runnable;

    private boolean hasExecutorMeterNamed(SimpleMeterRegistry registry, String poolName) {
        return registry.getMeters().stream()
                .anyMatch(meter -> meter.getId().getName().startsWith("executor")
                        && poolName.equals(meter.getId().getTag("name")));
    }

    @Test
    void aggregatePoolAIsUsableAndInstrumented() throws Exception {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        ExecutorService pool = config.aggregatePoolA(registry, identityDecorator);
        try {
            Future<String> result = pool.submit(() -> "ran");
            assertThat(result.get()).isEqualTo("ran");
            assertThat(hasExecutorMeterNamed(registry, "aggregatePoolA")).isTrue();
        } finally {
            pool.shutdown();
        }
    }

    @Test
    void notificationPoolBIsUsableAndInstrumented() throws Exception {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        ExecutorService pool = config.notificationPoolB(registry, identityDecorator);
        try {
            Future<String> result = pool.submit(() -> "ran");
            assertThat(result.get()).isEqualTo("ran");
            assertThat(hasExecutorMeterNamed(registry, "notificationPoolB")).isTrue();
        } finally {
            pool.shutdown();
        }
    }

    @Test
    void poolsRegisterUnderDistinctNames() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        ExecutorService a = config.aggregatePoolA(registry, identityDecorator);
        ExecutorService b = config.notificationPoolB(registry, identityDecorator);
        try {
            assertThat(registry.getMeters().stream()
                    .map(Meter::getId)
                    .filter(id -> id.getName().startsWith("executor"))
                    .map(id -> id.getTag("name")))
                    .contains("aggregatePoolA", "notificationPoolB");
        } finally {
            a.shutdown();
            b.shutdown();
        }
    }
}
