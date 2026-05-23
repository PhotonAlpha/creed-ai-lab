package com.creed.config.client;

import java.time.Duration;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.http.client.ClientHttpRequestFactoryBuilder;
import org.springframework.boot.http.client.ClientHttpRequestFactorySettings;
import org.springframework.boot.ssl.SslBundle;
import org.springframework.boot.ssl.SslBundles;
import org.springframework.boot.web.client.RestTemplateBuilder;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.ClientHttpRequestFactory;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestTemplate;

/**
 * Spring Boot 3 SSL Bundles are looked up by name from {@link SslBundles} and applied to
 * blocking HTTP clients via the builders. Either the JKS bundle ("creed-jks-client") or
 * the PEM bundle ("creed-pem-client") defined in application.yml can be used here.
 */
@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(prefix = "creed.http-client", name = "enabled", havingValue = "true", matchIfMissing = true)
public class HttpClientsConfiguration {

    @Bean
    @ConfigurationProperties("creed.http-client")
    HttpClientProperties httpClientProperties() {
        return new HttpClientProperties();
    }

    @Bean
    RestTemplate mtlsRestTemplate(RestTemplateBuilder builder,
                                  SslBundles sslBundles,
                                  HttpClientProperties props) {
        SslBundle bundle = sslBundles.getBundle(props.getSslBundle());
        return builder
                .setSslBundle(bundle)
                .connectTimeout(props.getConnectTimeout())
                .readTimeout(props.getReadTimeout())
                .build();
    }

    @Bean
    RestClient mtlsRestClient(RestClient.Builder builder,
                              SslBundles sslBundles,
                              HttpClientProperties props) {
        SslBundle bundle = sslBundles.getBundle(props.getSslBundle());
        ClientHttpRequestFactorySettings settings = ClientHttpRequestFactorySettings.defaults()
                .withSslBundle(bundle)
                .withConnectTimeout(props.getConnectTimeout())
                .withReadTimeout(props.getReadTimeout());
        ClientHttpRequestFactory requestFactory = ClientHttpRequestFactoryBuilder.httpComponents().build(settings);
        return builder.requestFactory(requestFactory).build();
    }

    public static class HttpClientProperties {
        private String sslBundle = "creed-pem-client";
        private Duration connectTimeout = Duration.ofSeconds(5);
        private Duration readTimeout = Duration.ofSeconds(10);

        public String getSslBundle() {
            return sslBundle;
        }

        public void setSslBundle(String sslBundle) {
            this.sslBundle = sslBundle;
        }

        public Duration getConnectTimeout() {
            return connectTimeout;
        }

        public void setConnectTimeout(Duration connectTimeout) {
            this.connectTimeout = connectTimeout;
        }

        public Duration getReadTimeout() {
            return readTimeout;
        }

        public void setReadTimeout(Duration readTimeout) {
            this.readTimeout = readTimeout;
        }
    }
}
