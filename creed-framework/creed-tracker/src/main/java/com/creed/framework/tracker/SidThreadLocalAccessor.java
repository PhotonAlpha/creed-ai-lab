package com.creed.framework.tracker;

import io.micrometer.context.ThreadLocalAccessor;
import org.slf4j.MDC;

/**
 * Carries the sid onto threads a request hands work to through context propagation
 * ({@code ContextSnapshotFactory.captureAll().wrap(...)}, Reactor, {@code ContextPropagatingTaskDecorator}): the MDC is
 * a ThreadLocal, so a virtual thread making the Jira calls would otherwise log them with no sid.
 *
 * <p>Registered through {@code META-INF/services/io.micrometer.context.ThreadLocalAccessor}, which
 * {@code ContextRegistry.getInstance()} loads - no bean, nothing to wire. Its own key, not micrometer's
 * {@code Slf4jThreadLocalAccessor}: that one's key is fixed, and a second registration under it would replace whatever
 * the application registered.
 */
public class SidThreadLocalAccessor implements ThreadLocalAccessor<String> {

    public static final String KEY = "creed.tracker.sid";

    @Override
    public Object key() {
        return KEY;
    }

    @Override
    public String getValue() {
        return MDC.get(TrackerContext.MDC_KEY);
    }

    @Override
    public void setValue(String value) {
        MDC.put(TrackerContext.MDC_KEY, value);
    }

    @Override
    public void setValue() {
        MDC.remove(TrackerContext.MDC_KEY);
    }
}
