package com.creed.gateway.config;

import javax.net.ssl.SSLException;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ssl.SslBundle;
import org.springframework.boot.ssl.SslBundles;
import org.springframework.boot.ssl.SslManagerBundle;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.reactive.ReactorClientHttpConnector;
import org.springframework.web.reactive.function.client.WebClient;

import io.netty.handler.ssl.SslContext;
import io.netty.handler.ssl.SslContextBuilder;
import reactor.netty.http.client.HttpClient;

/**
 * Requirement 5: the gateway aggregates data from the resource servers over HTTPS, trusting their
 * self-signed certificates through the {@code creed-jks-client} SSL bundle that the config server
 * delivers (also via HTTPS).
 *
 * <p>This bean is a plain (non load-balanced) {@link WebClient} backed by a Reactor Netty client
 * whose TLS material comes from that bundle. The {@code lb://} service IDs are resolved to concrete
 * instance URLs <em>explicitly</em> in {@code AggregateController} via the Spring Cloud
 * {@link org.springframework.cloud.client.loadbalancer.reactive.ReactiveLoadBalancer.Factory}, so we
 * deliberately do <strong>not</strong> register a load-balancer {@code ExchangeFilterFunction} here:
 * with a custom Netty connector that filter does not reliably rewrite the request before it reaches
 * the connector, which leaks the {@code lb} scheme down to Reactor Netty
 * ({@code UriEndpointFactory: Invalid scheme [lb]}). Explicit resolution composes deterministically
 * with the SSL connector.
 */
@Configuration(proxyBeanMethods = false)
public class GatewayWebClientConfiguration {

    @Bean
    WebClient resourceWebClient(
            SslBundles sslBundles,
            @Value("${creed.webclient.ssl-bundle:creed-jks-client}") String bundleName) {
        SslBundle bundle = sslBundles.getBundle(bundleName);
        SslManagerBundle managers = bundle.getManagers();

        SslContext nettySslContext;
        try {
            nettySslContext = SslContextBuilder.forClient()
                    .keyManager(managers.getKeyManagerFactory())
                    .trustManager(managers.getTrustManagerFactory())
                    .build();
        } catch (SSLException ex) {
            throw new IllegalStateException(
                    "Failed to build Netty SslContext from SSL bundle '" + bundleName + "'", ex);
        }

        HttpClient httpClient = HttpClient.create().secure(spec -> spec.sslContext(nettySslContext));

        return WebClient.builder()
                .clientConnector(new ReactorClientHttpConnector(httpClient))
                .build();
    }
}
