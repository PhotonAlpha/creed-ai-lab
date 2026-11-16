package com.creed.resource.lbhealth.lb;

import com.creed.resource.lbhealth.config.LbHealthProperties;
import lombok.extern.slf4j.Slf4j;
import org.springframework.cloud.client.ServiceInstance;
import org.springframework.http.HttpStatus;
import org.springframework.http.client.ClientHttpRequestInterceptor;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.http.client.reactive.JdkClientHttpConnector;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.reactive.function.client.ExchangeFilterFunction;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.util.UriComponentsBuilder;
import reactor.core.publisher.Mono;

import java.net.URI;
import java.net.http.HttpClient;
import java.util.List;
import java.util.function.BiFunction;

/**
 * The probe clients. Neither is a Spring bean and neither is {@code @LoadBalanced}: a probe must go straight to
 * {@code instance.getUri()}. Through the load balancer, the instance host would be taken for a service id and
 * every probe would fail.
 *
 * <p>The two alive functions are copies of the lambdas in Spring Cloud 5.0.3's
 * {@code ServiceInstanceListSupplierBuilder} ({@code blockingHealthCheckServiceInstanceListSupplier(RestTemplate,..)}
 * / {@code healthCheckServiceInstanceListSupplier(WebClient,..)}). Only the debug path uses them, since it must
 * instantiate {@link ObservableHealthCheckServiceInstanceListSupplier} itself. They differ from the originals in
 * one way: with debug on, the exception the stock blocking lambda swallows ({@code catch (Exception ignored)}) is
 * logged.
 */
@Slf4j
public final class HealthCheckClients {

    private HealthCheckClients() {
    }

    /**
     * connect + read timeout ARE the probe timeout in blocking mode. Spring's own {@code timeout(interval)} never
     * fires there: the probe blocks inside {@code subscribe()}, before the timeout operator starts its timer.
     */
    public static RestTemplate restTemplate(LbHealthProperties.HealthCheck settings, boolean debug) {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(settings.connectTimeout());
        factory.setReadTimeout(settings.readTimeout());
        RestTemplate restTemplate = new RestTemplate(factory);
        if (debug) {
            restTemplate.setInterceptors(List.<ClientHttpRequestInterceptor>of((request, body, execution) -> {
                log.info("[HC] {} {} thread={}", DebugLog.now(), request.getURI(), Thread.currentThread().getName());
                return execution.execute(request, body);
            }));
        }
        return restTemplate;
    }

    public static WebClient webClient(LbHealthProperties.HealthCheck settings, boolean debug) {
        // HTTP/1.1: the JDK client would otherwise try an h2c upgrade on every cleartext probe.
        HttpClient httpClient = HttpClient.newBuilder()
                .version(HttpClient.Version.HTTP_1_1)
                .connectTimeout(settings.connectTimeout())
                .build();
        JdkClientHttpConnector connector = new JdkClientHttpConnector(httpClient);
        connector.setReadTimeout(settings.readTimeout());
        WebClient.Builder builder = WebClient.builder().clientConnector(connector);
        if (debug) {
            builder.filter(ExchangeFilterFunction.ofRequestProcessor(request -> {
                log.info("[HC] {} {} thread={}", DebugLog.now(), request.url(), Thread.currentThread().getName());
                return Mono.just(request);
            }));
        }
        return builder.build();
    }

    /** Copy of the stock blocking RestTemplate probe (plus a log line for the swallowed exception). */
    public static BiFunction<ServiceInstance, String, Mono<Boolean>> blockingAliveFunction(RestTemplate restTemplate) {
        return (instance, healthCheckPath) -> Mono.defer(() -> {
            URI uri = UriComponentsBuilder.fromUriString(uri(instance, healthCheckPath)).build().toUri();
            try {
                return Mono.just(HttpStatus.OK.equals(restTemplate.getForEntity(uri, Void.class).getStatusCode()));
            } catch (Exception ex) {
                log.info("[HC] {} {} DOWN: {}", DebugLog.now(), uri, ex.toString());
                return Mono.just(false);
            }
        });
    }

    /** Copy of the stock WebClient probe. */
    public static BiFunction<ServiceInstance, String, Mono<Boolean>> webClientAliveFunction(WebClient webClient) {
        return (instance, healthCheckPath) -> webClient.get()
                .uri(UriComponentsBuilder.fromUriString(uri(instance, healthCheckPath)).build().toUri())
                .retrieve()
                .toBodilessEntity()
                .map(response -> HttpStatus.OK.equals(response.getStatusCode()))
                .doOnError(ex -> log.info("[HC] {} {} DOWN: {}", DebugLog.now(), instance.getUri(), ex.toString()))
                .onErrorReturn(false);
    }

    /** Same as the package-private {@code ServiceInstanceListSupplierBuilder.getUri}. */
    static String uri(ServiceInstance instance, String healthCheckPath) {
        if (healthCheckPath != null && !healthCheckPath.isBlank()) {
            String path = healthCheckPath.startsWith("/") ? healthCheckPath : "/" + healthCheckPath;
            return instance.getUri().toString() + path;
        }
        return instance.getUri().toString();
    }
}
