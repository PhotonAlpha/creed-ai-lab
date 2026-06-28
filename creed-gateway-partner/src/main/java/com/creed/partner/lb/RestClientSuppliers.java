package com.creed.partner.lb;

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
    /**
     *
     * @param bundle
     * @param maxTotal
     * @param maxPerRoute
     * @param connectTimeout
     * @param socketTimeout
     * @return
     *
     * 你发出请求  ──────►  对方开始回数据  ──►  数据一段段传回来  ──►  收完
     *               ↑                        ↑
     *           responseTimeout          socketTimeout
     *          （第188行：等"开口"）    （第173行：等"下一口"）
     *
     *   一句话区分
     *
     *   - 第 188 行 responseTimeout：我把请求发出去了，对方多久内得开始回我。比如设 5 秒，5 秒还没听到任何动静（第一个字节都没回），就报超时。
     *   - 第 173 行 socketTimeout：对方已经在回数据了，但数据是一段一段来的。两段数据之间最多能卡多久。比如设 10 秒，对方传了一半突然不传了，卡超过 10 秒就报超时。
     *
     *   为什么看起来"一样"
     *
     *   因为它们单位都是毫秒、写法长得像（都是 Timeout.ofMilliseconds(...)），而且在很多简单场景下，如果对方一直不回数据，两个超时都可能触发，所以容易误以为重复。但本质上：
     *
     *   - 一个是连接这条"水管"本身的默认读超时（173 行，连接管理器级别）。
     *   - 一个是单次请求等响应的超时（188 行，请求级别），且会在请求期间临时盖过前者。
     *
     *   所以不是配了两遍同一个东西，而是给"等开头"和"防中途卡死"分别设了值。
     */
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
