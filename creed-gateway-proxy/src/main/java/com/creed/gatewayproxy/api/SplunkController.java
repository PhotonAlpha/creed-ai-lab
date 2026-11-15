package com.creed.gatewayproxy.api;

import java.util.List;

import lombok.RequiredArgsConstructor;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import com.creed.gatewayproxy.api.dto.SplunkDtos;
import com.creed.gatewayproxy.service.splunk.ClientHostResolver;
import com.creed.gatewayproxy.service.splunk.SplunkBroker;
import com.creed.gatewayproxy.service.splunk.audit.SplunkAuditRow;

/**
 * The Splunk page's API, moved here from the Node BFF — same paths and JSON:
 *
 * <pre>
 * GET  /api/env-matrix/splunk/totp          period/digits/server time, targets (each with its block windows),
 *                                           the caller's IP and host name
 * GET  /api/env-matrix/splunk/totp/current  the current code (creed.totp.expose-current-code)
 * POST /api/env-matrix/splunk/session       {code, target?, sessionCookie?, scriptCookieName?, viaTunnel?}
 *                                           → 200 | 400 | 401 | 403 blocked | 429 | 502 Splunk | 503 unset
 * POST /api/env-matrix/splunk/session/{correlationId}/revoke
 *                                           Splunk Web logout for a session issued here → 200 | 400 | 404 | 502
 * GET  /api/env-matrix/splunk/audit?limit=  newest audit rows
 * </pre>
 *
 * Errors: SplunkApiErrors. The admin API (allowed windows, bearer token) is SplunkAdminController.
 *
 * A controller outranks the gateway's {@code /api/**} route (handler-mapping order 0 vs 1), so these
 * paths are answered here and everything else under /api goes to the env-matrix backend.
 */
@RestController
@RequestMapping("/api/env-matrix/splunk")
@RequiredArgsConstructor
public class SplunkController {

    private final SplunkBroker broker;
    private final ClientHostResolver hosts;

    /** Off the event loop: the caller's reverse lookup may wait on DNS. */
    @GetMapping("/totp")
    public Mono<SplunkDtos.TotpInfo> totp(ServerHttpRequest request) {
        return Mono.fromCallable(() -> broker.info(client(request, hosts)))
                .subscribeOn(Schedulers.boundedElastic());
    }

    @GetMapping("/totp/current")
    public ResponseEntity<SplunkDtos.TotpCode> current() {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(broker.currentCode());
    }

    @PostMapping("/session")
    public Mono<ResponseEntity<SplunkDtos.Session>> session(@RequestBody SplunkDtos.SessionRequest body, ServerHttpRequest request) {
        if (body.code() == null || !body.code().matches("^\\d{6,8}$")) return Mono.error(invalid("code", "must be 6-8 digits"));
        if (body.target() != null && body.target().length() > 64) return Mono.error(invalid("target", "must be a login target id"));
        // Off the event loop: the login, a JDBC audit store and the reverse lookup block.
        return Mono.fromCallable(() -> broker.issue(body, client(request, hosts)))
                .subscribeOn(Schedulers.boundedElastic())
                .map(session -> ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(session));
    }

    @PostMapping("/session/{correlationId}/revoke")
    public Mono<ResponseEntity<SplunkDtos.Revocation>> revoke(@PathVariable String correlationId, ServerHttpRequest request) {
        if (!correlationId.matches("^[0-9a-f-]{36}$")) return Mono.error(invalid("correlationId", "must be a login's correlation id"));
        return Mono.fromCallable(() -> broker.revoke(correlationId, client(request, hosts)))
                .subscribeOn(Schedulers.boundedElastic())
                .map(r -> ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(r));
    }

    /** Blocks on the reverse lookup (bounded by {@code creed.splunk.client-host.timeout}). */
    static SplunkBroker.Client client(ServerHttpRequest request, ClientHostResolver hosts) {
        String ip = request.getRemoteAddress() == null ? null : request.getRemoteAddress().getAddress().getHostAddress();
        return new SplunkBroker.Client(ip, hosts.resolve(ip), request.getHeaders().getFirst("X-Forwarded-For"),
                request.getHeaders().getFirst(HttpHeaders.USER_AGENT));
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
}
