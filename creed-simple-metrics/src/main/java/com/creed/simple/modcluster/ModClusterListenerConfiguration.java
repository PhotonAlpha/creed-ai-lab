package com.creed.simple.modcluster;

import lombok.extern.slf4j.Slf4j;
import org.apache.catalina.Engine;
import org.apache.catalina.Server;
import org.jboss.modcluster.config.ProxyConfiguration;
import org.jboss.modcluster.config.impl.ProxyConfigurationImpl;
import org.jboss.modcluster.container.tomcat.ModClusterListener;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.web.embedded.tomcat.TomcatServletWebServerFactory;
import org.springframework.boot.web.server.WebServerFactoryCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.util.StringUtils;

import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.UnknownHostException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

/**
 * Registers this process as a node of an Apache HTTP Server {@code mod_cluster} balancer, using the
 * upstream container integration ({@code org.jboss.mod_cluster:mod_cluster-container-tomcat-10.1}) —
 * the same library Red Hat JBoss Web Server drops into {@code $JWS_HOME/tomcat/lib} and wires with:
 *
 * <pre>{@code
 * <Listener className="org.jboss.modcluster.container.tomcat.ModClusterListener"
 *           advertise="true" stickySession="true" .../>
 * }</pre>
 *
 * <p>There is no {@code server.xml} here, so this class does programmatically the three things that
 * element would do: build the listener from configuration, put it on the {@code Server}, and give the
 * {@code Engine} its {@code jvmRoute}.
 *
 * <p><strong>The listener must be attached to the {@code Server}, before it initialises.</strong>
 * {@code TomcatEventHandlerAdapter} only reacts to {@code AFTER_INIT} / {@code START} /
 * {@code AFTER_START} / {@code BEFORE_STOP} / {@code STOP} events whose source is a {@link Server} — a
 * listener added to the Context or the Host receives none of them and registers nothing, silently. Boot
 * builds its {@code Tomcat} inside {@code getWebServer(...)}: it creates the context, adds it to the
 * Host, runs the {@code TomcatContextCustomizer}s, and only then starts the server. So a context
 * customizer is late enough for the parent chain (Context → Host → Engine → Service → Server) to be
 * wired, and early enough to still catch {@code AFTER_INIT}.
 *
 * <p><strong>{@code STATUS} cadence is the Engine's background processor.</strong> mod_cluster sends
 * {@code STATUS} on the Engine's {@code PERIODIC_EVENT}, every
 * {@code backgroundProcessorDelay × org.jboss.modcluster.container.catalina.status-frequency}
 * (the system property defaults to 1), so {@code creed.mod-cluster.status-interval} is mapped onto the
 * Engine's delay.
 *
 * <p>What the library then owns, and this module does not re-implement: the MCMP handshake
 * ({@code CONFIG} → {@code ENABLE-APP} → {@code STATUS}), the dynamic load factor
 * ({@code LoadMetric} SPI), context discovery, re-registration when a proxy forgets the node, session
 * draining, and the graceful {@code DISABLE-APP} → {@code STOP-APP} → {@code REMOVE-APP} on shutdown.
 * Whether any of it <em>worked</em> is the one thing it does not report — see
 * {@link ModClusterListenerStatusReporter}. Full writeup: {@code docs/mod-cluster-registration.md}.
 */
@Slf4j
@Configuration(proxyBeanMethods = false)
public class ModClusterListenerConfiguration {

