package com.creed.framework.tracker.logbook;

import com.creed.framework.tracker.TrackerContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.zalando.logbook.Correlation;
import org.zalando.logbook.HttpLogFormatter;
import org.zalando.logbook.HttpRequest;
import org.zalando.logbook.HttpResponse;
import org.zalando.logbook.Origin;
import org.zalando.logbook.Precorrelation;
import org.zalando.logbook.Sink;

import java.io.IOException;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Logbook's sink, with every line telling where it comes from. One Logbook serves both sides - the servlet filter
 * (requests this application answers) and the HTTP clients (requests it makes: RestClient / RestTemplate / Apache
 * HttpClient) - and its default writer prints both on {@code org.zalando.logbook.Logbook} in the same shape, so an
 * outbound Jira call and the inbound request that caused it could not be told apart.
 *
 * <ul>
 *   <li>its own logger per direction: {@code creed.tracker.http.inbound} (Logbook's {@link Origin#REMOTE} - the caller
 *       is remote) and {@code creed.tracker.http.outbound} ({@link Origin#LOCAL}), at INFO;</li>
 *   <li>{@code sid=… direction=inbound peer=…} / {@code direction=outbound peer=…} in the message itself (the client
 *       address inbound, the called host outbound) - Splunk extracts key=value pairs from the text, it does not see the
 *       MDC unless the pattern prints it. With {@code logbook.format.style: json} the fields go into the JSON object
 *       instead.</li>
 * </ul>
 *
 * <p><b>Only inbound lines carry a sid</b> (2026-11-22, asked for): the journey is the requests the application
 * answers. An outbound call (RestTemplate / RestClient / Apache HttpClient) is the application's own work, often made
 * on another thread where the sid is not - it is found by traceId, like any other line of that work.
 *
 * <p>The sid is the MDC's when the request is written. A response can be written on another thread (an async request
 * completing), so the request's sid is remembered by correlation id until its response is written - bounded, a
 * response that never comes (the client's connection died) must not leak.
 */
public final class TrackingSink implements Sink {

    static final Logger INBOUND = LoggerFactory.getLogger("creed.tracker.http.inbound");
    static final Logger OUTBOUND = LoggerFactory.getLogger("creed.tracker.http.outbound");

    private static final int REMEMBERED = 4096;

    private final HttpLogFormatter formatter;
    private final Map<String, String> sids = Collections.synchronizedMap(new LinkedHashMap<>(256, 0.75f, false) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<String, String> eldest) {
            return size() > REMEMBERED;
        }
    });

    public TrackingSink(HttpLogFormatter formatter) {
        this.formatter = formatter;
    }

    @Override
    public boolean isActive() {
        return INBOUND.isInfoEnabled() || OUTBOUND.isInfoEnabled();
    }

    @Override
    public void write(Precorrelation precorrelation, HttpRequest request) throws IOException {
        Logger log = logger(request);
        if (!log.isInfoEnabled()) {
            return;
        }
        String sid = inbound(request) ? TrackerContext.current() : null;
        if (sid != null) {
            sids.put(precorrelation.getId(), sid);
        }
        log.info(stamp(formatter.format(precorrelation, request), request, sid));
    }

    @Override
    public void write(Correlation correlation, HttpRequest request, HttpResponse response) throws IOException {
        Logger log = logger(request);
        String remembered = sids.remove(correlation.getId());
        if (!log.isInfoEnabled()) {
            return;
        }
        String sid = !inbound(request) ? null : remembered != null ? remembered : TrackerContext.current();
        log.info(stamp(formatter.format(correlation, response), request, sid));
    }

    private static Logger logger(HttpRequest request) {
        return inbound(request) ? INBOUND : OUTBOUND;
    }

    private static boolean inbound(HttpRequest request) {
        return request.getOrigin() == Origin.REMOTE;
    }

    /** Inbound: {@code sid=} first ({@code -} outside a journey). Outbound: no sid at all. */
    static String stamp(String formatted, HttpRequest request, String sid) {
        boolean in = inbound(request);
        String direction = in ? "inbound" : "outbound";
        String peer = in ? request.getRemote() : request.getHost();
        String s = sid == null ? TrackerContext.NONE : sid;
        if (formatted.startsWith("{")) {
            return "{" + (in ? "\"sid\":\"" + json(s) + "\"," : "") + "\"direction\":\"" + direction + "\",\"peer\":\""
                    + json(peer) + "\"," + formatted.substring(1);
        }
        return (in ? "sid=" + s + " " : "") + "direction=" + direction + " peer=" + plain(peer) + " " + formatted;
    }

    private static String json(String value) {
        return value == null ? "" : value.replace("\\", "\\\\").replace("\"", "\\\"");
    }

    private static String plain(String value) {
        return value == null || value.isBlank() ? "-" : value.replaceAll("\\s", "_");
    }
}
