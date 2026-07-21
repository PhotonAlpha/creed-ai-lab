package com.creed.simple.lb;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for {@link StickyContextHolder}: the always-overwrite ThreadLocal contract, the
 * {@code null} sentinel for "no stickiness", {@code clear()} semantics and per-thread isolation.
 */
class StickyContextHolderTest {

    @AfterEach
    void clearHolder() {
        StickyContextHolder.clear();
    }

    @Test
    void getIsNullByDefault() {
        assertThat(StickyContextHolder.get()).isNull();
    }

    @Test
    void setThenGetReturnsTheStoredValue() {
        StickyContextHolder.set("STICKY-1");
        assertThat(StickyContextHolder.get()).isEqualTo("STICKY-1");
    }

    @Test
    void setOverwritesThePreviousValue() {
        StickyContextHolder.set("STICKY-1");
        StickyContextHolder.set("STICKY-2");
        assertThat(StickyContextHolder.get()).isEqualTo("STICKY-2");
    }

    @Test
    void setNullClearsTheStickinessRequest() {
        StickyContextHolder.set("STICKY-1");
        StickyContextHolder.set(null);
        assertThat(StickyContextHolder.get()).isNull();
    }

    @Test
    void clearRemovesTheValue() {
        StickyContextHolder.set("STICKY-1");
        StickyContextHolder.clear();
        assertThat(StickyContextHolder.get()).isNull();
    }

    @Test
    void valueIsIsolatedPerThread() throws Exception {
        StickyContextHolder.set("MAIN-THREAD");
        ExecutorService executor = Executors.newSingleThreadExecutor();
        try {
            Future<String> other = executor.submit(StickyContextHolder::get);
            // A fresh pool thread never inherits the caller's value.
            assertThat(other.get()).isNull();
        } finally {
            executor.shutdownNow();
        }
        // The caller's own value is unaffected by the other thread.
        assertThat(StickyContextHolder.get()).isEqualTo("MAIN-THREAD");
    }
}
