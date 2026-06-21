package com.creed.partner.web;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ssl.SslBundle;
import org.springframework.boot.ssl.SslBundles;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.BufferingClientHttpRequestFactory;
import org.springframework.http.client.ClientHttpRequestFactory;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

import java.net.http.HttpClient;
import java.time.Duration;

/**
 * Wires the HTTP clients the partner gateway uses to reach the resource servers:
 *
 * <ul>
 *   <li>{@code partnerRestClient} — the client the aggregator uses for the resource-server calls
 *       (requirement 1), carrying the {@link LoadBalancerAuditInterceptor} (requirement 3). The
 *       {@code lb://} service IDs are resolved to concrete instances explicitly in the controller via
 *       {@code LoadBalancerClient.choose(...)} (the health-checked supplier), then called here.</li>
 *   <li>{@code healthCheckRestClient} — a plain client (no audit) used by Spring Cloud LoadBalancer's
 *       blocking health checks (requirement 2).</li>
 * </ul>
 *
 * <p>Both share a JDK-{@link HttpClient} request factory whose TLS material comes from a local SSL
 * bundle, so the HTTPS resource servers' self-signed certificates (signed by the Creed CA) are
 * trusted. The factory is buffered so the audit interceptor can read the response body and still hand
 * it to the caller.
 *
 * <p>We resolve {@code lb://} explicitly rather than registering a load-balancer interceptor because
 * the {@code SimpleDiscoveryClient} reconstruct leaks the {@code lb} scheme into the JDK client
 * ({@code invalid URI scheme lb}) — the same reason the reactive gateway resolves instances by hand.
 */
@Configuration(proxyBeanMethods = false)
public class PartnerClientConfiguration {

    @Bean
    ClientHttpRequestFactory partnerClientHttpRequestFactory(
            SslBundles sslBundles,
            @Value("${creed.partner.client-bundle:creed-partner-server}") String bundleName) {
        SslBundle bundle = sslBundles.getBundle(bundleName);
        HttpClient jdkClient = HttpClient.newBuilder()
                .sslContext(bundle.createSslContext())
                .connectTimeout(Duration.ofSeconds(5))
                .build();
        return new BufferingClientHttpRequestFactory(new JdkClientHttpRequestFactory(jdkClient));
    }

    @Bean
    RestClient partnerRestClient(
            ClientHttpRequestFactory partnerClientHttpRequestFactory,
            LoadBalancerAuditInterceptor auditInterceptor) {
        return RestClient.builder()
                .requestFactory(partnerClientHttpRequestFactory)
                .requestInterceptor(auditInterceptor)
                .build();
    }

    @Bean
    RestClient healthCheckRestClient(ClientHttpRequestFactory partnerClientHttpRequestFactory) {
        return RestClient.builder().requestFactory(partnerClientHttpRequestFactory).build();
    }
}
