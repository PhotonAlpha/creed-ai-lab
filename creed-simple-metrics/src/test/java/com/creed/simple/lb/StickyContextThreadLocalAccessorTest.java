package com.creed.simple.lb;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for {@link StickyContextThreadLocalAccessor}: its bridging of {@link StickyContextHolder}
 * into Micrometer context-propagation, including the "clear on null restore" contract that prevents a
 * pooled thread from leaking a previous request's sticky id.
 */
class StickyContextThreadLocalAccessorTest {

    private final StickyContextThreadLocalAccessor accessor = new StickyContextThreadLocalAccessor();

    @AfterEach
    void clearHolder() {
        StickyContextHolder.clear();
    }

    @Test
    void keyIsTheDocumentedConstant() {
        assertThat(accessor.key()).isEqualTo(StickyContextThreadLocalAccessor.KEY);
        assertThat(StickyContextThreadLocalAccessor.KEY).isEqualTo("creed.stickyId");
    }

    @Test
    void getValueReadsFromTheHolder() {
        StickyContextHolder.set("STICKY-9");
        assertThat(accessor.getValue()).isEqualTo("STICKY-9");
    }

    @Test
    void setValueWritesToTheHolder() {
        accessor.setValue("STICKY-42");
        assertThat(StickyContextHolder.get()).isEqualTo("STICKY-42");
    }

    @Test
    void setValueNoArgClearsTheHolder() {
        StickyContextHolder.set("STICKY-42");
        accessor.setValue();
        assertThat(StickyContextHolder.get()).isNull();
    }

    @Test
    void resetClearsTheHolder() {
        StickyContextHolder.set("STICKY-42");
        accessor.reset();
        assertThat(StickyContextHolder.get()).isNull();
    }

    @Test
    void nullRestoreExplicitlyClearsRatherThanKeepingStaleValue() {
        // Simulates a reused pool thread that still carries a previous request's id...
        StickyContextHolder.set("PREVIOUS-REQUEST");
        // ...restoring a null snapshot must clear it, not keep the stale value.
        accessor.setValue();
        assertThat(accessor.getValue()).isNull();
    }
}
