package com.creed.simple.tutorial.service;

import tools.jackson.databind.ObjectMapper;
import org.apache.camel.Exchange;
import org.apache.camel.ProducerTemplate;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/**
 * The heart of API-3: the two ways to use a {@link ProducerTemplate}, wrapped in a
 * Freemarker-based anti-corruption layer.
 *
 * <p><b>What a ProducerTemplate is</b>: Camel's client-side API. It lets <i>any</i> Java code
 * push a message to an Endpoint without living inside a route. Think of it as Camel's
 * {@code RestTemplate}, except the target can be any component (freemarker, http, jms,
 * direct, ...).
 *
 * <ul>
 *   <li>{@code sendBody(...)}    - InOnly: send and move on, no reply expected</li>
 *   <li>{@code requestBody(...)} - InOut: wait for and return the reply (used here)</li>
 * </ul>
 *
 * <p><b>The message flow</b> - note that every hop is the same template call:
 * <pre>
 *   internal order Map
 *     -- freemarker:tutorial/templates/request.ftl  --&gt; outbound JSON in the remote system's shape
 *     -- http://.../mock/remote/fx-rate    --&gt; inbound JSON in the remote system's shape
 *     -- freemarker:tutorial/templates/response.ftl --&gt; business JSON this application understands
 *   business Map
 * </pre>
 * Keeping both mappings in {@code .ftl} files means the remote system's format is described
 * in exactly two places, and changing it never touches Java code.
 *
 * <p><b>Where does this ProducerTemplate come from?</b>
 * With the Spring XML DSL, {@code <camelContext>} automatically registers two beans in the
 * Spring container: a {@link ProducerTemplate} named {@code template} and a ConsumerTemplate
 * named {@code consumerTemplate}. So plain constructor injection is enough - <b>never new one
 * up or declare another @Bean</b>, or the container ends up with two beans of the same type
 * and startup fails with NoUniqueBeanDefinitionException.
 *
 * <p>(A ProducerTemplate holds an endpoint cache and thread pools, so it must be reused as a
 * singleton; creating one per call leaks resources.)
 *
 * <p><b>Why is there an {@code @Lazy} on the constructor parameter?</b>
 * Resolving {@code template} also forces {@code <camelContext>} to be instantiated. If a business
 * bean is created before Camel's {@code *ComponentAutoConfiguration} beans, the CamelContext is
 * pulled in too early and the container hits a "camelContext to autoConfiguration" cycle that
 * fails startup. {@code @Lazy} injects a proxy and defers the real lookup to the first call,
 * which makes the cycle go away. This is a classic pitfall of combining an XML-defined
 * CamelContext with Spring Boot auto-configuration.
 */
@Component("remoteCallService")
public class RemoteCallService {

    private static final Logger log = LoggerFactory.getLogger(RemoteCallService.class);

    /** Renders the outbound payload in the remote system's request format. */
    private static final String REQUEST_TEMPLATE = "freemarker:tutorial/templates/request.ftl";
    /** Maps the remote system's reply back into this application's own format. */
    private static final String RESPONSE_TEMPLATE = "freemarker:tutorial/templates/response.ftl";

    private final ProducerTemplate producerTemplate;
    private final ObjectMapper objectMapper;
    private final String remoteBaseUrl;

    // Qualified: CamelConfig's context-propagating "producerTemplate" is the one to use, not the
    // "template" bean the <camelContext> registers by itself.
    public RemoteCallService(@Lazy @Qualifier("producerTemplate") ProducerTemplate producerTemplate,
                             ObjectMapper objectMapper,
                             @Value("${creed.tutorial.remote.base-url}") String remoteBaseUrl) {
        this.producerTemplate = producerTemplate;
        this.objectMapper = objectMapper;
        this.remoteBaseUrl = remoteBaseUrl;
    }

    /**
     * Invoked from the XML route as {@code <to uri="bean:remoteCallService?method=handle"/>}.
     */
    @SuppressWarnings("unchecked")
    public Map<String, Object> handle(Map<String, Object> request) throws Exception {
        log.info("[API-3][entry] request={}", request);
        String msgId = UUID.randomUUID().toString();

        // ---------- Step 1: render the outbound payload with request.ftl ----------
        // The internal order Map goes in, the remote system's envelope comes out as text.
        // The template is the only thing that knows that shape.
        String outboundJson = producerTemplate.requestBodyAndHeader(
                REQUEST_TEMPLATE, request, "msgId", msgId, String.class);
        log.info("[API-3][request.ftl] internal order -> remote request format:\n{}",
                outboundJson.trim());

        // ---------- Step 2: ProducerTemplate calling a REMOTE HTTP API ----------
        String url = remoteBaseUrl + "/mock/remote/fx-rate";
        log.info("[API-3][remote call] POST {}", url);
        String remoteRaw = producerTemplate.requestBodyAndHeaders(
                // camel-http producer endpoint (CamelConfig's component serves both http and https;
                // the scheme of the URI decides). bridgeEndpoint makes it ignore the inbound path/query.
                url + "?bridgeEndpoint=true",
                outboundJson,
                Map.of(Exchange.HTTP_METHOD, "POST",
                       Exchange.CONTENT_TYPE, "application/json"),
                String.class);
        log.info("[API-3][remote call] raw reply={}", remoteRaw);

        // ---------- Step 3: map the reply back with response.ftl ----------
        // The reply is text, so it is parsed into a Map first; the template then flattens the
        // remote envelope into the shape the rest of this application expects.
        Map<String, Object> remoteReply = objectMapper.readValue(remoteRaw, Map.class);
        String businessJson = producerTemplate.requestBody(
                RESPONSE_TEMPLATE, remoteReply, String.class);
        log.info("[API-3][response.ftl] remote reply -> business format:\n{}",
                businessJson.trim());
        Map<String, Object> remote = objectMapper.readValue(businessJson, Map.class);

        // ---------- Step 4: ProducerTemplate calling a LOCAL Camel route ----------
        // direct: is a synchronous, same-thread, in-memory endpoint - the natural way to model a sub-flow
        log.info("[API-3][route call] -> direct:api3-calc-fee");
        Map<String, Object> fee = producerTemplate.requestBody(
                "direct:api3-calc-fee", request, Map.class);
        log.info("[API-3][route call] <- direct:api3-calc-fee returned={}", fee);

        Number amount = (Number) request.getOrDefault("amount", 0);
        double rate = ((Number) remote.get("rate")).doubleValue();

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("code", "OK");
        result.put("orderId", request.get("orderId"));
        result.put("currency", remote.get("currency"));
        result.put("rate", rate);
        result.put("amountInCNY", round2(amount.doubleValue() * rate));
        result.put("fee", fee.get("fee"));
        result.put("feeRule", fee.get("rule"));
        result.put("remoteProvider", remote.get("provider"));
        result.put("remoteCode", remote.get("remoteCode"));
        result.put("msgId", msgId);
        log.info("[API-3][exit] summary={}", result);
        return result;
    }

    /** Called by the {@code direct:api3-calc-fee} route: a route may itself call back into a bean. */
    public Map<String, Object> calcFee(Map<String, Object> request) {
        Number amount = (Number) request.getOrDefault("amount", 0);
        double a = amount.doubleValue();
        String rule = a >= 1000 ? "VIP-0.5%" : "STD-1.5%";
        double fee = round2(a >= 1000 ? a * 0.005 : a * 0.015);
        log.info("[API-3][sub-route] fee calculation amount={} rule={} fee={}", a, rule, fee);
        return Map.of("fee", fee, "rule", rule);
    }

    private static double round2(double v) {
        return Math.round(v * 100d) / 100d;
    }
}
