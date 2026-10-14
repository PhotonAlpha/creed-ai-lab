package com.creed.simple.tutorial.mock;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * A stand-in for an "external system" (a plain Spring MVC controller,
 * <b>not part of any Camel route</b>).
 *
 * <p>In API-3 the ProducerTemplate calls this over real HTTP, so the outbound-call demo
 * still works end to end on a machine with no network access.
 *
 * <p><b>It deliberately speaks a different dialect</b> from the rest of this application:
 * a {@code header} / {@code payload} envelope with abbreviated field names. That is what
 * makes the two Freemarker templates in {@code resources/tutorial/templates/} meaningful - without a
 * format mismatch there would be nothing for them to translate.
 *
 * <pre>
 * request   { "header": { "msgId", "source", "version" },
 *             "payload": { "ccy", "amt", "ref" } }
 * response  { "header": { "msgId", "code" },
 *             "payload": { "ccy", "fxRate", "provider" } }
 * </pre>
 */
@RestController
@RequestMapping("/mock/remote")
public class MockRemoteApiController {

    private static final Logger log = LoggerFactory.getLogger(MockRemoteApiController.class);

    @PostMapping("/fx-rate")
    @SuppressWarnings("unchecked")
    public Map<String, Object> fxRate(@RequestBody Map<String, Object> req) {
        log.info("[MOCK-REMOTE] external call received: {}", req);

        Map<String, Object> header = (Map<String, Object>) req.getOrDefault("header", Map.of());
        Map<String, Object> payload = (Map<String, Object>) req.getOrDefault("payload", Map.of());

        String currency = String.valueOf(payload.getOrDefault("ccy", "USD"));
        double rate = switch (currency) {
            case "USD" -> 7.24;
            case "EUR" -> 7.86;
            case "JPY" -> 0.048;
            default -> 1.0;
        };

        Map<String, Object> respHeader = new LinkedHashMap<>();
        respHeader.put("msgId", header.getOrDefault("msgId", "unknown"));
        respHeader.put("code", "0000");

        Map<String, Object> respPayload = new LinkedHashMap<>();
        respPayload.put("ccy", currency);
        respPayload.put("fxRate", rate);
        respPayload.put("provider", "mock-fx-service");

        Map<String, Object> resp = new LinkedHashMap<>();
        resp.put("header", respHeader);
        resp.put("payload", respPayload);

        log.info("[MOCK-REMOTE] returning fx rate: {}", resp);
        return resp;
    }
}
