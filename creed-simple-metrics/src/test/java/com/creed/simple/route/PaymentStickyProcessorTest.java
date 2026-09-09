package com.creed.simple.route;

import org.apache.camel.Exchange;
import org.apache.camel.ProducerTemplate;
import org.apache.camel.impl.DefaultCamelContext;
import org.apache.camel.support.DefaultExchange;
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
 * Unit tests for {@link PaymentStickyProcessor}: Cookie-header parsing and the narrowing of the outgoing
 * {@code Cookie} header down to the sticky cookie alone (the value the route planner later reads off the
 * outgoing request). The downstream {@code direct:fetch-order} call the processor fires is stubbed on a
 * mocked {@link ProducerTemplate}, so the tests exercise only the sticky-cookie logic.
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

    /** Runs the processor over an exchange carrying the given Cookie header, returns the outgoing one. */
    private String processWithCookie(String cookieHeader) {
        Exchange exchange = new DefaultExchange(camelContext);
        if (cookieHeader != null) {
            exchange.getIn().setHeader(PaymentStickyProcessor.COOKIE_HEADER, cookieHeader);
        }
        processor.process(exchange);
        return exchange.getMessage().getHeader(PaymentStickyProcessor.COOKIE_HEADER, String.class);
    }

    @Test
    void keepsASoloStickyCookie() {
        assertThat(processWithCookie("stickyId=" + STICKY)).isEqualTo("stickyId=" + STICKY);
    }

    @Test
    void narrowsAMultiCookieHeaderToTheStickyCookie() {
        assertThat(processWithCookie("JSESSIONID=abc123; stickyId=" + STICKY + "; theme=dark"))
                .isEqualTo("stickyId=" + STICKY);
    }

    @Test
    void dropsTheHeaderWhenTheRequestHasNoCookie() {
        assertThat(processWithCookie(null)).isNull();
    }

    @Test
    void dropsTheHeaderWhenTheCookieHeaderLacksStickyId() {
        assertThat(processWithCookie("JSESSIONID=abc123")).isNull();
    }

    @Test
    void emptyStickyValueCountsAsAbsent() {
        assertThat(processWithCookie("stickyId=; JSESSIONID=abc123")).isNull();
    }
}
