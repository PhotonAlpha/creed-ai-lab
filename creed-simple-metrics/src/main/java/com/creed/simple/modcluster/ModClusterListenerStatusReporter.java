package com.creed.simple.modcluster;

import lombok.extern.slf4j.Slf4j;
import org.jboss.modcluster.ModClusterServiceMBean;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.ApplicationListener;

import java.net.InetSocketAddress;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Answers, in the startup log, the one question the mod_cluster listener does not: <em>did this node
 * actually get registered?</em>
 *
 * <p>{@link ModClusterListenerConfiguration}'s listener registers the node on its own, but says nothing
 * conclusive about it — its MCMP traffic is DEBUG-level, and a proxy that refused (or was never
 * reachable) looks exactly like one that accepted. So this bean asks each proxy what it holds:
 * {@link ModClusterServiceMBean#getProxyInfo()} issues an MCMP {@code INFO} per proxy and returns the
 * dump it replied with, and this node counts as registered only when its {@code JVMRoute} is named in
 * that dump.
 *
 * <p>Two details that keep the answer honest:
 * <ul>
 *   <li><strong>It waits.</strong> Registration is asynchronous with respect to
 *       {@code ApplicationReadyEvent} (the listener's work happens on the Server's
 *       {@code AFTER_START}, and with {@code advertise} a proxy can appear seconds later), so the
 *       banner is delayed by a bounded poll — {@code creed.mod-cluster.startup-timeout} — rather than
 *       printed against a half-finished state.</li>
 *   <li><strong>The node line comes from the proxy's answer, not from our configuration.</strong> With
 *       this integration the advertised address is chosen by mod_cluster from the Tomcat connector, and
 *       the two genuinely differ (a multi-homed host resolves its own name to one address while the
 *       connector binds another). Printing the local guess would make the banner look right while
 *       traffic went somewhere else.</li>
 * </ul>
 *
 * <p>The verdict is carried by the <strong>log level</strong>, not only by the text: INFO when every
 * proxy took the node, WARN when only some did, ERROR when none did — so the outcome survives a log
 * aggregator that only keeps WARN and above. With {@code creed.mod-cluster.fail-fast=true}, "none did"
 * also aborts the startup.
 */
@Slf4j
public class ModClusterListenerStatusReporter implements ApplicationListener<ApplicationReadyEvent> {

    private static final long POLL_INTERVAL_MS = 500;

    private final ModClusterServiceMBean service;
    private final ModClusterProperties properties;
    private final String applicationName;

    public ModClusterListenerStatusReporter(ModClusterServiceMBean service,
                                            ModClusterProperties properties,
                                            String applicationName) {
        this.service = service;
        this.properties = properties;
        this.applicationName = applicationName;
    }

    /** One round's view: what each proxy says, plus the node as the proxy actually holds it. */
    record Snapshot(List<ProxyState> states, NodeIdentity identity) {

        boolean complete() {
            return !states.isEmpty() && states.stream().allMatch(ProxyState::registered);
        }

        long registered() {
            return states.stream().filter(ProxyState::registered).count();
        }
    }

    @Override
    public void onApplicationEvent(ApplicationReadyEvent event) {
        if (!properties.enabled()) {
            return;
        }
        NodeIdentity configured = NodeIdentity.resolve(
                properties, applicationName, NodeIdentity.webServerPort(event.getApplicationContext()));
        Snapshot snapshot = awaitSnapshot(configured);
        logBanner(snapshot);

        if (snapshot.registered() == 0 && properties.failFast()) {
            // Thrown from the ready-event listener: SpringApplication.run() propagates it and the
            // context is closed, i.e. the process refuses to run un-registered. Off by default.
            throw new IllegalStateException("mod_cluster registration failed (no proxy holds JVMRoute="
                    + snapshot.identity().jvmRoute() + ") and creed.mod-cluster.fail-fast=true");
        }
    }

    /** Polls until every known proxy holds the node, or the configured timeout expires. */
    private Snapshot awaitSnapshot(NodeIdentity configured) {
        long deadline = System.nanoTime() + properties.startupTimeout().toNanos();
        while (true) {
            Snapshot snapshot = snapshot(configured);
            if (snapshot.complete() || System.nanoTime() >= deadline) {
                return snapshot;
            }
            try {
                Thread.sleep(POLL_INTERVAL_MS);
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
                return snapshot;
            }
        }
    }

    /**
     * One {@code INFO} round. The keys are the proxies mod_cluster currently knows — the configured
     * list plus anything {@code advertise} discovered — so the configured entries are folded in
     * explicitly: a proxy that was never contacted must still appear on the banner as NOT REGISTERED
     * rather than silently vanish from it.
     */
    Snapshot snapshot(NodeIdentity configured) {
        Map<String, ProxyState> states = new LinkedHashMap<>();
        for (String proxy : properties.proxies()) {
            states.put(normalize(proxy), new ProxyState(proxy, false, "no INFO response from this proxy"));
        }
        NodeIdentity identity = configured;
        for (Map.Entry<InetSocketAddress, String> entry : queryProxyInfo().entrySet()) {
            String proxy = entry.getKey().getHostString() + ":" + entry.getKey().getPort();
            String dump = entry.getValue();
            states.put(normalize(proxy),
                    new ProxyState(proxy, holdsNode(dump, configured), summarize(dump, configured)));
            if (holdsNode(dump, configured)) {
                identity = identityFromInfo(dump, identity);
            }
        }
        return new Snapshot(new ArrayList<>(states.values()), identity);
    }

    private Map<InetSocketAddress, String> queryProxyInfo() {
        try {
            Map<InetSocketAddress, String> info = service.getProxyInfo();
            return info == null ? Map.of() : info;
        } catch (RuntimeException ex) {
            // getProxyInfo() talks to every proxy; a dead one must not break the banner.
            log.debug("mod_cluster INFO query failed", ex);
            return Map.of();
        }
    }

    /** The proxy's {@code INFO} dump names every node it holds as {@code Name: <JVMRoute>}. */
    private static boolean holdsNode(String dump, NodeIdentity identity) {
        return dump != null && dump.contains("Name: " + identity.jvmRoute());
    }

    /**
     * One line of evidence for the banner: the proxy's own node line plus how many contexts it has
     * enabled — the number that decides whether traffic actually arrives.
     */
    private static String summarize(String dump, NodeIdentity identity) {
        if (dump == null || dump.isBlank()) {
            return "proxy answered INFO with nothing (unreachable, or it holds no node)";
        }
        if (!holdsNode(dump, identity)) {
            return "proxy does not hold JVMRoute=" + identity.jvmRoute()
                    + " (INFO listed " + dump.lines().filter(line -> line.startsWith("Node:")).count()
                    + " other node(s))";
        }
        String nodeLine = dump.lines()
                .filter(line -> line.contains("Name: " + identity.jvmRoute()))
                .findFirst().orElse("")
                .trim();
        long enabledContexts = dump.lines()
                .filter(line -> line.startsWith("Context:") && line.contains("Status: ENABLED"))
                .count();
        return "%s | contexts enabled=%d".formatted(truncate(nodeLine), enabledContexts);
    }

    /**
     * Rewrites the node line from the proxy's {@code INFO} node record — {@code Host}/{@code Port}/
     * {@code Type} are what the proxy will actually dial, and {@code Alias} what it filed the contexts
     * under. Anything the dump does not carry keeps the locally computed value.
     */
    private static NodeIdentity identityFromInfo(String dump, NodeIdentity fallback) {
        String nodeLine = dump.lines()
                .filter(line -> line.contains("Name: " + fallback.jvmRoute()))
                .findFirst().orElse(null);
        if (nodeLine == null) {
            return fallback;
        }
        Map<String, String> fields = new LinkedHashMap<>();
        for (String field : nodeLine.split(",")) {
            int colon = field.indexOf(':');
            if (colon > 0) {
                fields.put(field.substring(0, colon).trim(), field.substring(colon + 1).trim());
            }
        }
        List<String> aliases = dump.lines()
                .filter(line -> line.contains("Alias: "))
                .map(line -> line.substring(line.indexOf("Alias: ") + "Alias: ".length()).trim())
                .filter(alias -> !alias.isEmpty())
                .distinct()
                .toList();
        int port;
        try {
            port = Integer.parseInt(fields.getOrDefault("Port", String.valueOf(fallback.port())));
        } catch (NumberFormatException ex) {
            port = fallback.port();
        }
        return new NodeIdentity(
                fallback.jvmRoute(),
                fields.getOrDefault("Host", fallback.host()),
                port,
                fields.getOrDefault("Type", fallback.type()),
                aliases.isEmpty() ? fallback.aliases() : aliases);
    }

    private void logBanner(Snapshot snapshot) {
        ModClusterProperties.Balancer balancer = properties.balancer();
        NodeIdentity identity = snapshot.identity();
        List<ProxyState> states = snapshot.states();
        long registered = snapshot.registered();

        StringBuilder banner = new StringBuilder("\n")
                .append("================== mod_cluster registration ==================\n")
                .append(" node      : JVMRoute=%s  %s%n".formatted(identity.jvmRoute(), identity.address()))
                .append(" balancer  : %s%s  sticky=%s (force=%s, remove=%s)%n".formatted(
                        balancer.name(),
                        properties.node().domain().isBlank() ? "" : " domain=" + properties.node().domain(),
                        balancer.stickySession(), balancer.stickySessionForce(),
                        balancer.stickySessionRemove()))
                .append(" contexts  : discovered by Tomcat  aliases=%s%n".formatted(identity.aliases()))
                .append(" manager   : %s://<proxy>/  (MCMP, advertise=%s)%n".formatted(
                        properties.managerScheme(), properties.advertise()));
        for (ProxyState state : states) {
            banner.append(" proxy %-24s %s%n".formatted(
                    state.proxy(), state.registered() ? "REGISTERED" : "NOT REGISTERED"));
            banner.append("   detail  : %s%n".formatted(state.detail()));
        }
        String verdict = states.isEmpty() || registered == 0 ? "FAILED"
                : registered == states.size() ? "OK" : "PARTIAL";
        banner.append(" result    : %d/%d proxies registered => %s%n".formatted(
                        registered, states.size(), verdict))
                .append("==============================================================");

        if (!states.isEmpty() && registered == states.size()) {
            log.info("{}", banner);
        } else if (registered == 0) {
            log.error("{}", banner);
        } else {
            log.warn("{}", banner);
        }
    }

    private static String truncate(String line) {
        return line.length() <= 180 ? line : line.substring(0, 180) + "…";
    }

    /** Proxy entries can be written with or without a scheme; compare them without one. */
    private static String normalize(String proxy) {
        int scheme = proxy.indexOf("://");
        return (scheme < 0 ? proxy : proxy.substring(scheme + 3)).trim().toLowerCase();
    }
}
