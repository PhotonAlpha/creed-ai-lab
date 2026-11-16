package com.creed.resource.lbhealth.lb;

import io.micrometer.context.ContextRegistry;
import org.slf4j.MDC;
import reactor.core.publisher.Hooks;

import java.util.concurrent.atomic.AtomicBoolean;

import static com.creed.resource.lbhealth.lb.ObservableHealthCheckServiceInstanceListSupplier.MDC_ROUND;
import static com.creed.resource.lbhealth.lb.ObservableHealthCheckServiceInstanceListSupplier.MDC_TRIGGER;

/**
 * Carries a probe's round (MDC {@code hcTrigger} / {@code hcRound}) to the threads a WebClient probe is logged on.
 * Logbook writes a WebClient probe's request and response on the JDK HttpClient's worker threads, not on the Reactor
 * thread that subscribed - an MDC set around the subscription never reaches them (measured: both lines came out
 * without it). Reactor's automatic context propagation restores registered ThreadLocals from the Reactor Context on
 * whatever thread a signal arrives, so the supplier writes the round into the Context and this registers the MDC keys.
 *
 * <p>Global and once per JVM ({@link Hooks}): enabled only for the debug + {@code webclient} mode that needs it.
 */
public final class HealthCheckMdc {

    private static final AtomicBoolean ENABLED = new AtomicBoolean();

    private HealthCheckMdc() {
    }

    public static void enableForWebClient() {
        if (!ENABLED.compareAndSet(false, true)) {
            return;
        }
        for (String key : new String[] {MDC_TRIGGER, MDC_ROUND}) {
            ContextRegistry.getInstance().registerThreadLocalAccessor(key,
                    () -> MDC.get(key), value -> MDC.put(key, value), () -> MDC.remove(key));
        }
        Hooks.enableAutomaticContextPropagation();
    }
}
