package com.creed.simple.route;

import com.creed.simple.lb.StickyContextHolder;
import lombok.extern.slf4j.Slf4j;
import org.apache.camel.Exchange;
import org.apache.camel.Processor;
import org.springframework.stereotype.Component;

/**
 * Lifts the caller's {@code stickyId} cookie off the inbound exchange into {@link StickyContextHolder}
 * so the {@code payment-resource} LB child context can pin the downstream call to the instance whose
 * registry {@code metadata.stickyId} matches (see {@code StickyMetadataServiceInstanceListSupplier}).
 *
 * <p>Runs as the first step of the {@code fetch-payment} route — i.e. on the same thread that will
 * execute the camel-http producer and, inside it, {@code LoadBalancerRoutePlanner}'s blocking
 * {@code choose()}. It <em>always overwrites</em> the holder ({@code null} when the request has no
 * sticky cookie): route threads come from pools, so an unconditional set is what prevents a previous
 * request's sticky id from leaking into this one.
 */
@Slf4j
@Component("paymentStickyProcessor")
public class PaymentStickyProcessor implements Processor {

    static final String COOKIE_NAME = "stickyId";

    @Override
    public void process(Exchange exchange) {
        String cookieHeader = exchange.getIn().getHeader("Cookie", String.class);
        String stickyId = parseStickyId(cookieHeader);
        StickyContextHolder.set(stickyId);
        if (stickyId != null) {
            log.debug("[LB-STICKY] request carries {}={}", COOKIE_NAME, stickyId);
        }
    }

    /** Extracts the {@code stickyId} value from a {@code Cookie} header, or {@code null} when absent. */
    static String parseStickyId(String cookieHeader) {
        if (cookieHeader == null || cookieHeader.isBlank()) {
            return null;
        }
        for (String pair : cookieHeader.split(";")) {
            String trimmed = pair.trim();
            if (trimmed.startsWith(COOKIE_NAME + "=")) {
                String value = trimmed.substring(COOKIE_NAME.length() + 1).trim();
                return value.isEmpty() ? null : value;
            }
        }
        return null;
    }
}
