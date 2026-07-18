package com.creed.simple.config;

import lombok.extern.slf4j.Slf4j;
import org.apache.camel.Exchange;
import org.apache.camel.spi.CamelEvent;
import org.apache.camel.support.EventNotifierSupport;
import org.apache.camel.util.URISupport;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Times every endpoint send in the context — each {@code <to>} in a route, wireTap/multicast branches
 * and ProducerTemplate sends — via {@link CamelEvent.ExchangeSentEvent}, whose {@code timeTaken} is
 * measured by Camel around the producer call. {@code direct:} hops are skipped: they are in-JVM route
 * glue whose time is already contained in the outer send.
 *
 * <p>Just a {@code @Component}: the classic {@code <camelContext>} factory bean discovers every
 * {@code EventNotifier} bean in the Spring registry and adds it to the management strategy.
 *
 * <p>Lines go to the {@code METRICS} named logger (see {@code logback-spring.xml}), i.e. the
 * {@code *-metrics.log} file, as single-line key=value records for easy scraping — alongside what
 * Micrometer already tags on {@code http.client.requests} for the RestClient side.
 */
@Component
@Slf4j
public class CamelSendTimingEventNotifier extends EventNotifierSupport {

    private static final Logger METRICS = LoggerFactory.getLogger("METRICS");

    @Override
    public boolean isEnabled(CamelEvent event) {
        return event instanceof CamelEvent.ExchangeSentEvent;
    }

    @Override
    public void notify(CamelEvent event) {
        CamelEvent.ExchangeSentEvent sent = (CamelEvent.ExchangeSentEvent) event;
        String uri = sent.getEndpoint().getEndpointUri();
        if (uri.startsWith("direct:")) {
            return;
        }
        Exchange exchange = sent.getExchange();
        Integer status = exchange.getMessage().getHeader(Exchange.HTTP_RESPONSE_CODE, Integer.class);
        METRICS.info("camel-send endpoint={} status={} timeMs={} failed={} fromRoute={} exchangeId={}",
                URISupport.sanitizeUri(uri),
                status != null ? status : "-",
                sent.getTimeTaken(),
                exchange.isFailed(),
                exchange.getFromRouteId(),
                exchange.getExchangeId());
        log.info("camel-send endpoint={} status={} timeMs={} failed={} fromRoute={} exchangeId={}",
                URISupport.sanitizeUri(uri),
                status != null ? status : "-",
                sent.getTimeTaken(),
                exchange.isFailed(),
                exchange.getFromRouteId(),
                exchange.getExchangeId());
    }
}
