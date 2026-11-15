package com.creed.gatewayproxy.service.splunk;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.Test;

import com.creed.gatewayproxy.config.SplunkProperties;

class ClientHostResolverTest {

    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-11-15T04:00:00Z"), ZoneOffset.UTC);

    private static SplunkProperties.ClientHost config(Boolean enabled, Duration timeout) {
        return new SplunkProperties.ClientHost(enabled, timeout, Duration.ofMinutes(10));
    }

    @Test
    void aNameIsLookedUpOnceAndCached() {
        AtomicInteger calls = new AtomicInteger();
        try (ClientHostResolver resolver = new ClientHostResolver(config(null, Duration.ofSeconds(1)),
                ip -> calls.incrementAndGet() > 0 ? "pc-" + ip.replace('.', '-') + ".corp" : null, CLOCK)) {
            assertThat(resolver.resolve("10.0.0.7")).isEqualTo("pc-10-0-0-7.corp");
            assertThat(resolver.resolve("10.0.0.7")).isEqualTo("pc-10-0-0-7.corp");
            assertThat(calls).hasValue(1);
        }
    }

    @Test
    void aSlowLookupIsNotWaitedForButFillsTheCache() throws InterruptedException {
        CountDownLatch release = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(1);
        try (ClientHostResolver resolver = new ClientHostResolver(config(true, Duration.ofMillis(50)), ip -> {
            try {
                release.await();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            done.countDown();
            return "late.corp";
        }, CLOCK)) {
            long started = System.nanoTime();
            assertThat(resolver.resolve("10.0.0.8")).isNull();
            assertThat(Duration.ofNanos(System.nanoTime() - started)).isLessThan(Duration.ofSeconds(1));
            release.countDown();
            assertThat(done.await(5, TimeUnit.SECONDS)).isTrue();
            assertThat(resolver.resolve("10.0.0.8")).isEqualTo("late.corp");
        }
    }

    @Test
    void disabledOrNoIpLooksNothingUp() {
        AtomicReference<String> asked = new AtomicReference<>();
        try (ClientHostResolver off = new ClientHostResolver(config(false, Duration.ofSeconds(1)), ip -> {
            asked.set(ip);
            return "x";
        }, CLOCK)) {
            assertThat(off.resolve("10.0.0.7")).isNull();
            assertThat(asked).hasNullValue();
        }
        try (ClientHostResolver on = new ClientHostResolver(config(true, Duration.ofSeconds(1)), ip -> "x", CLOCK)) {
            assertThat(on.resolve(null)).isNull();
            assertThat(on.resolve(" ")).isNull();
        }
    }

    @Test
    void aFailingLookupIsNoName() {
        try (ClientHostResolver resolver = new ClientHostResolver(config(true, Duration.ofSeconds(1)), ip -> {
            throw new IllegalStateException("resolver down");
        }, CLOCK)) {
            assertThat(resolver.resolve("10.0.0.9")).isNull();
        }
    }
}
