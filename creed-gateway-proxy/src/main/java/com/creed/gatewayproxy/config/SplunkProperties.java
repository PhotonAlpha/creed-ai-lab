package com.creed.gatewayproxy.config;

import java.time.Duration;
import java.time.ZoneId;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.util.StringUtils;

import com.creed.gatewayproxy.service.splunk.BlockWindows;

/**
 * The Splunk session broker (ported from the Node BFF's server/splunk/config.js). No password is a
 * property — each target's comes from {@code SPLUNK_TARGET_<ID>_PASSWORD} / {@code _FILE} or the
 * startup prompt (SplunkSecrets), so a configuration file never holds one.
 *
 * @param enabled          the switch for the real call; false answers a fabricated {@code mock-…} cookie
 * @param sessionCookie    default for targets that set none: the cookie read off Splunk's login response
 *                         ({@code splunkd_<web port>})
 * @param scriptCookieName default for targets that set none: the cookie the returned script sets
 * @param tlsInsecure      on by request: Splunk's certificate is not verified (chain nor host name),
 *                         scoped to the login client alone
 * @param defaultTarget    the dropdown's initial choice; the first target when blank
 */
@ConfigurationProperties("creed.splunk")
public record SplunkProperties(Boolean enabled, String sessionCookie, String scriptCookieName, String scriptCookiePath,
                               Boolean prefetchCval, Duration connectTimeout, Duration readTimeout, Boolean tlsInsecure,
                               String caFile, String defaultTarget, List<Target> targets, Block block, Audit audit) {

    /** An RFC 6265 cookie-name token, narrowed: it is interpolated into a {@code document.cookie} script. */
    public static final Pattern COOKIE_NAME = Pattern.compile("^[A-Za-z0-9_.-]{1,64}$");
    /** Becomes part of an environment variable name. */
    static final Pattern TARGET_ID = Pattern.compile("^[A-Za-z0-9_]{1,32}$");

    public SplunkProperties {
        enabled = enabled != null && enabled;
        sessionCookie = cookie("session-cookie", sessionCookie, "splunkd_8000");
        scriptCookieName = cookie("script-cookie-name", scriptCookieName, "splunkd_8089");
        scriptCookiePath = StringUtils.hasText(scriptCookiePath) ? scriptCookiePath : "/";
        prefetchCval = prefetchCval == null || prefetchCval;
        connectTimeout = connectTimeout == null ? Duration.ofSeconds(5) : connectTimeout;
        readTimeout = readTimeout == null ? Duration.ofSeconds(15) : readTimeout;
        tlsInsecure = tlsInsecure == null || tlsInsecure;
        caFile = StringUtils.hasText(caFile) ? caFile : null;
        if (targets == null || targets.isEmpty()) {
            throw new IllegalArgumentException("creed.splunk.targets must name at least one login target");
        }
        Set<String> seen = new HashSet<>();
        for (Target t : targets) {
            if (!seen.add(t.id())) throw new IllegalArgumentException("creed.splunk.targets: '" + t.id() + "' is listed twice");
        }
        targets = List.copyOf(targets);
        if (StringUtils.hasText(defaultTarget)) {
            String wanted = defaultTarget.strip();
            defaultTarget = targets.stream().filter(t -> t.id().equalsIgnoreCase(wanted)).map(Target::id).findFirst()
                    .orElseThrow(() -> new IllegalArgumentException("creed.splunk.default-target '" + wanted + "' is not one of the targets"));
        } else {
            defaultTarget = targets.getFirst().id();
        }
        block = block == null ? new Block(null, null) : block;
        audit = audit == null ? new Audit(null, null, null, null) : audit;
    }

    /** A target's own cookie settings, or the broker-wide default. */
    public String sessionCookieOf(Target t) {
        return t.sessionCookie() != null ? t.sessionCookie() : sessionCookie;
    }

    public String scriptCookieNameOf(Target t) {
        return t.scriptCookieName() != null ? t.scriptCookieName() : scriptCookieName;
    }

    public String scriptCookiePathOf(Target t) {
        return t.scriptCookiePath() != null ? t.scriptCookiePath() : scriptCookiePath;
    }

    static String cookie(String name, String value, String fallback) {
        String v = StringUtils.hasText(value) ? value.strip() : fallback;
        if (v != null && !COOKIE_NAME.matcher(v).matches()) {
            throw new IllegalArgumentException("creed.splunk." + name + " must match " + COOKIE_NAME + ", got '" + v + "'");
        }
        return v;
    }

    /**
     * One Splunk instance. The password is not here — see SplunkSecrets.
     *
     * @param tunnel        {@code host:port} of a TCP forward the login may connect through (curl
     *                      {@code --connect-to}: the socket moves, the URL, Host header and SNI stay the target's)
     * @param tunnelDefault whether a login goes through the tunnel when the request does not say
     */
    public record Target(String id, String label, String loginUrl, String username, String sessionCookie,
                         String scriptCookieName, String scriptCookiePath, String tunnel, Boolean tunnelDefault) {

        private static final Pattern HOST_PORT = Pattern.compile("^(?:\\[([0-9a-fA-F:.]+)]|([A-Za-z0-9.-]+)):(\\d{1,5})$");

        public Target {
            if (id == null || !TARGET_ID.matcher(id).matches()) {
                throw new IllegalArgumentException("creed.splunk.targets[].id must match " + TARGET_ID + ", got '" + id + "'");
            }
            label = StringUtils.hasText(label) ? label : id;
            loginUrl = StringUtils.hasText(loginUrl) ? loginUrl.strip() : null;
            username = StringUtils.hasText(username) ? username.strip() : null;
            sessionCookie = cookie("targets[" + id + "].session-cookie", sessionCookie, null);
            scriptCookieName = cookie("targets[" + id + "].script-cookie-name", scriptCookieName, null);
            scriptCookiePath = StringUtils.hasText(scriptCookiePath) ? scriptCookiePath : null;
            tunnel = StringUtils.hasText(tunnel) ? tunnel.strip() : null;
            if (tunnel != null) parseTunnel(id, tunnel);
            tunnelDefault = tunnel != null && tunnelDefault != null && tunnelDefault;
        }

        /** The environment variable (or {@code _FILE}) holding this target's password. */
        public String passwordVariable() {
            return "SPLUNK_TARGET_" + id.toUpperCase(Locale.ROOT) + "_PASSWORD";
        }

        public HostPort tunnelAddress() {
            return tunnel == null ? null : parseTunnel(id, tunnel);
        }

        private static HostPort parseTunnel(String id, String raw) {
            Matcher m = HOST_PORT.matcher(raw);
            int port = m.matches() ? Integer.parseInt(m.group(3)) : -1;
            if (port < 1 || port > 65535) {
                throw new IllegalArgumentException("creed.splunk.targets[" + id + "].tunnel must be host:port, got '" + raw + "'");
            }
            return new HostPort(m.group(1) != null ? m.group(1) : m.group(2), port);
        }
    }

    public record HostPort(String host, int port) {
        @Override
        public String toString() {
            return (host.contains(":") ? "[" + host + "]" : host) + ":" + port;
        }
    }

    /**
     * Times of day at which no session is issued.
     *
     * @param windows {@code HH:mm-HH:mm[,…]}, start inclusive, end exclusive; an end at or before the
     *                start crosses midnight ({@code 22:00-09:00}). Blank: never blocked.
     * @param zone    the clock the windows are read on; the server's default zone when blank
     */
    public record Block(String windows, String zone) {

        public Block {
            windows = windows == null ? "" : windows.strip();
            zone = StringUtils.hasText(zone) ? zone.strip() : ZoneId.systemDefault().getId();
            ZoneId.of(zone); // fail at startup on a typo
            BlockWindows.parse(windows);
        }
    }

    /**
     * Where audit rows go: {@code memory} (newest 500, lost on restart), {@code pg}
     * ({@code <schema>.splunk_audit}) or {@code mysql} ({@code splunk_audit} in the URL's database).
     * The password comes from {@code SPLUNK_DB_PASSWORD} / {@code _FILE} or the prompt.
     *
     * @param url JDBC URL; the Node BFF's {@code postgres://…} / {@code mysql://…} form is accepted too
     */
    public record Audit(String store, String url, String username, String schema) {

        private static final Pattern IDENTIFIER = Pattern.compile("^[a-z_][a-z0-9_]*$");

        public Audit {
            store = StringUtils.hasText(store) ? store.strip().toLowerCase(Locale.ROOT) : "memory";
            if (!List.of("memory", "pg", "mysql").contains(store)) {
                throw new IllegalArgumentException("creed.splunk.audit.store must be memory, pg or mysql, got '" + store + "'");
            }
            url = jdbcUrl(StringUtils.hasText(url) ? url.strip()
                    : "mysql".equals(store) ? "jdbc:mysql://127.0.0.1:3306/env_matrix" : "jdbc:postgresql://127.0.0.1:5432/env_matrix");
            username = StringUtils.hasText(username) ? username : "artifactory";
            schema = StringUtils.hasText(schema) ? schema : "splunk_broker";
            // Interpolated into DDL.
            if (!IDENTIFIER.matcher(schema).matches()) {
                throw new IllegalArgumentException("creed.splunk.audit.schema must match " + IDENTIFIER + ", got '" + schema + "'");
            }
            String expected = switch (store) {
                case "pg" -> "jdbc:postgresql:";
                case "mysql" -> "jdbc:mysql:";
                default -> null;
            };
            if (expected != null && !url.startsWith(expected)) {
                throw new IllegalArgumentException("creed.splunk.audit.url '" + url.replaceAll("//[^@/]*@", "//***@")
                        + "' does not fit store " + store);
            }
        }

        /** {@code postgres://h:p/db} → {@code jdbc:postgresql://h:p/db}; credentials in the URL are dropped (they come separately). */
        static String jdbcUrl(String url) {
            String u = url.replaceFirst("^postgres(ql)?://", "jdbc:postgresql://").replaceFirst("^mysql://", "jdbc:mysql://");
            return u.replaceFirst("^(jdbc:[a-z]+://)[^@/]*@", "$1");
        }
    }
}
