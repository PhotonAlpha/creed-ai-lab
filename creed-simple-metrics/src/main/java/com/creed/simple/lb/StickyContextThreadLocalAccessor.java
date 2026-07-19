package com.creed.simple.lb;

import io.micrometer.context.ThreadLocalAccessor;

/**
 * Bridges {@link StickyContextHolder}'s plain ThreadLocal into Micrometer context-propagation, so
 * {@code ContextSnapshotFactory.captureAll()} carries the sticky id across executor boundaries (the
 * ProducerTemplate async pool — see {@code CamelConfig.producerTemplate}) the same way it carries the
 * Observation scope.
 *
 * <p>Restore semantics interplay with the holder's "always overwrite" staleness contract: at submit
 * time the caller thread's value (possibly {@code null}) is snapshotted; on the pool thread a
 * {@code null} snapshot triggers {@link #setValue()} — i.e. an explicit clear, never "keep whatever
 * the reused thread had". After the task the pre-task value is restored, so wrapped pool threads can
 * no longer leak a previous request's sticky id.
 *
 * <p>Registered on the global {@link io.micrometer.context.ContextRegistry} (registration replaces
 * any accessor with the same key, so re-registering is harmless); every {@code captureAll()}-based
 * mechanism in the app then propagates it, not just the Camel executor.
 */
public final class StickyContextThreadLocalAccessor implements ThreadLocalAccessor<String> {

    public static final String KEY = "creed.stickyId";

    @Override
    public Object key() {
        return KEY;
    }

    @Override
    public String getValue() {
        return StickyContextHolder.get();
    }

    @Override
    public void setValue(String value) {
        StickyContextHolder.set(value);
    }

    @Override
    public void setValue() {
        StickyContextHolder.clear();
    }

    @Override
    public void reset() {
        StickyContextHolder.clear();
    }
}
