package com.creed.simple.modcluster;

import org.jboss.modcluster.ModClusterServiceMBean;
import org.junit.jupiter.api.Test;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.support.GenericApplicationContext;

import java.net.InetSocketAddress;
import java.time.Duration;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalStateException;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Turning the listener's {@code INFO} dumps into the same pass/fail statement the MCMP provider prints.
 * The dumps below are the real {@code mod_cluster} INFO shape.
 */
class ModClusterListenerStatusReporterTest {

    private static final NodeIdentity NODE = new NodeIdentity(
            "creed-simple-metrics-8096", "10.1.2.3", 8096, "https", List.of("localhost"));

    private static final String INFO_WITH_NODE = """
            Node: [1],Name: creed-simple-metrics-8096,Balancer: mycluster,LBGroup: ,Host: 10.1.2.3,\
            Port: 8096,Type: https,Flushpackets: On,Flushwait: 10,Ping: 10,Smax: 26,Ttl: 60,\
            Elected: 0,Read: 0,Transferred: 0,Connected: 0,Load: 100
            Vhost: [1:1:1], Alias: localhost
            Context: [1:1:1], Context: /, Status: ENABLED
            """;

    private static final String INFO_WITH_OTHER_NODE = """
            Node: [1],Name: some-other-node,Balancer: mycluster,LBGroup: ,Host: 10.9.9.9,Port: 8080,\
            Type: http,Load: 100
            """;

    private static ModClusterProperties properties(List<String> proxies) {
        return new ModClusterProperties(
                true, false, proxies, "http", Duration.ofSeconds(10), Duration.ofSeconds(10),
                // startup-timeout 0: snapshot() is exercised directly, no polling in these tests
                false, "", "", "", "", "DEFAULT", Duration.ZERO,
                new ModClusterProperties.Node("10.1.2.3", 8096, "", "", List.of("localhost"),
                        100, true, 10, 10, -1, 60, 0),
                new ModClusterProperties.Balancer("mycluster", true, false, true, 0, 1));
    }

    private ModClusterListenerStatusReporter reporter(ModClusterServiceMBean service, List<String> proxies) {
        return new ModClusterListenerStatusReporter(service, properties(proxies), "creed-simple-metrics");
    }

    @Test
    void aProxyWhoseInfoNamesThisJvmRouteIsRegistered() {
        ModClusterServiceMBean service = mock(ModClusterServiceMBean.class);
        when(service.getProxyInfo()).thenReturn(
                Map.of(new InetSocketAddress("127.0.0.1", 6666), INFO_WITH_NODE));

        List<ProxyState> states = reporter(service, List.of("127.0.0.1:6666")).snapshot(NODE).states();

        assertThat(states).singleElement().satisfies(state -> {
            assertThat(state.registered()).isTrue();
            assertThat(state.detail()).contains("contexts enabled=1");
        });
    }

    @Test
    void aProxyHoldingOnlyOtherNodesIsNotRegistered() {
        ModClusterServiceMBean service = mock(ModClusterServiceMBean.class);
        when(service.getProxyInfo()).thenReturn(
                Map.of(new InetSocketAddress("127.0.0.1", 6666), INFO_WITH_OTHER_NODE));

        List<ProxyState> states = reporter(service, List.of("127.0.0.1:6666")).snapshot(NODE).states();

        assertThat(states).singleElement().satisfies(state -> {
            assertThat(state.registered()).isFalse();
            assertThat(state.detail()).contains("does not hold JVMRoute=creed-simple-metrics-8096");
        });
    }

    @Test
    void aConfiguredProxyThatWasNeverContactedStillAppearsOnTheBanner() {
        ModClusterServiceMBean service = mock(ModClusterServiceMBean.class);
        when(service.getProxyInfo()).thenReturn(Map.of());

        // The scheme-carrying entry must not produce a second, duplicate row.
        List<ProxyState> states =
                reporter(service, List.of("127.0.0.1:6666", "http://127.0.0.1:6667")).snapshot(NODE).states();

        assertThat(states).hasSize(2).allSatisfy(state -> {
            assertThat(state.registered()).isFalse();
            assertThat(state.detail()).contains("no INFO response");
        });
    }

