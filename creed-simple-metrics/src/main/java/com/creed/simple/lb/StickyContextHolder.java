package com.creed.simple.lb;

/**
 * Per-thread carrier for the caller's sticky-session id ({@code stickyId} cookie), bridging the Camel
 * route thread to the Spring Cloud LoadBalancer supplier chain.
 *
 * <p>Why a ThreadLocal works here: the camel-http producer executes synchronously on the route thread
 * (a Tomcat worker or an {@code aggregatePoolA} thread), and {@link LoadBalancerRoutePlanner} calls the
 * blocking {@code LoadBalancerClient.choose(serviceId)} from inside that same call stack — so a value
 * set by a processor step earlier in the route is visible when
 * {@code StickyMetadataServiceInstanceListSupplier} captures it in {@code get()}.
 *
 * <p>Staleness contract: pooled threads are reused, so callers must <em>always overwrite</em> the slot
 * before the downstream call ({@code set(null)} when the request carries no sticky cookie) rather than
 * relying on a try/finally clear.
 */
public final class StickyContextHolder {

    private static final ThreadLocal<String> STICKY_ID = new ThreadLocal<>();

    private StickyContextHolder() {
    }

    /** Overwrites the current thread's sticky id; pass {@code null} for "no stickiness requested". */
    public static void set(String stickyId) {
        STICKY_ID.set(stickyId);
    }

    /** The sticky id of the request currently processed by this thread, or {@code null}. */
    public static String get() {
        return STICKY_ID.get();
    }

    public static void clear() {
        STICKY_ID.remove();
    }
}
