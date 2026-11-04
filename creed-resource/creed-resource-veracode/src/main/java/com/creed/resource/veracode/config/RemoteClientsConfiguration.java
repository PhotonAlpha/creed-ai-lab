package com.creed.resource.veracode.config;

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

/**
 * The {@code @HttpExchange} Jira proxy over its own {@link RestClient}.
 *
 * <p>The {@code RestClient.Builder} is Boot's (prototype-scoped, so each injection is a fresh copy):
 * it carries the context's message converters and the {@code http.client.requests} observation.
 *
 * <p>Redirects are not followed. A wrong base URL on Data Center answers 302 to the login page, and a
 * followed redirect turns that into "200, HTML where JSON was expected" - a confusing parse error
 * instead of the status that says what happened.
 *
 * <p>TLS: certificates and hostnames are not verified ({@link TrustAllTls}) - asked for, so that a Jira
 * behind a private CA or a mismatched name works without trust material. The request factory is built
 * here, so Boot's HTTP-client SSL properties never reach it either way.
 */
@Configuration(proxyBeanMethods = false)
@Slf4j
public class RemoteClientsConfiguration {

    @Bean
    JiraClient jiraClient(RestClient.Builder builder, JiraProperties props) {
        log.info("Jira client -> {} project {} ({}, TLS not verified)", props.baseUrl(), props.projectKey(), props.auth());
        return jiraClient(configure(builder, props).build());
    }

    /** Public so a test can put the same proxy over a different transport. */
    public static JiraClient jiraClient(RestClient restClient) {
        return HttpServiceProxyFactory.builderFor(RestClientAdapter.create(restClient))
                .build()
                .createClient(JiraClient.class);
    }

    private static RestClient.Builder configure(RestClient.Builder builder, JiraProperties props) {
        HttpClient httpClient = HttpClient.newBuilder()
                .connectTimeout(props.connectTimeout())
                .followRedirects(HttpClient.Redirect.NEVER)
                .sslContext(TrustAllTls.context())
                .build();
        JdkClientHttpRequestFactory requestFactory = new JdkClientHttpRequestFactory(httpClient);
        requestFactory.setReadTimeout(props.readTimeout());

        builder.baseUrl(props.baseUrl()).requestFactory(requestFactory);
        String authorization = props.auth().headerValue();
        if (authorization != null) {
            builder.defaultHeader(HttpHeaders.AUTHORIZATION, authorization);
        }
        return builder;
    }
}