    /**
     * The listener, configured from {@code creed.mod-cluster.*}.
     *
     * <p>{@code ModClusterListener} extends mod_cluster's own {@code ModClusterConfig}, so these
     * setters are exactly the attributes a {@code server.xml} {@code <Listener>} would carry.
     */
    @Bean
    ModClusterListener modClusterListener(ModClusterProperties properties) {
        ModClusterProperties.Node node = properties.node();
        ModClusterProperties.Balancer balancer = properties.balancer();

        ModClusterListener modCluster = new ModClusterListener();
        // setProxies(Collection<InetSocketAddress>) is deprecated since 1.3.1.Final; it only wrapped each
        // address in an anonymous ProxyConfiguration with no local bind address, which is what this does.
        modCluster.setProxyConfigurations(parseProxies(properties));
        boolean tls = "https".equalsIgnoreCase(properties.managerScheme());
        modCluster.setSsl(tls);
        if (tls) {
            configureTls(modCluster, properties.ssl());
        }
        modCluster.setSocketTimeout((int) properties.socketTimeout().toMillis());

        modCluster.setAdvertise(properties.advertise());
        if (StringUtils.hasText(properties.advertiseInterface())) {
            modCluster.setAdvertiseInterfaceName(properties.advertiseInterface());
        }
        if (StringUtils.hasText(properties.advertiseSecurityKey())) {
            modCluster.setAdvertiseSecurityKey(properties.advertiseSecurityKey());
        }

        modCluster.setBalancer(balancer.name());
        modCluster.setStickySession(balancer.stickySession());
        modCluster.setStickySessionRemove(balancer.stickySessionRemove());
        modCluster.setStickySessionForce(balancer.stickySessionForce());
        modCluster.setMaxAttempts(balancer.maxAttempts());
        // MCMP's WaitWorker parameter is spelled workerTimeout in the Java config.
        modCluster.setWorkerTimeout(balancer.waitWorker());

        if (StringUtils.hasText(node.domain())) {
            // MCMP's Domain parameter; "load balancing group" is the Java-side name for it.
            modCluster.setLoadBalancingGroup(node.domain());
        }
        modCluster.setFlushPackets(node.flushPackets());
        modCluster.setFlushWait(node.flushWait());
        modCluster.setPing(node.ping());
        if (node.smax() >= 0) {
            // Negative means "leave the proxy's own default" — which is also mod_cluster's own -1
            // sentinel, so not setting it and setting -1 coincide.
            modCluster.setSmax(node.smax());
        }
        modCluster.setTtl(node.ttl());
        modCluster.setNodeTimeout(node.timeout());
        // Only the STARTING load: from the first STATUS on, the LoadMetric provider computes it.
        modCluster.setInitialLoad(node.load());
        if (StringUtils.hasText(properties.loadMetricClass())) {
            modCluster.setLoadMetricClass(properties.loadMetricClass());
        }

        // What the proxy should dial back, when it must differ from what the connector binds
        // (containers, NAT, multi-NIC) — the "external" pair, not connectorAddress/Port, which select
        // WHICH local connector to advertise.
        if (StringUtils.hasText(node.host())) {
            String host = node.host().trim();
            // mod_cluster resolves this ON THIS HOST (TomcatConnector.getAddress), not on the proxy. A
            // name only the proxy can resolve — host.docker.internal from a Mac — comes back null, and
            // the library then fails every CONFIG with an NPE in DefaultMCMPRequestFactory: the proxy
            // answers INFO/STATUS but never learns the node. Use an address (e.g. Docker Desktop's
            // host-gateway 192.168.65.254) or a name that resolves on both sides.
            try {
                InetAddress.getByName(host);
            } catch (UnknownHostException ex) {
                log.error("mod_cluster: creed.mod-cluster.node.host '{}' cannot be resolved on THIS host. "
                        + "mod_cluster resolves it locally, so CONFIG will fail and the node will not be "
                        + "registered — use an IP address the proxy can reach.", host);
            }
            modCluster.setExternalConnectorAddress(host);
        }
        if (node.port() > 0) {
            modCluster.setExternalConnectorPort(node.port());
        }
        if (StringUtils.hasText(properties.excludedContexts())) {
            modCluster.setExcludedContexts(properties.excludedContexts());
        }
        modCluster.setSessionDrainingStrategy(properties.sessionDrainingStrategy());
        return modCluster;
    }

    /**
     * The {@code server.xml} equivalent: put the listener on the {@code Server}, give the {@code Engine}
     * the {@code jvmRoute} that identifies this node, and set the background-processor delay that paces
     * {@code STATUS}.
     */
    @Bean
    WebServerFactoryCustomizer<TomcatServletWebServerFactory> modClusterTomcatCustomizer(
            ModClusterListener modClusterListener,
            ModClusterProperties properties,
            @Value("${spring.application.name:creed-simple-metrics}") String applicationName) {

        return factory -> factory.addContextCustomizers(context -> {
            if (!(context.getParent() != null && context.getParent().getParent() instanceof Engine engine)) {
                log.error("mod_cluster listener NOT attached: the Tomcat context has no Engine parent "
                        + "yet ({}). The node will not be registered.", context.getParent());
                return;
            }
            Server server = engine.getService() != null ? engine.getService().getServer() : null;
            if (server == null) {
                log.error("mod_cluster listener NOT attached: no Server on the Engine's Service. "
                        + "The node will not be registered.");
                return;
            }

            NodeIdentity identity = NodeIdentity.resolve(properties, applicationName, factory.getPort());
            if (factory.getPort() > 0 || StringUtils.hasText(properties.node().jvmRoute())) {
                engine.setJvmRoute(identity.jvmRoute());
            } else {
                // server.port=0: the port is unknown here, so "<app>-<port>" would resolve to "-0".
                // Leave it unset and let mod_cluster's JvmRouteFactory generate one.
                log.warn("mod_cluster: server.port=0 and creed.mod-cluster.node.jvm-route is blank — "
                        + "letting mod_cluster generate the JVMRoute instead of deriving it.");
            }

            int delaySeconds = Math.max(1, (int) properties.statusInterval().toSeconds());
            engine.setBackgroundProcessorDelay(delaySeconds);
            server.addLifecycleListener(modClusterListener);
            log.info("mod_cluster listener attached to the Tomcat Server (JVMRoute={}, STATUS every {}s "
                    + "× org.jboss.modcluster.container.catalina.status-frequency)",
                    engine.getJvmRoute(), delaySeconds);
        });
    }

