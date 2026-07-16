package com.creed.simple.lb;

import lombok.extern.slf4j.Slf4j;
import org.apache.hc.client5.http.HttpRoute;
import org.apache.hc.client5.http.impl.DefaultSchemePortResolver;
import org.apache.hc.client5.http.impl.routing.DefaultRoutePlanner;
import org.apache.hc.client5.http.routing.HttpRoutePlanner;
import org.apache.hc.core5.http.HttpException;
import org.apache.hc.core5.http.HttpHost;
import org.apache.hc.core5.http.protocol.HttpContext;
import org.springframework.cloud.client.ServiceInstance;
import org.springframework.cloud.client.discovery.DiscoveryClient;
import org.springframework.cloud.client.loadbalancer.LoadBalancerClient;

/**
 * HttpClient 5 {@link HttpRoutePlanner} that resolves logical service-ids through Spring Cloud
 * LoadBalancer — the camel-http replacement for the removed ServiceCall EIP /
 * {@code staticServiceDiscovery}.
 *
 * <p>Camel routes keep their unchanged {@code https://<service-id>/...} endpoint URIs (e.g.
 * {@code https://catalog-resource/api/catalog/items}). When the target host matches a service-id known
 * to the {@link DiscoveryClient} (the {@code SimpleDiscoveryClient} registry in {@code application.yml}),
 * the connection is routed to the instance picked by {@link LoadBalancerClient#choose} — which runs the
 * full per-service supplier chain from {@code PartnerLoadBalancerConfiguration} (discovery → logging
 * health check → caching), so only alive instances are round-robined. Any other host falls through to
 * HttpClient's stock {@link DefaultRoutePlanner} and is connected directly.
 *
 * <p>Notes:
 * <ul>
 * <li>{@code choose()} only reads the health-check supplier's cached alive list — no probe happens on
 * the request path, so blocking here (a Camel producer thread) is safe.</li>
 * <li>The TLS handshake verifies the <em>resolved</em> host ({@code localhost}), matching the Creed-CA
 * certificates — same behaviour as the {@code @LoadBalanced} RestClient. The {@code Host} header keeps
 * the logical service name.</li>
 * <li>HttpClient's built-in retry re-executes on the same route; instance failover needs a Camel-level
 * redelivery, which re-enters this planner and picks a fresh instance.</li>
 * <li>{@code DiscoveryClient.getServices()} is an in-memory map lookup for the simple registry; switch
 * to a locally cached set before pointing this at a remote registry (Eureka/Consul).</li>
 * </ul>
 */
@Slf4j
public class LoadBalancerRoutePlanner implements HttpRoutePlanner {

    private final LoadBalancerClient loadBalancer;
    private final DiscoveryClient discoveryClient;
    private final HttpRoutePlanner directPlanner = new DefaultRoutePlanner(DefaultSchemePortResolver.INSTANCE);

    public LoadBalancerRoutePlanner(LoadBalancerClient loadBalancer, DiscoveryClient discoveryClient) {
        this.loadBalancer = loadBalancer;
        this.discoveryClient = discoveryClient;
    }

    @Override
    public HttpRoute determineRoute(HttpHost target, HttpContext context) throws HttpException {
        String serviceId = target.getHostName();
        if (!discoveryClient.getServices().contains(serviceId)) {
            return directPlanner.determineRoute(target, context);
        }
        ServiceInstance instance = loadBalancer.choose(serviceId);
        if (instance == null) {
            // every registered instance failed the health check (or none are registered)
            throw new HttpException("No alive instances available for service '" + serviceId + "'");
        }
        HttpHost resolved = new HttpHost(instance.isSecure() ? "https" : "http",
                instance.getHost(), instance.getPort());
        log.debug("[LB-ROUTE] {} -> {} (instanceId={})", serviceId, resolved, instance.getInstanceId());
        // the secure flag decides whether TLS is layered on the connection — HttpRoute(HttpHost) alone
        // defaults it to false, which would send https traffic in plaintext
        return new HttpRoute(resolved, null, "https".equalsIgnoreCase(resolved.getSchemeName()));
    }
}
