package com.creed.framework.tracker;

import org.slf4j.MDC;

/**
 * The current request's sid lives on the MDC under {@value #MDC_KEY} (a logging pattern prints it as {@code %X{sid}}).
 * Only the request thread has it, on purpose (2026-11-22, asked for): the sid marks the requests this application
 * answers and the user's journey through them, not the work they do - a thread the request hands work to (a virtual
 * thread, a pool) has none, and its log lines are found by traceId.
 */
public final class TrackerContext {

    public static final String MDC_KEY = "sid";

    /** What a log line shows for a request outside any journey (no cookie yet). */
    public static final String NONE = "-";

    private TrackerContext() {
    }

    /** The sid of the request this thread works for, or {@code null}. */
    public static String current() {
        return MDC.get(MDC_KEY);
    }

    static void set(String sid) {
        if (sid == null) {
            MDC.remove(MDC_KEY);
        } else {
            MDC.put(MDC_KEY, sid);
        }
    }
}
