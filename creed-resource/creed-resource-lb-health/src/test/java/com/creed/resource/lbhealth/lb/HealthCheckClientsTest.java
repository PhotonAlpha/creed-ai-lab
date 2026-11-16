package com.creed.resource.lbhealth.lb;

import com.creed.resource.lbhealth.config.LbHealthProperties;
import org.junit.jupiter.api.Test;
import org.springframework.web.reactive.function.client.ExchangeFilterFunction;
import org.springframework.web.reactive.function.client.WebClient;
import org.zalando.logbook.Logbook;
import org.zalando.logbook.spring.LogbookClientHttpRequestInterceptor;
import org.zalando.logbook.spring.webflux.LogbookExchangeFilterFunction;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** {@code creed.lb-health.logbook.enabled}: a Logbook passed in is attached to the probe client, {@code null} is not. */
class HealthCheckClientsTest {

    private static final LbHealthProperties.HealthCheck SETTINGS = new LbHealthProperties.HealthCheck(
            LbHealthProperties.Mode.BLOCKING, Duration.ofSeconds(1), Duration.ofSeconds(1));

    @Test
    void theRestTemplateCarriesLogbookOnlyWhenGivenOne() {
        assertThat(HealthCheckClients.restTemplate(SETTINGS, false, Logbook.create()).getInterceptors())
                .singleElement().isInstanceOf(LogbookClientHttpRequestInterceptor.class);
        assertThat(HealthCheckClients.restTemplate(SETTINGS, false, null).getInterceptors()).isEmpty();
        // debug's [HC] interceptor stays first: interceptors run in list order
        assertThat(HealthCheckClients.restTemplate(SETTINGS, true, Logbook.create()).getInterceptors())
                .hasSize(2).last().isInstanceOf(LogbookClientHttpRequestInterceptor.class);
        assertThat(HealthCheckClients.restTemplate(SETTINGS, true, null).getInterceptors()).hasSize(1)
                .noneMatch(LogbookClientHttpRequestInterceptor.class::isInstance);
    }

    @Test
    void theWebClientCarriesLogbookOnlyWhenGivenOne() {
        assertThat(filters(HealthCheckClients.webClient(SETTINGS, false, Logbook.create())))
                .singleElement().isInstanceOf(LogbookExchangeFilterFunction.class);
        assertThat(filters(HealthCheckClients.webClient(SETTINGS, false, null))).isEmpty();
        assertThat(filters(HealthCheckClients.webClient(SETTINGS, true, null)))
                .hasSize(1).noneMatch(LogbookExchangeFilterFunction.class::isInstance);
    }

    private static List<ExchangeFilterFunction> filters(WebClient client) {
        List<ExchangeFilterFunction> seen = new ArrayList<>();
        client.mutate().filters(seen::addAll);
        return seen;
    }
}
