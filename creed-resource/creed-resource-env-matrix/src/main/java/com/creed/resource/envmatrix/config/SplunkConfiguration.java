package com.creed.resource.envmatrix.config;

import com.creed.resource.envmatrix.service.splunk.RestClientSplunkLoginClient;
import com.creed.resource.envmatrix.service.splunk.SplunkLoginClient;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.ssl.SslBundles;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.client.RestClient;

import javax.net.ssl.SSLContext;
import java.time.Clock;

/**
 * Wiring for the Splunk session broker: TOTP gate + login client.
 *
 * <p>One client either way: {@code env-matrix.splunk.enabled} decides inside it whether Splunk is
 * called or a mock value returned. The page reads {@link SplunkLoginClient#mode()} so a mocked
 * cookie is labelled as such.
 */
@Configuration
@EnableConfigurationProperties({TotpProperties.class, SplunkProperties.class})
@Slf4j
public class SplunkConfiguration {

    /** A bean so tests can pin time; TOTP is nothing but a function of the clock. */
    @Bean
    @ConditionalOnMissingBean
    Clock clock() {
        return Clock.systemUTC();
    }

    @Bean
    SplunkLoginClient splunkLoginClient(SplunkProperties properties, RestClient.Builder restClientBuilder,
                                        ObjectProvider<SslBundles> sslBundles) {
        log.info("splunk {}", properties);
        SSLContext sslContext = null;
        if (properties.enabled() && !properties.sslBundle().isBlank()) {
            sslContext = sslBundles.getObject().getBundle(properties.sslBundle()).createSslContext();
        }
        return new RestClientSplunkLoginClient(properties, restClientBuilder, sslContext);
    }
}
