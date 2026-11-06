package com.creed.resource.veracode.config;

import com.creed.resource.veracode.client.jira.JiraClient;
import lombok.extern.slf4j.Slf4j;
import org.apache.hc.client5.http.config.ConnectionConfig;
import org.apache.hc.client5.http.config.RequestConfig;
import org.apache.hc.client5.http.impl.classic.CloseableHttpClient;
import org.apache.hc.client5.http.impl.classic.HttpClients;
import org.apache.hc.client5.http.impl.io.PoolingHttpClientConnectionManager;
import org.apache.hc.client5.http.impl.io.PoolingHttpClientConnectionManagerBuilder;
import org.apache.hc.client5.http.ssl.ClientTlsStrategyBuilder;
import org.apache.hc.client5.http.ssl.HostnameVerificationPolicy;
import org.apache.hc.client5.http.ssl.NoopHostnameVerifier;
import org.apache.hc.core5.util.Timeout;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.HttpComponentsClientHttpRequestFactory;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.support.RestClientAdapter;
import org.springframework.web.service.invoker.HttpServiceProxyFactory;
import org.zalando.logbook.Logbook;
import org.zalando.logbook.httpclient5.LogbookHttpExecHandler;

import java.time.Duration;

/**
 * The {@code @HttpExchange} Jira proxy over its own {@link RestClient}, on Apache HttpClient 5.
 *
 * <p>The {@code RestClient.Builder} is Boot's (prototype-scoped, so each injection is a fresh copy):
 * it carries the context's message converters and the {@code http.client.requests} observation.
 *
 * <p>The pool is its own bean, closed by Spring; the client is told it does not own it
 * ({@code setConnectionManagerShared}).
 *
 * <p>Redirects are not followed. A wrong base URL on Data Center answers 302 to the login page, and a
 * followed redirect turns that into "200, HTML where JSON was expected" - a confusing parse error
 * instead of the status that says what happened.
 *
 * <p><b>No cookies.</b> One client serves every user's token, and Jira answers each call with a
 * {@code JSESSIONID}: kept, it would be sent with the next user's request - and a call without a token
 * would run as whoever came before.
 *
 * <p>Every Jira request and response is logged by Logbook ({@code logbook.*}, logger
 * {@code org.zalando.logbook.Logbook} at TRACE), from inside HttpClient's exec chain - so what is logged is what
 * went on the wire, headers the RestClient never saw included. {@code Authorization} carries the user's token
 * and is masked ({@code logbook.obfuscate.headers}).
 *
 * <p>TLS: certificates and hostnames are not verified ({@link TrustAllTls}) - asked for, so that a Jira
 * behind a private CA or a mismatched name works without trust material. Boot's HTTP-client SSL
 * properties never reach this client either way: it is built here.
 */
@Configuration(proxyBeanMethods = false)
@Slf4j
public class RemoteClientsConfiguration {

    @Bean(destroyMethod = "close")
    PoolingHttpClientConnectionManager jiraConnectionManager(JiraProperties props) {
        return connectionManager(props.connectTimeout(), props.maxConnections());
    }

    @Bean
    JiraClient jiraClient(RestClient.Builder builder, PoolingHttpClientConnectionManager jiraConnectionManager,
                          Logbook logbook, JiraProperties props) {
        log.info("Jira client -> {} project {} (each user's own token, Apache HttpClient 5, TLS not verified)",
                props.baseUrl(), props.projectKey());
        HttpComponentsClientHttpRequestFactory requestFactory =
                new HttpComponentsClientHttpRequestFactory(httpClient(jiraConnectionManager, props.readTimeout(), logbook));
        // no default Authorization: a call without the user's token is refused before it gets here (JiraToken)
        builder.baseUrl(props.baseUrl()).requestFactory(requestFactory);
        return jiraClient(builder.build());
    }

    /** Public so a test can put the same proxy over a different transport. */
    public static JiraClient jiraClient(RestClient restClient) {
        return HttpServiceProxyFactory.builderFor(RestClientAdapter.create(restClient))
                .build()
                .createClient(JiraClient.class);
    }

    static PoolingHttpClientConnectionManager connectionManager(Duration connectTimeout, int maxConnections) {
        PoolingHttpClientConnectionManager pool = PoolingHttpClientConnectionManagerBuilder.create()
                // CLIENT: the hostname is checked by the verifier below (a no-op), not by JSSE
                .setTlsSocketStrategy(ClientTlsStrategyBuilder.create()
                        .setSslContext(TrustAllTls.context())
                        .setHostVerificationPolicy(HostnameVerificationPolicy.CLIENT)
                        .setHostnameVerifier(NoopHostnameVerifier.INSTANCE)
                        .buildClassic())
                .setMaxConnTotal(maxConnections)
                .setMaxConnPerRoute(maxConnections)
                .build();
        // HttpClient 5 keeps the connect timeout on the pool; RequestConfig has the lease and response ones.
        pool.setDefaultConnectionConfig(ConnectionConfig.custom()
                .setConnectTimeout(Timeout.of(connectTimeout))
                .build());
        return pool;
    }

    static CloseableHttpClient httpClient(PoolingHttpClientConnectionManager pool, Duration readTimeout, Logbook logbook) {
        return HttpClients.custom()
                .setConnectionManager(pool)
                .setConnectionManagerShared(true)
                .setDefaultRequestConfig(RequestConfig.custom()
                        .setConnectionRequestTimeout(Timeout.of(readTimeout))
                        .setResponseTimeout(Timeout.of(readTimeout))
                        .build())
                .disableRedirectHandling()
                .disableCookieManagement()
                .addExecInterceptorFirst("logbook", new LogbookHttpExecHandler(logbook))
                .build();
    }
}
