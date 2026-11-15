package com.creed.gatewayproxy.api;

import java.util.List;

import lombok.RequiredArgsConstructor;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import com.creed.gatewayproxy.api.dto.SplunkDtos;
import com.creed.gatewayproxy.service.splunk.ClientHostResolver;
import com.creed.gatewayproxy.service.splunk.SplunkAdmin;

/**
 * When each Splunk target may issue sessions — runtime changes, not on the page. Every call needs
 * {@code Authorization: Bearer <SPLUNK_ADMIN_TOKEN>}; without that token configured these paths are 404.
 *
 * <pre>
 * GET    /api/env-matrix/splunk/admin/targets                       every target's allowed windows
 * GET    /api/env-matrix/splunk/admin/targets/{id}/availability     one target's
 * PUT    /api/env-matrix/splunk/admin/targets/{id}/availability     {"windows": "2026-11-16 20:00:00 - 2026-11-20 00:00:00"}
 *                                                                   ("" = unrestricted); takes effect at once
 * DELETE /api/env-matrix/splunk/admin/targets/{id}/availability     back to targets[].allow from the configuration
 *        → 200 | 400 bad windows | 401 | 404 off / unknown target | 429 too many wrong tokens
 * </pre>
 *
 * Changes live in memory: a restart goes back to the configuration.
 */
@RestController
@RequestMapping("/api/env-matrix/splunk/admin/targets")
@RequiredArgsConstructor
public class SplunkAdminController {

    private final SplunkAdmin admin;
    private final ClientHostResolver hosts;

    @GetMapping
    public Mono<ResponseEntity<List<SplunkDtos.Availability>>> list(
            @RequestHeader(value = HttpHeaders.AUTHORIZATION, required = false) String authorization, ServerHttpRequest request) {
        return blocking(() -> admin.list(authorization, SplunkController.client(request, hosts)));
    }

    @GetMapping("/{id}/availability")
    public Mono<ResponseEntity<SplunkDtos.Availability>> get(@PathVariable String id,
            @RequestHeader(value = HttpHeaders.AUTHORIZATION, required = false) String authorization, ServerHttpRequest request) {
        return blocking(() -> admin.get(id, authorization, SplunkController.client(request, hosts)));
    }

    @PutMapping("/{id}/availability")
    public Mono<ResponseEntity<SplunkDtos.Availability>> set(@PathVariable String id,
            @RequestBody SplunkDtos.AvailabilityUpdate body,
            @RequestHeader(value = HttpHeaders.AUTHORIZATION, required = false) String authorization, ServerHttpRequest request) {
        return blocking(() -> admin.set(id, body.windows(), authorization, SplunkController.client(request, hosts)));
    }

    @DeleteMapping("/{id}/availability")
    public Mono<ResponseEntity<SplunkDtos.Availability>> reset(@PathVariable String id,
            @RequestHeader(value = HttpHeaders.AUTHORIZATION, required = false) String authorization, ServerHttpRequest request) {
        return blocking(() -> admin.reset(id, authorization, SplunkController.client(request, hosts)));
    }

    /** Off the event loop: the reverse lookup and a JDBC audit store block. */
    private static <T> Mono<ResponseEntity<T>> blocking(java.util.concurrent.Callable<T> call) {
        return Mono.fromCallable(call).subscribeOn(Schedulers.boundedElastic())
                .map(body -> ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(body));
    }
}