    @Test
    void anAdvertiseDiscoveredProxyIsReportedEvenThoughItIsNotConfigured() {
        ModClusterServiceMBean service = mock(ModClusterServiceMBean.class);
        when(service.getProxyInfo()).thenReturn(
                Map.of(new InetSocketAddress("127.0.0.1", 7777), INFO_WITH_NODE));

        List<ProxyState> states = reporter(service, List.of()).snapshot(NODE).states();

        assertThat(states).singleElement().satisfies(state -> {
            assertThat(state.proxy()).isEqualTo("127.0.0.1:7777");
            assertThat(state.registered()).isTrue();
        });
    }

    @Test
    void theNodeLineIsRewrittenFromWhatTheProxyActuallyHolds() {
        ModClusterServiceMBean service = mock(ModClusterServiceMBean.class);
        when(service.getProxyInfo()).thenReturn(
                Map.of(new InetSocketAddress("127.0.0.1", 6666), INFO_WITH_NODE));

        // NODE says 10.1.2.3 (what we computed locally); the proxy says 10.1.2.3:8096 in the dump —
        // use a dump with a different host to prove the proxy's value wins.
        NodeIdentity guessed = new NodeIdentity("creed-simple-metrics-8096", "192.168.5.9", 8096,
                "", List.of("guessed-host"));

        NodeIdentity reported = reporter(service, List.of("127.0.0.1:6666")).snapshot(guessed).identity();

        assertThat(reported.host()).isEqualTo("10.1.2.3");
        assertThat(reported.port()).isEqualTo(8096);
        assertThat(reported.type()).isEqualTo("https");
        assertThat(reported.aliases()).containsExactly("localhost");
    }

    @Test
    void failFastAbortsTheStartupWhenNoProxyHoldsTheNode() {
        ModClusterServiceMBean service = mock(ModClusterServiceMBean.class);
        when(service.getProxyInfo()).thenReturn(Map.of());
        ModClusterProperties base = properties(List.of("127.0.0.1:6666"));
        ModClusterProperties failFast = new ModClusterProperties(
                true, true, base.proxies(), base.managerScheme(), base.socketTimeout(),
                base.statusInterval(), false, "", "", "", "", "DEFAULT", Duration.ZERO,
                base.node(), base.balancer());

        ModClusterListenerStatusReporter reporter =
                new ModClusterListenerStatusReporter(service, failFast, "creed-simple-metrics");

        assertThatIllegalStateException()
                .isThrownBy(() -> reporter.onApplicationEvent(readyEvent()))
                .withMessageContaining("fail-fast");
    }

    @Test
    void withoutFailFastAFailedRegistrationOnlyLogs() {
        ModClusterServiceMBean service = mock(ModClusterServiceMBean.class);
        when(service.getProxyInfo()).thenReturn(Map.of());

        // Default fail-fast=false: the banner is printed at ERROR, but the application still starts.
        reporter(service, List.of("127.0.0.1:6666")).onApplicationEvent(readyEvent());
    }

    private static ApplicationReadyEvent readyEvent() {
        return new ApplicationReadyEvent(new SpringApplication(), new String[0],
                new GenericApplicationContext(), Duration.ZERO);
    }

    @Test
    void aFailingInfoQueryDoesNotBreakTheBanner() {
        ModClusterServiceMBean service = mock(ModClusterServiceMBean.class);
        when(service.getProxyInfo()).thenThrow(new IllegalStateException("proxy handler not started"));

        List<ProxyState> states = reporter(service, List.of("127.0.0.1:6666")).snapshot(NODE).states();

        assertThat(states).singleElement()
                .extracting(ProxyState::registered).isEqualTo(false);
    }
}
