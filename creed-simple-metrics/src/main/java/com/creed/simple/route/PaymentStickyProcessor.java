package com.creed.simple.route;

import com.creed.simple.lb.StickyMetadataServiceInstanceListSupplier;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.apache.camel.Endpoint;
import org.apache.camel.Exchange;
import org.apache.camel.ExchangePattern;
import org.apache.camel.Message;
import org.apache.camel.Processor;
import org.apache.camel.ProducerTemplate;
import org.apache.camel.RuntimeCamelException;
import org.apache.camel.http.common.HttpMethods;
import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * Narrows the caller's {@code Cookie} header down to the single {@code stickyId} cookie, so it — and
 * nothing else the inbound servlet request carried — rides along to {@code payment-resource}. The
 * {@code payment-resource} LB child context then pins the call to the instance whose registry
 * {@code metadata.stickyId} matches (see {@code StickyMetadataServiceInstanceListSupplier}).
 *
 * <p>Runs as the first step of the {@code fetch-payment} route. The sticky id travels on the exchange
 * (and from there onto the outgoing HTTP request), <em>not</em> on the thread: multicast branches,
 * aggregation pools and the async {@code ProducerTemplate} all copy exchange headers, so there is no
 * pooled-thread staleness to defend against and no context propagation to configure.
 */
@Slf4j
@Component("paymentStickyProcessor")
public class PaymentStickyProcessor implements Processor {
    @Resource
    ProducerTemplate producerTemplate;


    static final String COOKIE_HEADER = "Cookie";
    static final String COOKIE_NAME = StickyMetadataServiceInstanceListSupplier.STICKY_COOKIE;

    @Override
    public void process(Exchange exchange) {
        Map<String, Object> headers = exchange.getMessage().copy().getHeaders();
        headers.put(Exchange.HTTP_METHOD, HttpMethods.GET.name());
//        CompletableFuture<String> asyncResult = producerTemplate.asyncRequestBodyAndHeaders("direct:fetch-order", null, headers, String.class);
//        log.info("result:{}", asyncResult.join());

//        String result = producerTemplate.requestBodyAndHeaders("direct:fetch-order", null, headers, String.class);
//        String result = producerTemplate.requestBodyAndHeaders("https://order-resource/api/order/items?bridgeEndpoint=true", null, headers, String.class);
//        producerTemplate.send("https://order-resource/api/order/large?kb=256&contentType=text/plain&bridgeEndpoint=true",
//                exc -> exc.getMessage().setBody(exchange.getMessage().getBody())) 当返回很大的response的时候，会出现 NoSuchFileException 参考 {@link producertemplate-stream-cache-cleanup.md}
        Endpoint orderEndpoint = exchange.getContext().getEndpoint(
                "https://order-resource/api/order/large?kb=256&contentType=text/plain&bridgeEndpoint=true");
        // The resultProcessor (4th arg) is what makes the body readable afterwards: send() runs the new
        // exchange's UnitOfWork to done BEFORE returning, and that deletes a spooled FileInputStreamCache's
        // temp file (NoSuchFileException on a later getBody). The resultProcessor runs just before the
        // UnitOfWork completes, so materialising the body there is still safe; headers are untouched.
        Exchange result = producerTemplate.send(orderEndpoint, ExchangePattern.InOut,
                exec -> exec.getMessage().setBody(exchange.getIn().getBody()),
                exec -> exec.getMessage().setBody(exec.getMessage().getBody(String.class)));
        if (result.getException() != null) {
            throw new RuntimeCamelException(result.getException());
        }
        String resultStr = result.getMessage().getBody(String.class);
        log.info("order response status={} contentType={} bodyLength={}",
                result.getMessage().getHeader(Exchange.HTTP_RESPONSE_CODE),
                result.getMessage().getHeader(Exchange.CONTENT_TYPE),
                resultStr == null ? 0 : resultStr.length());

        log.info("result:{}", resultStr);
        Message message = exchange.getMessage();
        String stickyId = parseStickyId(message.getHeader(COOKIE_HEADER, String.class));
        if (stickyId != null) {
            message.setHeader(COOKIE_HEADER, COOKIE_NAME + "=" + stickyId);
            log.debug("[LB-STICKY] request carries {}={}", COOKIE_NAME, stickyId);
        } else {
            message.removeHeader(COOKIE_HEADER);
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
