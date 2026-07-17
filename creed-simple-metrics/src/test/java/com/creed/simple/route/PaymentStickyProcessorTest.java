package com.creed.simple.route;

import com.creed.simple.lb.StickyContextHolder;
import org.apache.camel.Exchange;
import org.apache.camel.impl.DefaultCamelContext;
import org.apache.camel.support.DefaultExchange;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for {@link PaymentStickyProcessor}: Cookie-header parsing and the always-overwrite
 * semantics that keep a pooled route thread from leaking a previous request's sticky id.
 */
class PaymentStickyProcessorTest {

    private static final String STICKY = "27AE496060A84649E527E8533A185D1461286662457143FE306F422EF1FA2696";

    private final PaymentStickyProcessor processor = new PaymentStickyProcessor();
    private final DefaultCamelContext camelContext = new DefaultCamelContext();

    @AfterEach
    void clearHolder() {
        StickyContextHolder.clear();
    }

    private void processWithCookie(String cookieHeader) {
        Exchange exchange = new DefaultExchange(camelContext);
        if (cookieHeader != null) {
            exchange.getIn().setHeader("Cookie", cookieHeader);
        }
        processor.process(exchange);
    }

    @Test
    void liftsStickyIdFromASoloCookie() {
        processWithCookie("stickyId=" + STICKY);
        assertThat(StickyContextHolder.get()).isEqualTo(STICKY);
    }

    @Test
    void liftsStickyIdFromAMultiCookieHeader() {
        processWithCookie("JSESSIONID=abc123; stickyId=" + STICKY + "; theme=dark");
        assertThat(StickyContextHolder.get()).isEqualTo(STICKY);
    }

    @Test
    void overwritesAStaleValueWhenTheRequestHasNoCookie() {
        StickyContextHolder.set("STALE-FROM-PREVIOUS-REQUEST");
        processWithCookie(null);
        assertThat(StickyContextHolder.get()).isNull();
    }

    @Test
    void overwritesAStaleValueWhenTheCookieHeaderLacksStickyId() {
        StickyContextHolder.set("STALE-FROM-PREVIOUS-REQUEST");
        processWithCookie("JSESSIONID=abc123");
        assertThat(StickyContextHolder.get()).isNull();
    }

    @Test
    void emptyStickyValueCountsAsAbsent() {
        processWithCookie("stickyId=; JSESSIONID=abc123");
        assertThat(StickyContextHolder.get()).isNull();
    }
}
