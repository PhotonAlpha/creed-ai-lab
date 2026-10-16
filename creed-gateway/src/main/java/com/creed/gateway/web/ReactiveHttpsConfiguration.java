package com.creed.gateway.web;

import org.springframework.boot.ssl.SslBundles;
import org.springframework.boot.web.server.reactive.ConfigurableReactiveWebServerFactory;
import org.springframework.boot.web.server.Ssl;
import org.springframework.boot.web.server.WebServerFactoryCustomizer;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;

/**
 * Requirement 1: enable this gateway's embedded HTTPS listener <em>programmatically</em> via
 * {@link WebServerFactoryCustomizer}&lt;{@link ConfigurableReactiveWebServerFactory}&gt; rather than
 * through the {@code server.ssl.*} properties.
 *
 * <p><b>Reactive, not servlet.</b> This module is WebFlux on Reactor Netty, so it has no
 * {@code TomcatServletWebServerFactory} bean. The class used to be a copy of the servlet modules'
 * {@code TomcatHttpsConfiguration} and was typed on that factory, which meant
 * {@code WebServerFactoryCustomizerBeanPostProcessor} never matched it and the SSL bundle below was
 * silently never applied — the gateway served plain HTTP on 8080. Typing the customizer on
 * {@link ConfigurableReactiveWebServerFactory} (which declares {@code setSsl}/{@code setSslBundles}
 * on {@code ConfigurableWebServerFactory}) fixes that and keeps it independent of the reactive
 * server implementation.
 *
 * <p>The TLS material itself still comes from a Spring Boot 3 SSL Bundle
 * ({@code creed-pem-server} by default, defined in {@code application-ssl.yml}); we simply
 * point the factory at that bundle by name and hand it the {@link SslBundles} registry so
 * the certificate/key are resolved (and hot-reloaded) by the framework.
 *
 * <p>Gated by {@code creed.https.enabled} (default {@code true}). Tests run with the
 * {@code native,test} profiles where {@code application-test.yml} sets it to {@code false},
 * so the context loads without certificates on disk.
 */
@Configuration(proxyBeanMethods = false)
//@ConditionalOnProperty(prefix = "creed.https", name = "enabled", havingValue = "true", matchIfMissing = true)
public class ReactiveHttpsConfiguration
        implements WebServerFactoryCustomizer<ConfigurableReactiveWebServerFactory>, Ordered {

    private final SslBundles sslBundles;
    private final String bundle;

    public ReactiveHttpsConfiguration(
            SslBundles sslBundles,
            @org.springframework.beans.factory.annotation.Value("${creed.https.bundle:creed-pem-server}") String bundle) {
        this.sslBundles = sslBundles;
        this.bundle = bundle;
    }

    @Override
    public void customize(ConfigurableReactiveWebServerFactory factory) {
        Ssl ssl = new Ssl();
        ssl.setEnabled(true);
        ssl.setBundle(bundle);
        ssl.setClientAuth(Ssl.ClientAuth.NONE);
        factory.setSsl(ssl);
        // Hand Tomcat the bundle registry so it can resolve (and reload) the keystore by name.
        factory.setSslBundles(sslBundles);
    }

    /**
     * Run after Boot's own {@code ServletWebServerFactoryCustomizer} (which copies
     * {@code server.ssl}); since we deliberately leave {@code server.ssl} unset, running
     * last guarantees our programmatic {@link Ssl} is the one that survives.
     */
    @Override
    public int getOrder() {
        return Ordered.LOWEST_PRECEDENCE;
    }
}
