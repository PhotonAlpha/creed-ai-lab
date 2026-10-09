package com.creed.gatewayproxy.api;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ServerWebInputException;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import com.creed.gatewayproxy.api.dto.SplunkDtos;
import com.creed.gatewayproxy.service.splunk.BrokerException;
import com.creed.gatewayproxy.service.splunk.SplunkBroker;
import com.creed.gatewayproxy.service.splunk.audit.SplunkAuditRow;

/**
 * The Splunk page's API, moved here from the Node BFF — same paths and JSON:
 *
 * <pre>
 * GET  /api/env-matrix/splunk/totp          period/digits/server time, targets, block windows
 * GET  /api/env-matrix/splunk/totp/current  the current code (creed.totp.expose-current-code)
 * POST /api/env-matrix/splunk/session       {code, target?, sessionCookie?, scriptCookieName?, viaTunnel?}
 *                                           → 200 | 400 | 401 | 403 blocked | 429 | 502 Splunk | 503 unset
 * GET  /api/env-matrix/splunk/audit?limit=  newest audit rows
 * </pre>
 *
 * A controller outranks the gateway's {@code /api/**} route (handler-mapping order 0 vs 1), so these
 * paths are answered here and everything else under /api goes to the env-matrix backend.
 */
@Slf4j
@RestController
@RequestMapping("/api/env-matrix/splunk")
@RequiredArgsConstructor
public class SplunkController {

    private final SplunkBroker broker;

    @GetMapping("/totp")
    public SplunkDtos.TotpInfo totp() {
        return broker.info();
    }

    @GetMapping("/totp/current")
    public ResponseEntity<SplunkDtos.TotpCode> current() {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(broker.currentCode());
    }

    @PostMapping("/session")
    public Mono<ResponseEntity<SplunkDtos.Session>> session(@RequestBody SplunkDtos.SessionRequest body, ServerHttpRequest request) {
        if (body.code() == null || !body.code().matches("^\\d{6,8}$")) return Mono.error(invalid("code", "must be 6-8 digits"));
        if (body.target() != null && body.target().length() > 64) return Mono.error(invalid("target", "must be a login target id"));
        SplunkBroker.Client client = new SplunkBroker.Client(
                request.getRemoteAddress() == null ? null : request.getRemoteAddress().getAddress().getHostAddress(),
                request.getHeaders().getFirst("X-Forwarded-For"), request.getHeaders().getFirst(HttpHeaders.USER_AGENT));
        // Off the event loop: the login and a JDBC audit store block.
        return Mono.fromCallable(() -> broker.issue(body, client))
                .subscribeOn(Schedulers.boundedElastic())
                .map(session -> ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(session));
    }

    @GetMapping("/audit")
    public Mono<List<SplunkAuditRow>> audit(@RequestParam(defaultValue = "50") int limit) {
        return Mono.fromCallable(() -> broker.audit(limit)).subscribeOn(Schedulers.boundedElastic());
    }

    private static FieldException invalid(String field, String message) {
        return new FieldException(field, message);
    }

    static class FieldException extends RuntimeException {
        final String field;

        FieldException(String field, String message) {
            super(message);
            this.field = field;
        }
    }

    @ExceptionHandler(BrokerException.class)
    ResponseEntity<Map<String, Object>> broker(BrokerException e) {
        ResponseEntity.BodyBuilder response = ResponseEntity.status(e.status());
        e.headers().forEach(response::header);
        return response.body(body(e.error(), e.getMessage(), null));
    }

    @ExceptionHandler(FieldException.class)
    ResponseEntity<Map<String, Object>> field(FieldException e) {
        return ResponseEntity.badRequest().body(body("validation_failed", "request payload is invalid",
                List.of(Map.of("field", e.field, "message", e.getMessage()))));
    }

    @ExceptionHandler(ServerWebInputException.class)
    ResponseEntity<Map<String, Object>> unreadable(ServerWebInputException e) {
        return ResponseEntity.badRequest().body(body("validation_failed", "request body is not valid JSON", null));
    }

    /** Most likely the audit store — the broker fails closed rather than issue an unrecorded session. */
    @ExceptionHandler(RuntimeException.class)
    ResponseEntity<Map<String, Object>> internal(RuntimeException e) {
        log.error("Splunk broker request failed", e);
        return ResponseEntity.internalServerError().body(body("internal_error", "the Splunk broker failed — see the server log", null));
    }

    private static Map<String, Object> body(String error, String message, List<Map<String, String>> fields) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("error", error);
        body.put("message", message);
        if (fields != null) body.put("fields", fields);
        body.put("time", Instant.now().toString());
        return body;
    }
}
