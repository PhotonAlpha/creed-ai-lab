package com.creed.framework.tracker;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.util.unit.DataSize;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

/**
 * {@code creed.tracker.*}. A journey starts when a request hits one of {@link #startPaths}: the answer sets a new
 * {@code sid} cookie, which the browser then sends on every later request to the same origin.
 */
@Getter
@Setter
@ConfigurationProperties(prefix = "creed.tracker")
public class TrackerProperties {

    private boolean enabled = true;

    /** Paths (Ant patterns, without the servlet context path) whose request starts a new journey - a new sid. */
    private List<String> startPaths = new ArrayList<>();

    private Cookie cookie = new Cookie();

    /** Response header naming the request's sid (the cookie is HttpOnly, so the page cannot read it); blank = none. */
    private String responseHeader = "X-Session-Id";

    /**
     * Response headers larger than this are logged (WARN, with the sid) - Tomcat answers 500 when they exceed
     * {@code server.tomcat.max-http-response-header-size} and the application never hears of it. Unset = that property (8KB).
     */
    private DataSize responseHeaderWarnSize;

    private Logbook logbook = new Logbook();

    @Getter
    @Setter
    public static class Cookie {
        private String name = "sid";
        /** Lax: sent on top-level navigation and every same-site request, never on cross-site sub-requests. */
        private String sameSite = "Lax";
        private String path = "/";
        private boolean httpOnly = true;
        /** null = follow the request (Secure only when it came over HTTPS). */
        private Boolean secure;
        /** null = a session cookie (gone when the browser closes). */
        private Duration maxAge;
    }

    @Getter
    @Setter
    public static class Logbook {
        /** Replace Logbook's Sink with {@code TrackingSink} (sid + direction on each line, one logger per direction). */
        private boolean enabled = true;
    }
}
