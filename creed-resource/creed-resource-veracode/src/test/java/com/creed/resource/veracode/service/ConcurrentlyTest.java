package com.creed.resource.veracode.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.concurrent.Callable;
import java.util.stream.IntStream;

import io.micrometer.context.ContextRegistry;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/**
 * The calls run in the caller's context: in the application that is the current Observation - the Brave span, hence the
 * traceId / spanId on every Logbook line of a parallel Jira search. A plain ThreadLocal registered the same way stands
 * in for it, so the test needs no tracer.
 */
class ConcurrentlyTest {

    private static final String KEY = "concurrently-test";
    private static final ThreadLocal<String> CURRENT = new ThreadLocal<>();

    @BeforeAll
    static void register() {
        ContextRegistry.getInstance().registerThreadLocalAccessor(KEY, CURRENT);
    }

    @AfterAll
    static void unregister() {
        ContextRegistry.getInstance().removeThreadLocalAccessor(KEY);
    }

    @Test
    void everyCallSeesTheCallersContext() {
        CURRENT.set("trace-1");
        try {
            List<Callable<String>> calls = IntStream.range(0, 6).<Callable<String>>mapToObj(i -> CURRENT::get).toList();

            assertThat(Concurrently.all("test", 3, calls)).containsOnly("trace-1").hasSize(6);
            assertThat(Concurrently.join(Concurrently.start("test-one", CURRENT::get))).isEqualTo("trace-1");
        } finally {
            CURRENT.remove();
        }
    }

    @Test
    void theThreadsKeepNothingAfterwards() {
        CURRENT.set("trace-2");
        try {
            Concurrently.all("test", 2, List.<Callable<String>>of(CURRENT::get, CURRENT::get));
        } finally {
            CURRENT.remove();
        }
        // a call made without a context gets none - not the previous request's
        assertThat(Concurrently.all("test", 2, List.<Callable<String>>of(CURRENT::get, CURRENT::get)))
                .containsExactly(null, null);
    }
}
