package com.creed.resource.envmatrix.service.splunk;

import com.creed.resource.envmatrix.config.SplunkProperties;
import com.creed.resource.envmatrix.config.TotpProperties;
import com.creed.resource.envmatrix.domain.SplunkAuditEvent;
import com.creed.resource.envmatrix.domain.SplunkAuditRepository;
import com.creed.resource.envmatrix.service.TotpService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.web.client.RestClient;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

class SplunkSessionServiceTest {

    final Clock clock = Clock.fixed(Instant.parse("2026-01-01T00:00:10Z"), ZoneOffset.UTC);
    final TotpService totp = new TotpService(new TotpProperties(
            "GEZDGNBVGY3TQOJQGEZDGNBVGY3TQOJQ", 30, 6, 1, true, true, 5, Duration.ofSeconds(60)), clock);
    final SplunkAuditRepository audit = mock(SplunkAuditRepository.class);

    @Test
    @DisplayName("switch on but no password: 503 before the OTP is checked, so the code is not spent")
    void notConfiguredDoesNotConsumeTheCode() {
        SplunkProperties unconfigured = new SplunkProperties(true, "https://splunk:8000/en-US/account/login",
                "svc", "", "splunkd_8000", "splunkd_8089", "/", true,
                Duration.ofSeconds(1), Duration.ofSeconds(1), "");
        SplunkSessionService service = new SplunkSessionService(totp,
                new RestClientSplunkLoginClient(unconfigured, RestClient.builder(), null), unconfigured, audit, clock);
        String code = totp.currentCode();

        assertThatThrownBy(() -> service.issue(code, new SplunkSessionService.ClientInfo("10.0.0.9", null, "junit")))
                .isInstanceOf(SplunkSessionService.NotConfiguredException.class);

        ArgumentCaptor<SplunkAuditEvent> row = ArgumentCaptor.forClass(SplunkAuditEvent.class);
        verify(audit).save(row.capture());
        assertThat(row.getValue().getEventType()).isEqualTo(SplunkAuditEvent.SPLUNK_LOGIN);
        assertThat(row.getValue().getReason()).isEqualTo("not_configured");
        // The retry that used to come back "replayed" now goes through.
        assertThat(totp.verify(code).valid()).isTrue();
    }
}
