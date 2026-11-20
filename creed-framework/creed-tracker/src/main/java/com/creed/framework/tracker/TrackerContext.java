package com.creed.framework.tracker;

import org.slf4j.MDC;

/**
 * The current request's sid lives on the MDC under {@value #MDC_KEY} (a logging pattern prints it as {@code %X{sid}}).
 * A thread started by the request does not inherit it - see {@code SidThreadLocalAccessor}.
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
