package com.creed.simple.modcluster;

import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.web.context.WebServerApplicationContext;
import org.springframework.context.ApplicationContext;
import org.springframework.util.StringUtils;

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.List;

/**
 * This process as the {@code mod_cluster} proxy sees it.
 *
 * <p>Built twice over a startup: first from configuration (what we <em>expect</em> the proxy to be
 * told), then rewritten from the proxy's own {@code INFO} answer (what it actually holds). The second
 * one is what the banner prints — with this integration mod_cluster picks the advertised address off
 * the Tomcat connector, so the two genuinely differ on a multi-homed host, and printing the guess would
 * make a banner look right while traffic went elsewhere.
 *
 * @param jvmRoute the node's cluster-wide identity (the Tomcat {@code Engine}'s jvmRoute)
 * @param host     the address the proxy dials back
 * @param port     the port the proxy dials back
 * @param type     the scheme the proxy dials back with; blank until the proxy has reported it
 * @param aliases  the virtual-host aliases the contexts are filed under
 */
@Slf4j
public record NodeIdentity(String jvmRoute, String host, int port, String type, List<String> aliases) {

    public String address() {
        return StringUtils.hasText(type)
                ? "%s://%s:%d".formatted(type, host, port)
                : "%s:%d".formatted(host, port);
    }

    /**
     * The identity as configuration describes it. Blank {@code node.host} becomes the local address,
     * {@code node.port} of {@code 0} the port the server actually bound, a blank {@code jvm-route}
     * {@code <application-name>-<port>}, and an empty alias list the local hostname. The scheme is left
     * blank on purpose: mod_cluster, not this configuration, decides what it advertises.
     */
    public static NodeIdentity resolve(ModClusterProperties properties, String applicationName, int port) {
        ModClusterProperties.Node node = properties.node();
        int resolvedPort = node.port() > 0 ? node.port() : port;
        String host = StringUtils.hasText(node.host()) ? node.host().trim() : localAddress();
        String jvmRoute = StringUtils.hasText(node.jvmRoute())
                ? node.jvmRoute().trim()
                : applicationName + "-" + resolvedPort;
        List<String> aliases = node.aliases().isEmpty() ? List.of(localHostName()) : node.aliases();
        return new NodeIdentity(jvmRoute, host, resolvedPort, "", aliases);
    }

    /** The port the embedded server bound, falling back to the configured {@code server.port}. */
    public static int webServerPort(ApplicationContext context) {
        if (context instanceof WebServerApplicationContext webServerContext
                && webServerContext.getWebServer() != null) {
            return webServerContext.getWebServer().getPort();
        }
        return context.getEnvironment().getProperty("server.port", Integer.class, 0);
    }

    public static String localAddress() {
        try {
            return InetAddress.getLocalHost().getHostAddress();
        } catch (UnknownHostException ex) {
            log.warn("Cannot resolve the local address for the mod_cluster node — falling back to "
                    + "127.0.0.1. Set creed.mod-cluster.node.host explicitly.", ex);
            return "127.0.0.1";
        }
    }

    public static String localHostName() {
        try {
            return InetAddress.getLocalHost().getHostName();
        } catch (UnknownHostException ex) {
            return "localhost";
        }
    }
}
