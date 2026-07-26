package com.creed.resource.envmatrix.web;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.ssl.SslBundles;
import org.springframework.boot.web.embedded.tomcat.TomcatServletWebServerFactory;
import org.springframework.boot.web.server.Ssl;
import org.springframework.boot.web.server.WebServerFactoryCustomizer;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;

/**
 * The platform's programmatic HTTPS listener (see the sibling resource modules): TLS is configured
 * through a Spring Boot 3 SSL bundle named by {@code creed.https.bundle} instead of the
 * {@code server.ssl.*} properties.
 *
 * <p>Gated by {@code creed.https.enabled} (default {@code true}). The {@code dev} profile sets it to
 * {@code false} so the service serves plain HTTP on the port the Vite proxy targets — a self-signed
 * HTTPS origin would otherwise force every frontend dev to configure proxy TLS trust for no benefit.
 */
@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(prefix = "creed.https", name = "enabled", havingValue = "true", matchIfMissing = true)
public class TomcatHttpsConfiguration
        implements WebServerFactoryCustomizer<TomcatServletWebServerFactory>, Ordered {

    private final SslBundles sslBundles;
    private final String bundle;

    public TomcatHttpsConfiguration(SslBundles sslBundles,
                                    @Value("${creed.https.bundle:creed-env-matrix-server}") String bundle) {
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
