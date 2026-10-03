package com.creed.resource.envmatrix.api;

import com.creed.resource.envmatrix.domain.SplunkAuditRepository;
import com.creed.resource.envmatrix.service.TotpService;
import com.jayway.jsonpath.JsonPath;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.startsWith;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Full broker flow with {@code env-matrix.splunk.enabled=false} (mock value) — no network. */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class SplunkSessionControllerTest {

    /**
     * Time is a bean so every test can start on a fresh step: replay protection remembers the last
     * accepted step for the life of the (shared) context, so a fixed clock would make test order matter.
     */
    static class SteppingClock extends Clock {
        Instant now = Instant.parse("2026-01-01T00:00:10Z");

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now;
        }
    }

    @TestConfiguration
    static class ClockConfig {
        @Bean
        @Primary
        SteppingClock steppingClock() {
            return new SteppingClock();
        }
    }

    @Autowired
    MockMvc mockMvc;
    @Autowired
    TotpService totp;
    @Autowired
    SteppingClock clock;
    @Autowired
    SplunkAuditRepository auditRepository;

    @BeforeEach
    void nextStep() {
        clock.now = clock.now.plus(Duration.ofMinutes(5));
    }

    ResultActions submit(String code, String ip) throws Exception {
        return mockMvc.perform(post("/api/env-matrix/splunk/session")
                .with(request -> {
                    request.setRemoteAddr(ip);
                    return request;
                })
                .header("User-Agent", "junit")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"code\":\"" + code + "\"}"));
    }

    String wrongCode() {
        String right = totp.currentCode();
        return right.equals("000000") ? "111111" : "000000";
    }

    @Test
    @DisplayName("GET /totp describes the countdown and the mock Splunk")
    void info() throws Exception {
        mockMvc.perform(get("/api/env-matrix/splunk/totp"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.configured").value(true))
                .andExpect(jsonPath("$.periodSeconds").value(30))
                .andExpect(jsonPath("$.codeVisible").value(true))
                .andExpect(jsonPath("$.splunkMode").value("mock"))
                .andExpect(jsonPath("$.serverTimeMillis").value(clock.millis()));
    }

    @Test
    @DisplayName("GET /totp/current serves the code the verifier expects, uncached")
    void currentCode() throws Exception {
        mockMvc.perform(get("/api/env-matrix/splunk/totp/current"))
                .andExpect(status().isOk())
                .andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(jsonPath("$.code").value(totp.currentCode()))
                .andExpect(jsonPath("$.secondsRemaining").value(totp.secondsRemaining()));
    }

    @Test
    @DisplayName("a valid code returns the cookie script and writes two linked audit rows")
    void issue() throws Exception {
        String body = submit(totp.currentCode(), "10.0.0.1")
                .andExpect(status().isOk())
                .andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(jsonPath("$.sourceCookie").value("splunkd_8000"))
                .andExpect(jsonPath("$.cookieName").value("splunkd_8089"))
                .andExpect(jsonPath("$.cookieValue", startsWith("mock-")))
                .andExpect(jsonPath("$.script", startsWith("document.cookie = \"splunkd_8089=mock-")))
                .andExpect(jsonPath("$.script", containsString("; path=/; Secure; SameSite=Lax\";")))
                .andExpect(jsonPath("$.mode").value("mock"))
                .andReturn().getResponse().getContentAsString();

        String correlationId = JsonPath.read(body, "$.correlationId");
        String cookie = JsonPath.read(body, "$.cookieValue");
        var rows = auditRepository.findByCorrelationIdOrderByIdAsc(correlationId);
        assertThat(rows).extracting("eventType").containsExactly("OTP_VERIFY", "SPLUNK_LOGIN");
        assertThat(rows).extracting("outcome").containsOnly("SUCCESS");
        assertThat(rows).extracting("clientIp").containsOnly("10.0.0.1");
        assertThat(rows.get(1).getCookieFingerprint()).hasSize(16);
        // The audit trail must never hold the credential itself.
        assertThat(rows).allSatisfy(row -> assertThat(String.valueOf(row.getDetail())).doesNotContain(cookie));
    }

    @Test
    @DisplayName("the same code twice is 401 otp_replayed, and audited")
    void replay() throws Exception {
        String code = totp.currentCode();
        submit(code, "10.0.0.2").andExpect(status().isOk());
        submit(code, "10.0.0.2")
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error").value("otp_replayed"));
    }

    @Test
    @DisplayName("a wrong code is 401 and audited as a failed OTP_VERIFY with no Splunk call")
    void rejectsWrongCode() throws Exception {
        long before = auditRepository.count();
        submit(wrongCode(), "10.0.0.3")
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error").value("otp_invalid"));

        assertThat(auditRepository.count()).isEqualTo(before + 1);
        mockMvc.perform(get("/api/env-matrix/splunk/audit").param("limit", "1"))
                .andExpect(jsonPath("$[0].eventType").value("OTP_VERIFY"))
                .andExpect(jsonPath("$[0].outcome").value("FAILURE"))
                .andExpect(jsonPath("$[0].reason").value("invalid_code"));
    }

    @Test
    @DisplayName("max-failures wrong codes lock that address out with 429, even for a right code")
    void lockout() throws Exception {
        for (int i = 0; i < 3; i++) {
            submit(wrongCode(), "10.0.0.4").andExpect(status().isUnauthorized());
        }
        submit(totp.currentCode(), "10.0.0.4")
                .andExpect(status().isTooManyRequests())
                .andExpect(header().string("Retry-After", "60"));
        // Another address is unaffected.
        submit(totp.currentCode(), "10.0.0.5").andExpect(status().isOk());
    }

    @Test
    @DisplayName("a non-numeric code is 400 before it reaches the verifier")
    void validation() throws Exception {
        submit("abc", "10.0.0.6").andExpect(status().isBadRequest());
    }
}
