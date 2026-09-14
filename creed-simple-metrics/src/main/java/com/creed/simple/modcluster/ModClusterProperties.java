package com.creed.simple.modcluster;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

import java.time.Duration;
import java.util.List;

/**
 * Type-safe binding for the {@code mod_cluster} node registration under {@code creed.mod-cluster.*}
 * (see the block in {@code application.yml} for the environment-variable overrides).
 *
 * <p>These map onto the upstream {@code ModClusterListener}'s configuration surface — see
 * {@link ModClusterListenerConfiguration}, which is where each one is translated. Two groups of
 * settings deliberately have <strong>no</strong> property here, because with this integration the
 * container owns them:
 *
 * <ul>
 *   <li><strong>contexts</strong> — mod_cluster registers what Tomcat actually has deployed (here: the
 *       ROOT context, not the {@code /camel} servlet mapping). Use {@link #excludedContexts()} to keep
 *       one out;</li>
 *   <li><strong>the sticky-session cookie/path</strong> — read off Tomcat's own session-cookie
 *       configuration; {@link #balancer()} only carries the policy flags.</li>
 * </ul>
 *
 * @param enabled        master switch — off by default, since a lab run has no httpd in front of it
 * @param failFast       when {@code true}, a startup where no proxy ended up holding this node aborts
 *                       the application instead of only logging the failed banner
 * @param proxies        the MCMP listeners, {@code host:port} (a full {@code scheme://host:port} is
 *                       accepted too; the scheme is ignored — see {@link #managerScheme()})
 * @param managerScheme  {@code http} (the usual — the MCMP VirtualHost is plain) or {@code https},
 *                       which turns into mod_cluster's "SSL to the proxy" flag
 * @param socketTimeout  how long an MCMP exchange with a proxy may take
 * @param statusInterval the {@code STATUS} heartbeat period. It is mapped onto the Tomcat Engine's
 *                       background-processor delay — the real period is that delay multiplied by the
 *                       {@code org.jboss.modcluster.container.catalina.status-frequency} system
 *                       property (default 1). Keep it well below {@code node.ttl}
 * @param advertise      let proxies announce themselves by multicast instead of (or on top of) the
 *                       static {@code proxies} list. Needs httpd's {@code ServerAdvertise On} AND
 *                       multicast reachability — when either is missing nothing happens and nothing is
 *                       reported, which is why it defaults to off
 * @param advertiseInterface   the NIC to listen for advertisements on (e.g. {@code lo0} locally)
 * @param advertiseSecurityKey must match httpd's {@code AdvertiseSecurityKey} when it sets one
 * @param loadMetricClass      the {@code LoadMetric} implementation driving the dynamic load factor;
 *                             blank = mod_cluster's default (busy connectors)
 * @param excludedContexts     contexts never registered, {@code host:/path} comma-separated
 * @param sessionDrainingStrategy {@code DEFAULT} (drain unless the context is distributable),
 *                             {@code ALWAYS} or {@code NEVER}
 * @param startupTimeout       how long the startup banner waits for the proxies to report holding this
 *                             node before printing what it has
 */
@ConfigurationProperties("creed.mod-cluster")
public record ModClusterProperties(
        @DefaultValue("false") boolean enabled,
        @DefaultValue("false") boolean failFast,
        List<String> proxies,
        @DefaultValue("http") String managerScheme,
        @DefaultValue("10s") Duration socketTimeout,
        @DefaultValue("10s") Duration statusInterval,
        @DefaultValue("false") boolean advertise,
        @DefaultValue("") String advertiseInterface,
        @DefaultValue("") String advertiseSecurityKey,
        @DefaultValue("") String loadMetricClass,
        @DefaultValue("") String excludedContexts,
        @DefaultValue("DEFAULT") String sessionDrainingStrategy,
        @DefaultValue("10s") Duration startupTimeout,
        @DefaultValue Node node,
        @DefaultValue Balancer balancer) {

    public ModClusterProperties {
        // Normalise in the canonical constructor rather than with @DefaultValue: an empty @DefaultValue
        // is only defined for nested value objects, not for collections.
        proxies = proxies == null ? List.of() : List.copyOf(proxies);
    }

    /**
     * This process, as the proxy will see it.
     *
     * <p>Note that {@code host}/{@code port} do not decide where the proxy sends traffic on their own:
     * mod_cluster advertises the Tomcat <em>connector</em>, and these only override what it publishes
     * (the {@code externalConnector*} pair) for the cases where the two must differ — containers, NAT,
     * multi-NIC hosts. Leave them blank and the connector's own address/port is used.
     *
     * @param host        address the proxy should dial back; blank = whatever the connector reports
     * @param port        port the proxy should dial back; {@code 0} = the connector's own
     * @param jvmRoute    the node's identity for the whole cluster, set on the Tomcat {@code Engine};
     *                    blank = {@code <app-name>-<port>}. It must match the route suffix appended to
     *                    the session id for sticky sessions to land back here
     * @param domain      optional failover domain (mod_cluster calls it the load-balancing group)
     * @param aliases     virtual-host aliases shown on the startup banner when the proxy reports none;
     *                    the proxy learns the real ones from the container
     * @param load        the INITIAL load factor; from the first {@code STATUS} on, the
     *                    {@link #loadMetricClass()} metric computes it (1..100)
     * @param flushPackets whether the proxy should flush each packet to the node
     * @param flushWait   ms the proxy waits before flushing, when {@code flushPackets} is on
     * @param ping        seconds the proxy waits for the node's {@code CPING/CPONG} answer
     * @param smax        soft maximum idle connections the proxy keeps to the node ({@code -1} = leave
     *                    the proxy's own default)
     * @param ttl         seconds the proxy keeps an idle connection above {@code smax}
     * @param timeout     seconds the proxy waits for a free connection to the node ({@code 0} = no wait)
     */
    public record Node(
            @DefaultValue("") String host,
            @DefaultValue("0") int port,
            @DefaultValue("") String jvmRoute,
            @DefaultValue("") String domain,
            List<String> aliases,
            @DefaultValue("100") int load,
            @DefaultValue("true") boolean flushPackets,
            @DefaultValue("10") int flushWait,
            @DefaultValue("10") int ping,
            @DefaultValue("-1") int smax,
            @DefaultValue("60") int ttl,
            @DefaultValue("0") int timeout) {

        public Node {
            aliases = aliases == null ? List.of() : List.copyOf(aliases);
        }
    }

    /**
     * The balancer this node joins, and the sticky-session policy it asks for. All of it is advisory:
     * the proxy stores what the first node of a balancer sent and later nodes must agree, so keep these
     * identical across the instances of one cluster.
     *
     * @param name                the balancer name; must match httpd's {@code ManagerBalancerName}
     * @param stickySession       route a session back to the node that created it
     * @param stickySessionRemove strip the session id when the sticky node is gone
     * @param stickySessionForce  return an error instead of failing a sticky request over to another node
     * @param waitWorker          seconds to wait for a busy worker to free up ({@code 0} = do not wait);
     *                            MCMP's {@code WaitWorker}, spelled {@code workerTimeout} in the Java API
     * @param maxAttempts         how many times the proxy retries a failed request on another node
     */
    public record Balancer(
            @DefaultValue("mycluster") String name,
            @DefaultValue("true") boolean stickySession,
            @DefaultValue("false") boolean stickySessionRemove,
            @DefaultValue("true") boolean stickySessionForce,
            @DefaultValue("0") int waitWorker,
            @DefaultValue("1") int maxAttempts) {
    }
}
