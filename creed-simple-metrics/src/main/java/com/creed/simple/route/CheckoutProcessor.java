package com.creed.simple.route;

import tools.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.apache.camel.Exchange;
import org.apache.camel.http.base.HttpOperationFailedException;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Payload plumbing for the sequential checkout chain ({@code POST /camel/api/checkout}, route
 * {@code checkout} in {@code camel-context.xml}):
 *
 * <pre>
 * inbound {sku, quantity, customer?, method?, currency?}
 *   → POST catalog-resource /api/catalog/reserve   {sku, quantity}            (stock check + price quote)
 *   → POST order-resource   /api/order/checkout    {customer, item, quantity, total}
 *   → POST payment-resource /api/payment/checkout  {orderId, customer, amount, currency, method}
 *   → 200 {status: COMPLETED, reservation, order, payment}
 * </pre>
 *
 * Each hop is a real camel-http POST through the {@code LoadBalancerRoutePlanner} (service-id URIs),
 * so all three audit layers apply per attempt. The prepare* methods parse the previous hop's JSON
 * response, stash it as an exchange property for the final envelope, and set the next hop's request
 * body as a JSON <em>string</em> — the http producer sends String bodies as-is; a Map would be
 * {@code toString()}-ed into garbage.
 *
 * <p>{@link #failure} is the route's doCatch handler: a downstream 4xx passes through with the
 * downstream body attached (e.g. the reserve step's 409 insufficient-stock), a 5xx maps to 502
 * (mirroring {@link FailureResponseProcessor}), and gateway-side validation errors
 * ({@link IllegalArgumentException} from {@link #prepareReserve}) become a 400. No compensation is
 * attempted for hops that already succeeded — the failure body says which step failed so the caller
 * can see what state was left behind.
 */
@Slf4j
@Component("checkoutProcessor")
public class CheckoutProcessor {

    static final String PROP_REQUEST = "checkoutRequest";
    static final String PROP_RESERVATION = "checkoutReservation";
    static final String PROP_ORDER = "checkoutOrder";
    static final String PROP_STEP = "checkoutStep";

    private final ObjectMapper mapper;

    public CheckoutProcessor(ObjectMapper mapper) {
        this.mapper = mapper;
    }

    /** Validates the inbound request (REST binding already unmarshalled it to a Map) and builds the reserve payload. */
    @SuppressWarnings("unchecked")
    public void prepareReserve(Exchange exchange) throws Exception {
        Map<String, Object> body = exchange.getMessage().getBody(Map.class);
        if (body == null || !(body.get("sku") instanceof String sku) || sku.isBlank()) {
            throw new IllegalArgumentException("checkout requires a non-blank 'sku'");
        }
        int quantity = body.get("quantity") instanceof Number n ? n.intValue() : 1;
        if (quantity <= 0) {
            throw new IllegalArgumentException("'quantity' must be positive");
        }
        Map<String, Object> request = new LinkedHashMap<>();
        request.put("sku", sku);
        request.put("quantity", quantity);
        request.put("customer", body.getOrDefault("customer", "anonymous"));
        request.put("method", body.getOrDefault("method", "CARD"));
        request.put("currency", body.getOrDefault("currency", "SGD"));
        exchange.setProperty(PROP_REQUEST, request);
        exchange.setProperty(PROP_STEP, "catalog-reserve");
        exchange.getMessage().setBody(mapper.writeValueAsString(Map.of("sku", sku, "quantity", quantity)));
    }

    /** Reserve response → order-checkout payload (item name and quoted total come from the reservation). */
    public void prepareOrder(Exchange exchange) throws Exception {
        Map<String, Object> reservation = readJson(exchange);
        Map<String, Object> request = requestOf(exchange);
        exchange.setProperty(PROP_RESERVATION, reservation);
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("customer", request.get("customer"));
        payload.put("item", reservation.getOrDefault("name", request.get("sku")));
        payload.put("quantity", request.get("quantity"));
        payload.put("total", reservation.get("total"));
        exchange.setProperty(PROP_STEP, "order-checkout");
        exchange.getMessage().setBody(mapper.writeValueAsString(payload));
    }

    /** Order response → payment-checkout payload (amount is the order total the order service accepted). */
    public void preparePayment(Exchange exchange) throws Exception {
        Map<String, Object> order = readJson(exchange);
        Map<String, Object> request = requestOf(exchange);
        exchange.setProperty(PROP_ORDER, order);
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("orderId", order.get("id"));
        payload.put("customer", request.get("customer"));
        payload.put("amount", order.get("total"));
        payload.put("currency", request.get("currency"));
        payload.put("method", request.get("method"));
        exchange.setProperty(PROP_STEP, "payment-checkout");
        exchange.getMessage().setBody(mapper.writeValueAsString(payload));
    }

    /** Payment response → final success envelope (a Map; the REST binding marshals it back to JSON). */
    public void assemble(Exchange exchange) throws Exception {
        Map<String, Object> payment = readJson(exchange);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("status", "COMPLETED");
        result.put("reservation", exchange.getProperty(PROP_RESERVATION));
        result.put("order", exchange.getProperty(PROP_ORDER));
        result.put("payment", payment);
        exchange.getMessage().setBody(result);
    }

    /** doCatch handler: failure envelope naming the failed step, with the downstream status/body when present. */
    public void failure(Exchange exchange) {
        Exception ex = exchange.getProperty(Exchange.EXCEPTION_CAUGHT, Exception.class);
        String step = exchange.getProperty(PROP_STEP, "request-validation", String.class);
        Map<String, Object> failureBody = new LinkedHashMap<>();
        failureBody.put("status", "FAILED");
        failureBody.put("step", step);
        int responseCode;
        if (ex instanceof HttpOperationFailedException http) {
            // Same policy as FailureResponseProcessor: pass a downstream 4xx through, map a 5xx to 502.
            responseCode = http.getStatusCode() >= 500 ? 502 : http.getStatusCode();
            failureBody.put("message", "downstream rejected the " + step + " step");
            failureBody.put("downstreamStatus", http.getStatusCode());
            failureBody.put("downstreamBody", parseIfJson(http.getResponseBody()));
        }
        else if (ex instanceof IllegalArgumentException) {
            responseCode = 400;
            failureBody.put("message", ex.getMessage());
        }
        else {
            responseCode = 502;
            failureBody.put("message", String.valueOf(ex));
        }
        log.warn("checkout failed at step={} -> {}: {}", step, responseCode, String.valueOf(ex));
        exchange.getMessage().setBody(failureBody);
        exchange.getMessage().setHeader(Exchange.HTTP_RESPONSE_CODE, responseCode);
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> readJson(Exchange exchange) throws Exception {
        return mapper.readValue(exchange.getMessage().getBody(String.class), Map.class);
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> requestOf(Exchange exchange) {
        return (Map<String, Object>) exchange.getProperty(PROP_REQUEST, Map.class);
    }

    /** Downstream error bodies are usually JSON but not guaranteed — fall back to the raw string. */
    private Object parseIfJson(String body) {
        if (body == null || body.isBlank()) {
            return null;
        }
        try {
            return mapper.readValue(body, Map.class);
        }
        catch (Exception notJson) {
            return body;
        }
    }
}
