package com.creed.simple.route;

import com.creed.simple.lb.StickyContextHolder;
import org.apache.camel.Exchange;
import org.apache.camel.ProducerTemplate;
import org.apache.camel.impl.DefaultCamelContext;
import org.apache.camel.support.DefaultExchange;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.concurrent.CompletableFuture;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link PaymentStickyProcessor}: Cookie-header parsing and the always-overwrite
 * semantics that keep a pooled route thread from leaking a previous request's sticky id. The downstream
 * {@code direct:fetch-order} call the processor fires is stubbed on a mocked {@link ProducerTemplate}, so
 * the tests exercise only the sticky-id lifting logic.
 */
@ExtendWith(MockitoExtension.class)
class PaymentStickyProcessorTest {

    private static final String STICKY = "27AE496060A84649E527E8533A185D1461286662457143FE306F422EF1FA2696";

    @Mock
    private ProducerTemplate producerTemplate;
    @InjectMocks
    private PaymentStickyProcessor processor;

    private final DefaultCamelContext camelContext = new DefaultCamelContext();

    @BeforeEach
    void stubDownstreamCall() {
        when(producerTemplate.asyncRequestBodyAndHeaders(anyString(), any(), anyMap(), eq(String.class)))
                .thenReturn(CompletableFuture.completedFuture("downstream-ok"));
    }

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
