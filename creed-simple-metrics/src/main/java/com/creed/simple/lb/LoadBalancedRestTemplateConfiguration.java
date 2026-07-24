package com.creed.simple.lb;

import io.micrometer.observation.ObservationRegistry;
import org.springframework.cloud.client.loadbalancer.LoadBalancerInterceptor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.ClientHttpRequestInterceptor;
import org.springframework.web.client.RestTemplate;

import java.util.List;

/**
 * {@link RestTemplate} equivalent of {@link LoadBalancedRestClientConfiguration}'s two clients
 * ({@code clusterRestClient} / {@code healthCheckRestClient}). Reuses the very same beans — the two mTLS
 * {@link ManagedHttpClientPool}s ({@code clusterPool} / {@code healthCheckPool}, whose request factories
 * are already wrapped in {@code BufferingClientHttpRequestFactory}) and the
 * {@link LoadBalancerAuditInterceptor} — so only the client objects differ.
 *
 * <p>Three things that are <em>not</em> a 1:1 copy of the RestClient version, because RestTemplate works
 * differently:
 * <ol>
 *   <li><strong>Load balancing &amp; interceptor order.</strong> The RestClient version relies on the
 *       {@code @LoadBalanced} post-processor and adds the audit interceptor in the build step so it lands
 *       <em>inner</em> than the LB interceptor (sees the resolved {@code host:port}). For RestTemplate the
 *       {@code @LoadBalanced} customizer <em>appends</em> its interceptor via a
 *       {@code SmartInitializingSingleton}, so you cannot reliably insert another interceptor after it.
 *       Instead we skip {@code @LoadBalanced} and wire the interceptor list ourselves in execution order:
 *       {@code [loadBalancerInterceptor, auditInterceptor]} — index 0 is outermost, so the LB interceptor
 *       rewrites {@code https://<service-id>} → {@code host:port} first and the audit interceptor, running
 *       inside it, sees the resolved instance. (RestTemplate has no {@code @Order} on interceptors; list
 *       order is the only control — see creed-platform notes.)</li>
 *   <li><strong>Observation / {@code http.client.requests}.</strong> A hand-built {@code new RestTemplate}
 *       is not instrumented by Boot (only RestTemplates from the auto-configured {@code RestTemplateBuilder}
 *       are), so we call {@link RestTemplate#setObservationRegistry} explicitly — mirroring the
 *       {@code .observationRegistry(...)} call on the RestClient builders.</li>
 *   <li><strong>No {@code @LoadBalancerClients} here.</strong> The default LB client configuration is
 *       already registered once on {@link LoadBalancedRestClientConfiguration}. If you delete that class
 *       and keep only this one, move {@code @LoadBalancerClients(defaultConfiguration =
 *       PartnerLoadBalancerConfiguration.class)} onto this class.</li>
 * </ol>
 *
 * <p>To actually use these, point {@code RemoteClusterProcessor} at a {@code RestTemplate} instead of a
 * {@code RestClient} (e.g. {@code restTemplate.getForObject(clusterUrl, JsonNode.class)}).
 */
@Configuration(proxyBeanMethods = false)
public class LoadBalancedRestTemplateConfiguration {

    /**
     * Load-balanced + audited business client. Interceptor list is ordered explicitly so the audit
     * interceptor runs innermost (after the LB interceptor has resolved the instance {@code host:port}).
     */
    @Bean
    RestTemplate clusterRestTemplate(
            ManagedHttpClientPool clusterPool,
            LoadBalancerInterceptor loadBalancerInterceptor,
            LoadBalancerAuditInterceptor auditInterceptor,
            ObservationRegistry observationRegistry) {
        RestTemplate restTemplate = new RestTemplate(clusterPool.requestFactory());
        // index 0 = outermost: LB rewrites the service-id to host:port, THEN audit sees the resolved host.
        restTemplate.setInterceptors(List.<ClientHttpRequestInterceptor>of(loadBalancerInterceptor, auditInterceptor));
        // Emit `http.client.requests` (a bare new RestTemplate is otherwise uninstrumented).
        restTemplate.setObservationRegistry(observationRegistry);
        return restTemplate;
    }

    /** Plain (non-load-balanced) health-check client over its own mTLS pool. */
    @Bean
    RestTemplate healthCheckRestTemplate(
            ManagedHttpClientPool healthCheckPool,
            ObservationRegistry observationRegistry) {
        RestTemplate restTemplate = new RestTemplate(healthCheckPool.requestFactory());
        restTemplate.setObservationRegistry(observationRegistry);
        return restTemplate;
    }
}
