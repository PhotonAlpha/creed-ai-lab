package com.creed.simple.config;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.List;

/**
 * {@link RestHeaderFilterStrategy} from {@code creed.camel.rest.headers.*}; the servlet component takes it by name
 * ({@code camel.component.servlet.header-filter-strategy: "#restHeaderFilterStrategy"} in application.yml).
 */
@Configuration(proxyBeanMethods = false)
@Slf4j
public class RestHeaderFilterConfiguration {

    @Bean
    RestHeaderFilterStrategy restHeaderFilterStrategy(
            @Value("${creed.camel.rest.headers.inbound-denied}") List<String> inboundDenied,
            @Value("${creed.camel.rest.headers.outbound-allowed}") List<String> outboundAllowed) {
        log.info("REST headers: request headers {} kept out of the exchange; response headers only {} (+ Content-Type)",
                inboundDenied, outboundAllowed);
        return new RestHeaderFilterStrategy(inboundDenied, outboundAllowed);
    }
}
