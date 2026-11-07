package com.creed.parent.config;

import com.creed.parent.client.ArtifactoryClient;
import com.creed.parent.client.ConfluencePageClient;
import com.creed.parent.client.FirebaseClient;
import lombok.extern.slf4j.Slf4j;
import org.apache.hc.client5.http.auth.AuthSchemeFactory;
import org.apache.hc.client5.http.auth.AuthScope;
import org.apache.hc.client5.http.auth.NTCredentials;
import org.apache.hc.client5.http.auth.StandardAuthScheme;
import org.apache.hc.client5.http.config.ConnectionConfig;
import org.apache.hc.client5.http.config.RequestConfig;
import org.apache.hc.client5.http.impl.DefaultConnectionKeepAliveStrategy;
import org.apache.hc.client5.http.impl.auth.BasicCredentialsProvider;
import org.apache.hc.client5.http.impl.auth.BasicSchemeFactory;
import org.apache.hc.client5.http.impl.auth.BearerSchemeFactory;
import org.apache.hc.client5.http.impl.auth.DigestSchemeFactory;
import org.apache.hc.client5.http.impl.auth.NTLMSchemeFactory;
import org.apache.hc.client5.http.impl.auth.SPNegoSchemeFactory;
import org.apache.hc.client5.http.impl.classic.CloseableHttpClient;
import org.apache.hc.client5.http.impl.classic.HttpClientBuilder;
import org.apache.hc.client5.http.impl.classic.HttpClients;
import org.apache.hc.client5.http.impl.io.PoolingHttpClientConnectionManager;
import org.apache.hc.client5.http.impl.io.PoolingHttpClientConnectionManagerBuilder;
import org.apache.hc.client5.http.ssl.ClientTlsStrategyBuilder;
import org.apache.hc.client5.http.ssl.NoopHostnameVerifier;
import org.apache.hc.client5.http.ssl.TlsSocketStrategy;
import org.apache.hc.client5.http.ssl.TrustAllStrategy;
import org.apache.hc.core5.http.Header;
import org.apache.hc.core5.http.HeaderElements;
import org.apache.hc.core5.http.HttpHost;
import org.apache.hc.core5.http.config.RegistryBuilder;
import org.apache.hc.core5.http.message.BasicHeader;
import org.apache.hc.core5.ssl.SSLContexts;
import org.apache.hc.core5.util.TimeValue;
import org.apache.hc.core5.util.Timeout;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.http.HttpHeaders;
import org.springframework.http.client.HttpComponentsClientHttpRequestFactory;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.client.support.RestTemplateAdapter;
import org.springframework.web.service.invoker.HttpServiceProxyFactory;

import javax.net.ssl.SSLContext;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.security.KeyManagementException;
import java.security.KeyStoreException;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.Arrays;
import java.util.List;


@Configuration
@Slf4j
public class RestConfiguration {
    @Bean
    public CloseableHttpClient closeableHttpClient() throws NoSuchAlgorithmException, KeyManagementException, KeyStoreException {
        log.info("--- Initializing firebaseOAuth2HttpClient for firebase endpoint ---");
        SSLContext sslContext = SSLContexts.custom()
                .loadTrustMaterial(TrustAllStrategy.INSTANCE)
                // .loadKeyMaterial(keyStore, keyPassword.toCharArray())
                // .loadTrustMaterial(trustStore, TrustAllStrategy.INSTANCE)
                .build();
        TlsSocketStrategy tlsStrategy = (TlsSocketStrategy) ClientTlsStrategyBuilder.create()
                .setSslContext(sslContext)
                // .setTlsVersions(TLS.V_1_3)
                .setHostnameVerifier(NoopHostnameVerifier.INSTANCE)
                .buildClassic();
        final PoolingHttpClientConnectionManager connManager = PoolingHttpClientConnectionManagerBuilder.create()
                .setTlsSocketStrategy(tlsStrategy)
                .build();
        // connManager.setDefaultSocketConfig(SocketConfig.custom()
        //         .setTcpNoDelay(true)
        //         .setSoTimeout(Timeout.ofMinutes(3))//4️⃣
        //         .build());
        // Validate connections after 10 sec of inactivity
        connManager.setDefaultConnectionConfig(ConnectionConfig.custom()
                .setConnectTimeout(Timeout.ofMilliseconds(10000)) //与服务端建立连接超时时间
                .setSocketTimeout(Timeout.ofMinutes(3)) //3️⃣ 设置套接字超时时间，即等待响应数据的超时时间。
                .setValidateAfterInactivity(TimeValue.ofMinutes(2)) // 连接验证间隔时间。当一个连接在池中空闲时间超过此值时，连接将被验证是否可用
                .setTimeToLive(TimeValue.ofHours(1)) //定义连接可以保持活动状态或执行请求的总时间跨度。
                .build());


        // Use TLS v1.3 only
        // 仅使用 TLS v1.3
        // connManager.setDefaultTlsConfig(TlsConfig.custom() //用于配置 TLS 相关的参数
        //         .setHandshakeTimeout(Timeout.ofSeconds(30)) //设置 TLS 握手超时时间
        //         .setSupportedProtocols(TLS.V_1_3) //设置 TLS 握手超时时间
        //         .build());

        // Configure total max or per route limits for persistent connections
        // that can be kept in the pool or leased by the connection manager.
        // 为可以保留在池中或由连接管理器租用的持久连接配置总的最大或每条路由限制。
        connManager.setMaxTotal(60);
        connManager.setDefaultMaxPerRoute(20);
        // Create global request configuration
        // 创建全局请求配置
        // final RequestConfig defaultRequestConfig = RequestConfig.custom()
        //         .setConnectionRequestTimeout(Timeout.ofMilliseconds(1000)) //connectionRequestTimeout
        //         .setConnectTimeout(Timeout.ofMilliseconds(1000)) //setConnectTimeout
        //         .setResponseTimeout(Timeout.ofMinutes(3)) // readTimeOut 第2️⃣优先级
        //         .build();
        // for solve the java.net.SocketException: Connection reset error due to the HTTP persistent connection,
        List<Header> headers = List.of(new BasicHeader(HttpHeaders.CONNECTION, HeaderElements.CLOSE));

        return HttpClients.custom()
                .setDefaultHeaders(headers)
                .setConnectionManager(connManager) //设置 HttpClient 的连接管理器
                // .setDefaultRequestConfig(defaultRequestConfig) //设置默认的请求配置
                .setKeepAliveStrategy(new DefaultConnectionKeepAliveStrategy())
                .build();

    }

