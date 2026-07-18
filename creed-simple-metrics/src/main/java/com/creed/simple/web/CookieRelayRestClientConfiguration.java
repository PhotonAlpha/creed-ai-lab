package com.creed.simple.web;

import com.creed.simple.config.CamelLoadBalancerAuditExecHandler;
import io.micrometer.observation.ObservationRegistry;
import org.apache.hc.client5.http.config.RequestConfig;
import org.apache.hc.client5.http.impl.classic.CloseableHttpClient;
import org.apache.hc.client5.http.impl.classic.HttpClients;
import org.apache.hc.client5.http.impl.io.PoolingHttpClientConnectionManager;
import org.apache.hc.core5.util.Timeout;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.cloud.client.loadbalancer.LoadBalanced;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.ClientHttpRequestFactory;
import org.springframework.http.client.HttpComponentsClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

import java.time.Duration;

/**
 * Dedicated {@link RestClient} for the {@code POST /camel/api/cookie-relay} demo
 * (see {@link com.creed.simple.route.CookieRelayProcessor}), which reproduces the
 * {@code $Path="/"; $Domain=...} Cookie-header corruption on the wire log.
 *
 * <p>Why not reuse {@code clusterRestClient}: its HttpClient keeps the default <b>cookie
 * management</b>, so the JSESSIONID from {@code POST /session} would land in HttpClient's own
 * cookie store and be re-sent automatically as a second, clean {@code Cookie} header on the next
 * request — masking exactly the corruption the demo exists to show. This client calls
 * {@code disableCookieManagement()} so the only {@code Cookie} header on the wire is the one the
 * processor builds by hand.
 *
 * <p>It shares {@code clusterHttpConnectionManager} (both clients mark the pool shared; Spring owns
 * its lifecycle) and is {@code @LoadBalanced}, so {@code https://<service-id>/...} URLs resolve the
 * same way as the business client's.
 */
@Configuration(proxyBeanMethods = false)
public class CookieRelayRestClientConfiguration {

    @Bean
    ClientHttpRequestFactory cookieRelayRequestFactory(
            @Qualifier("clusterHttpConnectionManager") PoolingHttpClientConnectionManager clusterHttpConnectionManager,
            @Value("${creed.partner.http.connection-request-timeout:3s}") Duration connectionRequestTimeout,
            @Value("${creed.partner.http.response-timeout:10s}") Duration responseTimeout) {
        RequestConfig requestConfig = RequestConfig.custom()
                .setConnectionRequestTimeout(Timeout.ofMilliseconds(connectionRequestTimeout.toMillis()))
                .setResponseTimeout(Timeout.ofMilliseconds(responseTimeout.toMillis()))
                .build();
        CloseableHttpClient httpClient = HttpClients.custom()
                .setConnectionManager(clusterHttpConnectionManager)
                .setConnectionManagerShared(true)
                .setDefaultRequestConfig(requestConfig)
                // The relay forwards the Cookie header BY HAND — that is the whole point of the demo.
                .disableCookieManagement()
                // Same downstream audit block as the camel-http component (headers/cookies/bodies);
                // the wire here carries the hand-built Cookie header this demo is about.
                .addExecInterceptorLast("lbAudit", new CamelLoadBalancerAuditExecHandler())
                .build();
        return new HttpComponentsClientHttpRequestFactory(httpClient);
    }

    @Bean
    @LoadBalanced
    RestClient.Builder cookieRelayRestClientBuilder(
            @Qualifier("cookieRelayRequestFactory") ClientHttpRequestFactory cookieRelayRequestFactory,
            ObservationRegistry observationRegistry) {
        return RestClient.builder()
                .requestFactory(cookieRelayRequestFactory)
                .observationRegistry(observationRegistry);
    }

    @Bean
    RestClient cookieRelayRestClient(
            @Qualifier("cookieRelayRestClientBuilder") RestClient.Builder cookieRelayRestClientBuilder) {
        return cookieRelayRestClientBuilder.build();
    }
}
