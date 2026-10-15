package com.creed.resource.veracode.config;

import com.creed.resource.veracode.client.confluence.ConfluenceClient;
import com.creed.resource.veracode.client.jira.JiraClient;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpHeaders;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.support.RestClientAdapter;
import org.springframework.web.service.invoker.HttpServiceProxyFactory;

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
 */
@Configuration(proxyBeanMethods = false)
@Slf4j
public class RemoteClientsConfiguration {

    @Bean
    ConfluenceClient confluenceClient(RestClient.Builder builder, ConfluenceProperties props) {
        log.info("Confluence client -> {} ({})", props.baseUrl(), props.auth());
        return confluenceClient(configure(builder, props.baseUrl(), props.auth(),
                props.connectTimeout(), props.readTimeout()).build());
    }

    @Bean
    JiraClient jiraClient(RestClient.Builder builder, JiraProperties props) {
        log.info("Jira client -> {} project {} ({})", props.baseUrl(), props.projectKey(), props.auth());
        return jiraClient(configure(builder, props.baseUrl(), props.auth(),
                props.connectTimeout(), props.readTimeout()).build());
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

    private static RestClient.Builder configure(RestClient.Builder builder, String baseUrl, RemoteAuth auth,
                                                Duration connectTimeout, Duration readTimeout) {
        HttpClient httpClient = HttpClient.newBuilder()
                .connectTimeout(connectTimeout)
                .followRedirects(HttpClient.Redirect.NEVER)
                .build();
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
