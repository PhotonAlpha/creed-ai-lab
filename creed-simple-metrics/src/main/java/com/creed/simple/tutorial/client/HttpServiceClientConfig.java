package com.creed.simple.tutorial.client;

import com.creed.simple.lb.ManagedHttpClientPool;
import io.micrometer.observation.ObservationRegistry;
import org.apache.hc.client5.http.impl.classic.CloseableHttpClient;
import org.apache.hc.client5.http.impl.classic.HttpClients;
import org.springframework.beans.factory.config.ConfigurableBeanFactory;
import org.springframework.beans.factory.config.EmbeddedValueResolver;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.HttpComponentsClientHttpRequestFactory;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.support.RestClientAdapter;
import org.springframework.web.service.invoker.HttpServiceProxyFactory;
import org.zalando.logbook.Logbook;
import org.zalando.logbook.httpclient5.LogbookHttpExecHandler;

/**
 * Builds the {@code @HttpExchange} proxy for API-7.
 *
 * <p>Declared by hand on purpose, so the tutorial shows how the proxy is actually built:
 * <ul>
 *   <li>{@code embeddedValueResolver} - makes {@code ${...}} in {@code @HttpExchange} url and
 *       headers resolve against the environment; without it they are taken literally;</li>
 *   <li>{@link ArtisanHeaders} - the per-request header policy, an interceptor on this client's own
 *       RestClient;</li>
 *   <li>the HttpClient underneath - the module's shared mTLS pool ({@code clusterPool}), because the
 *       mock it calls is this very server on HTTPS, plus the same {@link LogbookHttpExecHandler} the
 *       camel-http component and the cookie-relay client use, so every outbound call is audited the
 *       same way;</li>
 *   <li>the {@link ObservationRegistry} - {@code http.client.requests} metrics and the W3C
 *       {@code traceparent} header.</li>
 * </ul>
 * Not {@code @LoadBalanced}: the base URL names a host, not a service id.
 */
@Configuration(proxyBeanMethods = false)
public class HttpServiceClientConfig {

    @Bean
    RestClient artisanRestClient(ManagedHttpClientPool clusterPool, Logbook logbook,
                                 ObservationRegistry observationRegistry) {
        CloseableHttpClient httpClient = HttpClients.custom()
                .setConnectionManager(clusterPool.connectionManager())
                // Spring owns the pool's lifecycle (ManagedHttpClientPool); closing this client must not close it
                .setConnectionManagerShared(true)
                .addExecInterceptorFirst("logbook", new LogbookHttpExecHandler(logbook))
                .build();
        return RestClient.builder()
                .requestFactory(new HttpComponentsClientHttpRequestFactory(httpClient))
                .requestInterceptor(new ArtisanHeaders())
                .observationRegistry(observationRegistry)
                .build();
    }

    /**
     * The bean name {@code artisanClient} is what the routes refer to in
     * {@code httpexchange:artisanClient/<method>}.
     */
    @Bean
    ArtisanClient artisanClient(RestClient artisanRestClient, ConfigurableBeanFactory beanFactory) {
        return HttpServiceProxyFactory
                .builderFor(RestClientAdapter.create(artisanRestClient))
                .embeddedValueResolver(new EmbeddedValueResolver(beanFactory))
                .build()
                .createClient(ArtisanClient.class);
    }
}