    @Bean
    @Primary
    public RestTemplate restTemplate() throws NoSuchAlgorithmException, KeyStoreException, KeyManagementException {
        RestTemplate restTemplate = new RestTemplate();
        HttpComponentsClientHttpRequestFactory httpRequestFactory = new HttpComponentsClientHttpRequestFactory();
        httpRequestFactory.setHttpClient(closeableHttpClient());
        httpRequestFactory.setConnectionRequestTimeout(2000);
        httpRequestFactory.setReadTimeout(Duration.ofMinutes(3)); // 1️⃣此处配置第一优先级2️⃣3️⃣4️⃣
//        httpRequestFactory.setConnectTimeout(3000);
        restTemplate.setRequestFactory(httpRequestFactory);
        return restTemplate;
    }

    @Bean
    public RestTemplate proxyRestTemplate() throws NoSuchAlgorithmException, KeyStoreException, KeyManagementException, UnknownHostException {
        SSLContext sslContext = SSLContexts.custom()
                .loadTrustMaterial(TrustAllStrategy.INSTANCE)
                .build();
        // for validate the host
        ClientTlsStrategyBuilder builder = ClientTlsStrategyBuilder.create()
                .setSslContext(sslContext)
                // .setTlsVersions(TLS.V_1_3, TLS.V_1_2)
                .setHostnameVerifier(NoopHostnameVerifier.INSTANCE);

        final PoolingHttpClientConnectionManager connManager = PoolingHttpClientConnectionManagerBuilder.create()
                .setTlsSocketStrategy(builder.buildClassic())
                .build();
        RequestConfig requestConfig = RequestConfig.custom()
                .setConnectionRequestTimeout(Timeout.ofMilliseconds(30_000))
                .setResponseTimeout(Timeout.ofMilliseconds(60_000))
                .setAuthenticationEnabled(true)
                .setProxyPreferredAuthSchemes(Arrays.asList(
                        StandardAuthScheme.SPNEGO,
                        StandardAuthScheme.NTLM
                ))
                .build();

        HttpClientBuilder httpClientBuilder = HttpClients.custom()
                .setConnectionManager(connManager)
                .setKeepAliveStrategy(DefaultConnectionKeepAliveStrategy.INSTANCE)
                .setDefaultRequestConfig(requestConfig);
        HttpHost proxy = new HttpHost("host", 8080);
        httpClientBuilder.setProxy(proxy);

        NTCredentials target = new NTCredentials("", "".toCharArray(), InetAddress.getLocalHost().getHostName(), "NTSGPDOM");
        AuthScope any = new AuthScope(null, -1);
        BasicCredentialsProvider credProv = new BasicCredentialsProvider();
        credProv.setCredentials(any, target);
        httpClientBuilder.setDefaultCredentialsProvider(credProv);
        //3️⃣
        RegistryBuilder<AuthSchemeFactory> schemeFactoryRegistry = RegistryBuilder.<AuthSchemeFactory>create()
                .register(StandardAuthScheme.BEARER, BearerSchemeFactory.INSTANCE)
                .register(StandardAuthScheme.BASIC, BasicSchemeFactory.INSTANCE)
                .register(StandardAuthScheme.DIGEST, DigestSchemeFactory.INSTANCE)
                .register(StandardAuthScheme.NTLM, NTLMSchemeFactory.INSTANCE)
                .register(StandardAuthScheme.SPNEGO, SPNegoSchemeFactory.DEFAULT);
        httpClientBuilder.setDefaultAuthSchemeRegistry(schemeFactoryRegistry.build());

        HttpComponentsClientHttpRequestFactory httpRequestFactory = new HttpComponentsClientHttpRequestFactory(httpClientBuilder.build());

        return new RestTemplate(httpRequestFactory);
    }

    @Bean
    public FirebaseClient firebaseClient(@Qualifier("proxyRestTemplate") RestTemplate restTemplate) {
        RestTemplateAdapter adapter = RestTemplateAdapter.create(restTemplate);
        HttpServiceProxyFactory factory = HttpServiceProxyFactory.builderFor(adapter).build();
        return factory.createClient(FirebaseClient.class);
    }

    @Bean
    public ArtifactoryClient artifactoryClient(RestTemplate restTemplate) {
        RestTemplateAdapter adapter = RestTemplateAdapter.create(restTemplate);
        HttpServiceProxyFactory factory = HttpServiceProxyFactory.builderFor(adapter).build();
        return factory.createClient(ArtifactoryClient.class);
    }

    @Bean
    public ConfluencePageClient confluencePageClient(RestTemplate restTemplate) {
        RestTemplateAdapter adapter = RestTemplateAdapter.create(restTemplate);
        HttpServiceProxyFactory factory = HttpServiceProxyFactory.builderFor(adapter).build();
        return factory.createClient(ConfluencePageClient.class);
    }


}
