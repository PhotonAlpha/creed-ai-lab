package com.creed.simple.config;

import org.apache.camel.Exchange;
import org.apache.camel.support.SynchronizationAdapter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Per-exchange audit callback: one {@code CAMEL-AUDIT} line when the whole REST API exchange is done
 * (the servlet consumer's UnitOfWork completes — i.e. after the response has been produced), covering
 * every nested {@code direct:} route the exchange traversed.
 *
 * <p>Attached once per inbound REST exchange by {@link RestApiAuditRoutePolicyFactory}. The request
 * method/URI are captured at attach time: the downstream routes strip the inbound HTTP headers
 * ({@code <removeHeaders pattern="CamelHttp*"/>}) before calling the clusters, so by completion time
 * {@code CamelHttpMethod}/{@code CamelHttpUri} are gone from the exchange.
 *
 * <p>{@code CamelHttpResponseCode} at completion time is whatever the LAST producer left on the
 * message (usually the downstream cluster's status), not necessarily the servlet response status —
 * hence the {@code 200}/{@code 500} fallback when it is absent.
 */
public class RestApiAuditSynchronization extends SynchronizationAdapter {

    private static final Logger log = LoggerFactory.getLogger(RestApiAuditSynchronization.class);

    private final String method;
    private final String uri;

    public RestApiAuditSynchronization(String method, String uri) {
        this.method = method;
        this.uri = uri;
    }

    @Override
    public void onComplete(Exchange exchange) {
        log.info("CAMEL-AUDIT {} {} status={} in {}ms exchangeId={} route={}",
                method, uri, status(exchange, 200), exchange.getClock().elapsed(),
                exchange.getExchangeId(), exchange.getFromRouteId());
    }

    @Override
    public void onFailure(Exchange exchange) {
        log.warn("CAMEL-AUDIT {} {} status={} FAILED in {}ms exchangeId={} route={} error={}",
                method, uri, status(exchange, 500), exchange.getClock().elapsed(),
                exchange.getExchangeId(), exchange.getFromRouteId(),
                String.valueOf(exchange.getException()));
    }

    private static int status(Exchange exchange, int fallback) {
        Integer code = exchange.getMessage().getHeader(Exchange.HTTP_RESPONSE_CODE, Integer.class);
        return code != null ? code : fallback;
    }
}
