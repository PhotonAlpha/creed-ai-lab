package com.creed.simple.lb;

import lombok.extern.slf4j.Slf4j;
import org.apache.hc.client5.http.HttpRoute;
import org.apache.hc.client5.http.impl.DefaultSchemePortResolver;
import org.apache.hc.client5.http.impl.routing.DefaultRoutePlanner;
import org.apache.hc.client5.http.routing.HttpRoutePlanner;
import org.apache.hc.core5.http.Header;
import org.apache.hc.core5.http.HttpException;
import org.apache.hc.core5.http.HttpHost;
import org.apache.hc.core5.http.HttpRequest;
import org.apache.hc.core5.http.protocol.HttpContext;
import org.springframework.cloud.client.ServiceInstance;
import org.springframework.cloud.client.discovery.DiscoveryClient;
import org.springframework.cloud.client.loadbalancer.DefaultRequest;
import org.springframework.cloud.client.loadbalancer.LoadBalancerClient;
import org.springframework.cloud.client.loadbalancer.RequestData;
import org.springframework.cloud.client.loadbalancer.RequestDataContext;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.HashMap;

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
 * <p><strong>Request-aware selection.</strong> {@code InternalHttpClient} calls the three-arg
 * {@link #determineRoute(HttpHost, HttpRequest, HttpContext)} overload, so the outgoing request — with
 * its headers and cookies — is available at selection time. It is adapted into the same
 * {@link RequestDataContext} that {@code BlockingLoadBalancerClient.execute(...)} builds for the
 * {@code @LoadBalanced} RestClient path, and passed to {@link LoadBalancerClient#choose(String, org.springframework.cloud.client.loadbalancer.Request)}.
 * That is what lets request-scoped suppliers work here — {@code StickyMetadataServiceInstanceListSupplier}
 * reads the {@code stickyId} cookie straight off this context, with no thread-bound state to keep in
 * sync across Camel's route/aggregation pools. The two-arg overload (used by {@code RedirectExec}) has
 * no request, so it degrades to a context-free {@code choose(serviceId)}.
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
 * <li>Unlike {@code execute(...)}, {@code choose(...)} still skips the {@code LoadBalancerLifecycle}
 * callbacks (no {@code loadbalancer.requests.*} metrics for this path) and the
 * {@code spring.cloud.loadbalancer.hint.*} properties — the context is built with the {@code default}
 * hint.</li>
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
        return determineRoute(target, null, context);
    }

    @Override
    public HttpRoute determineRoute(HttpHost target, HttpRequest request, HttpContext context) throws HttpException {
        String serviceId = target.getHostName();
        if (!discoveryClient.getServices().contains(serviceId)) {
            return directPlanner.determineRoute(target, context);
        }
        ServiceInstance instance = request == null
                ? loadBalancer.choose(serviceId)
                : loadBalancer.choose(serviceId, new DefaultRequest<>(new RequestDataContext(requestData(request))));
        if (instance == null) {
            // every registered instance failed the health check (or none are registered)
            throw new HttpException("No alive instances available for service '" + serviceId + "'");
        }
        HttpHost resolved = new HttpHost(instance.isSecure() ? "https" : "http",
                instance.getHost(), instance.getPort());
        log.info("[LB-ROUTE] {} -> {} (instance={})", serviceId, resolved, instance);
        // the secure flag decides whether TLS is layered on the connection — HttpRoute(HttpHost) alone
        // defaults it to false, which would send https traffic in plaintext
        return new HttpRoute(resolved, null, "https".equalsIgnoreCase(resolved.getSchemeName()));
    }

    /** Adapts the outgoing hc5 request into the {@link RequestData} shape the LB supplier chain reads. */
    static RequestData requestData(HttpRequest request) {
        HttpHeaders headers = new HttpHeaders();
        for (Header header : request.getHeaders()) {
            headers.add(header.getName(), header.getValue());
        }
        return new RequestData(HttpMethod.valueOf(request.getMethod()), uriOf(request), headers,
                cookiesOf(request), new HashMap<>());
    }

    private static URI uriOf(HttpRequest request) {
        try {
            return request.getUri();
        } catch (URISyntaxException ex) {
            // the request line is whatever camel-http built; never fail selection over an unparsable URI
            log.debug("[LB-ROUTE] unparsable request URI, selecting without one", ex);
            return null;
        }
    }

    /**
     * Splits every {@code Cookie} header into name/value pairs. Spring's own
     * {@code RequestData(HttpRequest)} constructor splits the raw header on {@code =} alone, so it only
     * ever sees the first cookie of a multi-cookie header — parse it properly here instead.
     */
    static MultiValueMap<String, String> cookiesOf(HttpRequest request) {
        MultiValueMap<String, String> cookies = new LinkedMultiValueMap<>();
        for (Header header : request.getHeaders(HttpHeaders.COOKIE)) {
            for (String pair : header.getValue().split(";")) {
                int separator = pair.indexOf('=');
                if (separator > 0) {
                    cookies.add(pair.substring(0, separator).trim(), pair.substring(separator + 1).trim());
                }
            }
        }
        return cookies;
    }
}
