package com.creed.resource.envmatrix.api;

import com.creed.resource.envmatrix.api.dto.SplunkAuditDto;
import com.creed.resource.envmatrix.api.dto.SplunkSessionDto;
import com.creed.resource.envmatrix.api.dto.SplunkSessionRequest;
import com.creed.resource.envmatrix.api.dto.TotpCodeDto;
import com.creed.resource.envmatrix.api.dto.TotpInfoDto;
import com.creed.resource.envmatrix.service.splunk.SplunkSessionService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * Splunk session broker: {@code TOTP → Splunk login → document.cookie script}.
 *
 * <pre>
 * GET  /splunk/totp          period/digits/server time — drives the countdown
 * GET  /splunk/totp/current  the current code (only with expose-current-code)
 * POST /splunk/session       {code} → 200 session | 401 bad code | 429 locked out | 502 Splunk | 503 unset
 * GET  /splunk/audit?limit=  newest audit rows
 * </pre>
 */
@RestController
@RequestMapping("/api/env-matrix/splunk")
@RequiredArgsConstructor
public class SplunkSessionController {

    private final SplunkSessionService service;

    @GetMapping("/totp")
    public TotpInfoDto totp() {
        return service.info();
    }

    @GetMapping("/totp/current")
    public ResponseEntity<TotpCodeDto> currentCode() {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(service.currentCode());
    }

    /** {@code no-store}: the body carries a live session credential. */
    @PostMapping("/session")
    public ResponseEntity<SplunkSessionDto> session(@Valid @RequestBody SplunkSessionRequest body,
                                                    HttpServletRequest request) {
        var client = new SplunkSessionService.ClientInfo(
                request.getRemoteAddr(), request.getHeader("X-Forwarded-For"), request.getHeader("User-Agent"));
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(service.issue(body.code(), client));
    }

    @GetMapping("/audit")
    public List<SplunkAuditDto> audit(@RequestParam(defaultValue = "50") int limit) {
        return service.audit(limit);
    }
}
