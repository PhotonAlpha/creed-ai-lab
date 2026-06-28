package com.creed.simple.lb;

import lombok.experimental.UtilityClass;
import org.apache.hc.client5.http.config.ConnectionConfig;
import org.apache.hc.client5.http.config.RequestConfig;
import org.apache.hc.client5.http.impl.classic.CloseableHttpClient;
import org.apache.hc.client5.http.impl.classic.HttpClients;
import org.apache.hc.client5.http.impl.io.PoolingHttpClientConnectionManager;
import org.apache.hc.client5.http.impl.io.PoolingHttpClientConnectionManagerBuilder;
import org.apache.hc.client5.http.ssl.DefaultClientTlsStrategy;
import org.apache.hc.core5.util.Timeout;
import org.springframework.boot.ssl.SslBundle;
import org.springframework.http.client.HttpComponentsClientHttpRequestFactory;

import java.time.Duration;

@UtilityClass
public final class RestClientSuppliers {

    public static PoolingHttpClientConnectionManager connectionManagerFrom(
            SslBundle bundle, int maxTotal, int maxPerRoute, Duration connectTimeout, Duration socketTimeout) {
        ConnectionConfig connectionConfig = ConnectionConfig.custom()
                .setConnectTimeout(Timeout.ofMilliseconds(connectTimeout.toMillis()))
                .setSocketTimeout(Timeout.ofMilliseconds(socketTimeout.toMillis()))
                .build();
        return PoolingHttpClientConnectionManagerBuilder.create()
                .setTlsSocketStrategy(new DefaultClientTlsStrategy(bundle.createSslContext()))
                .setMaxConnTotal(maxTotal)
                .setMaxConnPerRoute(maxPerRoute)
                .setDefaultConnectionConfig(connectionConfig)
                .build();
    }

    public static HttpComponentsClientHttpRequestFactory requestFactoryFrom(
            PoolingHttpClientConnectionManager connectionManager,
            Duration connectionRequestTimeout, Duration responseTimeout) {
        RequestConfig requestConfig = RequestConfig.custom()
                .setConnectionRequestTimeout(Timeout.ofMilliseconds(connectionRequestTimeout.toMillis()))
                .setResponseTimeout(Timeout.ofMilliseconds(responseTimeout.toMillis()))
                .build();
        CloseableHttpClient httpClient = HttpClients.custom()
                .setConnectionManager(connectionManager)
                // The pool is a Spring-managed bean (destroyMethod="close"); mark it shared so closing
                // the client does not also close the bean (Spring owns its lifecycle).
                .setConnectionManagerShared(true)
                .setDefaultRequestConfig(requestConfig)
                .build();
        return new HttpComponentsClientHttpRequestFactory(httpClient);
    }
}
