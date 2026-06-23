package com.creed.simple.web;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.ssl.SslBundles;
import org.springframework.boot.web.embedded.tomcat.TomcatServletWebServerFactory;
import org.springframework.boot.web.server.Ssl;
import org.springframework.boot.web.server.WebServerFactoryCustomizer;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;

/**
 * Enables the partner gateway's embedded Tomcat HTTPS listener <em>programmatically</em> via
 * {@link WebServerFactoryCustomizer}&lt;{@link TomcatServletWebServerFactory}&gt; rather than through
 * the {@code server.ssl.*} properties — matching the other gateways/resource servers.
 *
 * <p>The TLS material comes from the Spring Boot SSL Bundle named by {@code creed.https.bundle}
 * ({@code creed-partner-server} by default, defined in {@code application.yml}); we point the factory
 * at that bundle by name and hand it the {@link SslBundles} registry so the certificate/key are
 * resolved (and hot-reloaded) by the framework.
 *
 * <p>Gated by {@code creed.https.enabled} (default {@code true}).
 */
@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(prefix = "creed.https", name = "enabled", havingValue = "true", matchIfMissing = true)
public class TomcatHttpsConfiguration
        implements WebServerFactoryCustomizer<TomcatServletWebServerFactory>, Ordered {

    private final SslBundles sslBundles;
    private final String bundle;

    public TomcatHttpsConfiguration(
            SslBundles sslBundles,
            @org.springframework.beans.factory.annotation.Value("${creed.https.bundle:creed-partner-server}") String bundle) {
        this.sslBundles = sslBundles;
        this.bundle = bundle;
    }

    @Override
    public void customize(TomcatServletWebServerFactory factory) {
        Ssl ssl = new Ssl();
        ssl.setEnabled(true);
        ssl.setBundle(bundle);
        ssl.setClientAuth(Ssl.ClientAuth.NONE);
        factory.setSsl(ssl);
        // Hand Tomcat the bundle registry so it can resolve (and reload) the keystore by name.
        factory.setSslBundles(sslBundles);
    }

    /**
     * Run after Boot's own {@code ServletWebServerFactoryCustomizer} (which copies {@code server.ssl});
     * since we deliberately leave {@code server.ssl} unset, running last guarantees our programmatic
     * {@link Ssl} is the one that survives.
     */
    @Override
    public int getOrder() {
        return Ordered.LOWEST_PRECEDENCE;
    }
}
