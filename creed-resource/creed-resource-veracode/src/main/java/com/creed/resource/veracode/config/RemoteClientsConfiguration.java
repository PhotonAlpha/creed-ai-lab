package com.creed.resource.veracode.config;

import com.creed.resource.veracode.client.confluence.ConfluenceClient;
import com.creed.resource.veracode.client.jira.JiraClient;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ssl.SslBundles;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpHeaders;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.util.StringUtils;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.support.RestClientAdapter;
import org.springframework.web.service.invoker.HttpServiceProxyFactory;

import javax.net.ssl.SSLContext;
import java.net.http.HttpClient;
import java.time.Duration;

/**
 * The two {@code @HttpExchange} proxies. Each gets its own {@link RestClient}: base URL, credentials and
 * timeouts differ per system, and a shared client would send the Jira token to Confluence.
 *
 * <p>The {@code RestClient.Builder} is Boot's (prototype-scoped, so each injection is a fresh copy):
 * it carries the context's message converters and the {@code http.client.requests} observation.
 *
 * <p>Redirects are not followed. A wrong base URL on Data Center answers 302 to the login page, and a
 * followed redirect turns that into "200, HTML where JSON was expected" - a confusing parse error
 * instead of the status that says what happened.
 *
 * <p>TLS: the request factory is built here, so Boot's HTTP-client SSL properties never reach it. A
 * client trusts a private CA only through its own {@code ssl-bundle}; with the {@code ssl} profile that
 * is the same bundle the server presents, because the mocks are this process calling itself over https.
 */
@Configuration(proxyBeanMethods = false)
@Slf4j
public class RemoteClientsConfiguration {

    @Bean
    ConfluenceClient confluenceClient(RestClient.Builder builder, ConfluenceProperties props,
                                      SslBundles sslBundles) {
        log.info("Confluence client -> {} ({}, ssl bundle '{}')", props.baseUrl(), props.auth(), props.sslBundle());
        return confluenceClient(configure(builder, props.baseUrl(), props.auth(),
                props.connectTimeout(), props.readTimeout(), sslContext(sslBundles, props.sslBundle())).build());
    }

    @Bean
    JiraClient jiraClient(RestClient.Builder builder, JiraProperties props, SslBundles sslBundles) {
        log.info("Jira client -> {} project {} ({}, ssl bundle '{}')",
                props.baseUrl(), props.projectKey(), props.auth(), props.sslBundle());
        return jiraClient(configure(builder, props.baseUrl(), props.auth(),
                props.connectTimeout(), props.readTimeout(), sslContext(sslBundles, props.sslBundle())).build());
    }

    /** Public so a test can put the same proxy over a different transport. */
    public static ConfluenceClient confluenceClient(RestClient restClient) {
        return proxy(restClient, ConfluenceClient.class);
    }

    public static JiraClient jiraClient(RestClient restClient) {
        return proxy(restClient, JiraClient.class);
    }

    private static <T> T proxy(RestClient restClient, Class<T> type) {
        return HttpServiceProxyFactory.builderFor(RestClientAdapter.create(restClient))
                .build()
                .createClient(type);
    }

    /** {@code null} = the JDK default, which trusts only the public CAs in the runtime's cacerts. */
    private static SSLContext sslContext(SslBundles sslBundles, String bundleName) {
        return StringUtils.hasText(bundleName) ? sslBundles.getBundle(bundleName).createSslContext() : null;
    }

    private static RestClient.Builder configure(RestClient.Builder builder, String baseUrl, RemoteAuth auth,
                                                Duration connectTimeout, Duration readTimeout,
                                                SSLContext sslContext) {
        HttpClient.Builder httpClientBuilder = HttpClient.newBuilder()
                .connectTimeout(connectTimeout)
                .followRedirects(HttpClient.Redirect.NEVER);
        if (sslContext != null) {
            httpClientBuilder.sslContext(sslContext);
        }
        HttpClient httpClient = httpClientBuilder.build();
        JdkClientHttpRequestFactory requestFactory = new JdkClientHttpRequestFactory(httpClient);
        requestFactory.setReadTimeout(readTimeout);

        builder.baseUrl(baseUrl).requestFactory(requestFactory);
        String authorization = auth.headerValue();
        if (authorization != null) {
            builder.defaultHeader(HttpHeaders.AUTHORIZATION, authorization);
        }
        return builder;
    }
}
