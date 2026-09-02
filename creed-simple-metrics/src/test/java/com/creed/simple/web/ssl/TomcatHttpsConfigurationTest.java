package com.creed.simple.web.ssl;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.boot.ssl.SslBundles;
import org.springframework.boot.tomcat.servlet.TomcatServletWebServerFactory;
import org.springframework.boot.web.server.Ssl;
import org.springframework.core.Ordered;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

/**
 * Unit tests for {@link TomcatHttpsConfiguration}: it programmatically enables the HTTPS listener from
 * the named SSL bundle (client-auth NONE), hands Tomcat the bundle registry, and runs last
 * ({@link Ordered#LOWEST_PRECEDENCE}) so its {@link Ssl} survives Boot's own customizer.
 */
class TomcatHttpsConfigurationTest {

    private final SslBundles sslBundles = mock(SslBundles.class);

    @Test
    void customizeEnablesHttpsFromTheNamedBundle() {
        TomcatHttpsConfiguration config = new TomcatHttpsConfiguration(sslBundles, "creed-partner-server");
        TomcatServletWebServerFactory factory = mock(TomcatServletWebServerFactory.class);

        config.customize(factory);

        ArgumentCaptor<Ssl> ssl = ArgumentCaptor.forClass(Ssl.class);
        verify(factory).setSsl(ssl.capture());
        assertThat(ssl.getValue().isEnabled()).isTrue();
        assertThat(ssl.getValue().getBundle()).isEqualTo("creed-partner-server");
        assertThat(ssl.getValue().getClientAuth()).isEqualTo(Ssl.ClientAuth.NONE);
        verify(factory).setSslBundles(sslBundles);
    }

    @Test
    void runsAtLowestPrecedenceSoItWinsOverBootsCustomizer() {
        TomcatHttpsConfiguration config = new TomcatHttpsConfiguration(sslBundles, "creed-partner-server");
        assertThat(config.getOrder()).isEqualTo(Ordered.LOWEST_PRECEDENCE);
    }
}