    @Bean
    ModClusterListenerStatusReporter modClusterListenerStatusReporter(
            ModClusterListener modClusterListener,
            ModClusterProperties properties,
            @Value("${spring.application.name:creed-simple-metrics}") String applicationName) {
        return new ModClusterListenerStatusReporter(modClusterListener, properties, applicationName);
    }

    /**
     * MCMP over TLS. Once {@code ssl} is on, the library's {@code JSSESocketFactory} loads the keystore
     * unconditionally (default {@code ~/.keystore}, JKS) and throws {@code IllegalStateException} when it
     * is missing — from inside the Tomcat lifecycle, where it reads as "mod_cluster did nothing". So the
     * stores are always set, resolved to absolute paths (a relative one would be resolved against
     * {@code catalina.base}, a temp directory for embedded Tomcat), and a missing file is reported here,
     * by name, at startup.
     */
    private static void configureTls(ModClusterListener modCluster, ModClusterProperties.Ssl ssl) {
        String keyStore = absolute(ssl.keyStore());
        String trustStore = absolute(ssl.trustStore());
        for (String[] store : new String[][] {{"key-store", keyStore}, {"trust-store", trustStore}}) {
            if (store[1] == null) {
                log.error("mod_cluster: manager-scheme is https but creed.mod-cluster.ssl.{} is not set — "
                        + "MCMP over TLS cannot start and this node will not be registered.", store[0]);
            } else if (!Files.isReadable(Path.of(store[1]))) {
                log.error("mod_cluster: creed.mod-cluster.ssl.{} '{}' is not a readable file — MCMP over "
                        + "TLS cannot start and this node will not be registered.", store[0], store[1]);
            }
        }
        if (keyStore != null) {
            modCluster.setSslKeyStore(keyStore);
        }
        modCluster.setSslKeyStorePassword(ssl.keyStorePassword());
        modCluster.setSslKeyStoreType(ssl.keyStoreType());
        if (StringUtils.hasText(ssl.keyAlias())) {
            modCluster.setSslKeyAlias(ssl.keyAlias());
        }
        if (trustStore != null) {
            modCluster.setSslTrustStore(trustStore);
        }
        modCluster.setSslTrustStorePassword(ssl.trustStorePassword());
        modCluster.setSslTrustStoreType(ssl.keyStoreType());
        modCluster.setSslProtocol(ssl.protocol());
        log.info("mod_cluster: MCMP over TLS — client identity {}, trusting {}", keyStore, trustStore);
    }

    private static String absolute(String path) {
        return StringUtils.hasText(path) ? Path.of(path.trim()).toAbsolutePath().normalize().toString() : null;
    }

    /**
     * Turns the {@code proxies} entries into the {@link ProxyConfiguration}s the listener wants — one
     * {@link ProxyConfigurationImpl} per resolved address, with no local bind address (the OS picks it).
     *
     * <p><strong>Why not {@code setProxyList(String)}.</strong> That setter resolves every host through
     * DNS <em>at configuration time</em> and throws {@code IllegalArgumentException} on the first name
     * it cannot resolve — which, from a {@code @Bean} method, is a failed context: a typo'd or
     * not-yet-registered proxy hostname would stop the whole application from starting. That directly
     * contradicts this feature's contract, where {@code fail-fast} is opt-in and off by default. So the
     * addresses are built here, where an unresolvable one can be reported and skipped instead.
     *
     * <p>Unresolvable entries are skipped rather than passed through: {@code InetSocketAddress} keeps
     * them as "unresolved" with a {@code null} address, which mod_cluster's MCMP handler is not written
     * to receive.
     */
    private static Collection<ProxyConfiguration> parseProxies(ModClusterProperties properties) {
        List<ProxyConfiguration> proxies = new ArrayList<>();
        for (String entry : properties.proxies()) {
            String hostPort = stripScheme(entry);
            int colon = hostPort.lastIndexOf(':');
            if (colon <= 0 || colon == hostPort.length() - 1) {
                log.error("mod_cluster: ignoring proxy '{}' — expected host:port", entry);
                continue;
            }
            int port;
            try {
                port = Integer.parseInt(hostPort.substring(colon + 1).trim());
            } catch (NumberFormatException ex) {
                log.error("mod_cluster: ignoring proxy '{}' — port is not a number", entry);
                continue;
            }
            InetSocketAddress address = new InetSocketAddress(hostPort.substring(0, colon).trim(), port);
            if (address.isUnresolved()) {
                log.error("mod_cluster: ignoring proxy '{}' — its host cannot be resolved right now. "
                        + "The node will NOT be registered with it.", entry);
                continue;
            }
            proxies.add(new ProxyConfigurationImpl(address));
        }
        if (proxies.isEmpty() && !properties.advertise()) {
            log.error("mod_cluster: no usable proxy address and advertise is off — this node will not "
                    + "be registered anywhere.");
        }
        return proxies;
    }

    private static String stripScheme(String proxy) {
        int scheme = proxy.indexOf("://");
        return scheme < 0 ? proxy.trim() : proxy.substring(scheme + 3).trim();
    }
}
