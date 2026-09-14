package com.creed.simple.modcluster;

import org.jboss.modcluster.container.tomcat.ModClusterListener;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The property → {@link ModClusterListener} mapping. Constructing the listener starts nothing (the work
 * begins when Tomcat's Server fires {@code AFTER_INIT}), so this needs no container and no network.
 */
class ModClusterListenerConfigurationTest {

    private static ModClusterProperties properties(String managerScheme, ModClusterProperties.Node node) {
        return new ModClusterProperties(
                true, false,
                List.of("127.0.0.1:6666", "https://localhost:6667", "no-such-host.invalid:6668", "broken"),
                managerScheme, Duration.ofSeconds(10), Duration.ofSeconds(10),
                false, "", "", "", "localhost:/private", "NEVER", Duration.ofSeconds(10),
                node,
                new ModClusterProperties.Balancer("mycluster", true, true, false, 7, 3));
    }

    private static ModClusterProperties.Node node(String host, int port, int smax) {
        return new ModClusterProperties.Node(host, port, "", "east", List.of(), 80,
                true, 10, 10, smax, 60, 15);
    }

    private ModClusterListener listener(ModClusterProperties properties) {
        return new ModClusterListenerConfiguration().modClusterListener(properties);
    }

    @Test
    void mapsTheSharedProxyAndBalancerPropertiesOntoTheListener() {
        ModClusterListener listener = listener(properties("http", node("10.1.2.3", 8096, -1)));

        // Assert on the addresses themselves, not on getProxyList(): that getter only echoes back a
        // string passed to setProxyList(String), and returns null for proxies set as addresses.
        // Scheme is stripped (TLS to the proxy is a separate flag), and entries that cannot become an
        // address — unresolvable host, malformed entry — are skipped instead of failing the context.
        assertThat(listener.getProxies())
                .extracting(address -> address.getAddress().getHostAddress() + ":" + address.getPort())
                // order is not preserved — mod_cluster keeps the proxies in a set
                .containsExactlyInAnyOrder("127.0.0.1:6666", "127.0.0.1:6667");
        assertThat(listener.getBalancer()).isEqualTo("mycluster");
        assertThat(listener.getStickySession()).isTrue();
        assertThat(listener.getStickySessionRemove()).isTrue();
        assertThat(listener.getStickySessionForce()).isFalse();
        assertThat(listener.getMaxAttempts()).isEqualTo(3);
        // MCMP's WaitWorker is called workerTimeout on the Java side.
        assertThat(listener.getWorkerTimeout()).isEqualTo(7);
        // ...and MCMP's Domain is the "load balancing group".
        assertThat(listener.getLoadBalancingGroup()).isEqualTo("east");
        assertThat(listener.getPing()).isEqualTo(10);
        assertThat(listener.getTtl()).isEqualTo(60);
        assertThat(listener.getNodeTimeout()).isEqualTo(15);
        assertThat(listener.getInitialLoad()).isEqualTo(80);
        // mod_cluster parses the value into host -> context set and re-renders it without the slash.
        assertThat(listener.getExcludedContexts()).isEqualTo("localhost:private");
    }

    @Test
    void advertisesTheConfiguredAddressBackToTheProxy() {
        ModClusterListener listener = listener(properties("http", node("10.1.2.3", 8096, -1)));

        // externalConnector* is what the proxy dials; connectorAddress/Port would instead SELECT which
        // local connector to advertise.
        assertThat(listener.getExternalConnectorAddress()).isEqualTo("10.1.2.3");
        assertThat(listener.getExternalConnectorPort()).isEqualTo(8096);
        assertThat(listener.getConnectorAddress()).isNull();
        assertThat(listener.getConnectorPort()).isNull();
    }

    @Test
    void leavesTheAddressToTheContainerWhenNothingIsConfigured() {
        ModClusterListener listener = listener(properties("http", node("", 0, -1)));

        assertThat(listener.getExternalConnectorAddress()).isNull();
        assertThat(listener.getExternalConnectorPort()).isNull();
    }

    @Test
    void httpsManagerSchemeTurnsIntoTheSslFlag() {
        assertThat(listener(properties("https", node("", 0, -1))).isSsl()).isTrue();
        assertThat(listener(properties("http", node("", 0, -1))).isSsl()).isFalse();
    }

    @Test
    void negativeSmaxIsLeftToTheProxyDefault() {
        // mod_cluster uses the same -1 sentinel we do: its own default is -1 and the MCMP handler then
        // omits Smax from CONFIG. So "don't set it" and "set -1" coincide here — only a real value
        // must make it through.
        assertThat(listener(properties("http", node("", 0, -1))).getSmax()).isEqualTo(-1);
        assertThat(listener(properties("http", node("", 0, 4))).getSmax()).isEqualTo(4);
    }
}
