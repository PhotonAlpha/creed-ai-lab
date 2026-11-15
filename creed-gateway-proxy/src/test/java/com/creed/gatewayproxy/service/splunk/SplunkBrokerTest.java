package com.creed.gatewayproxy.service.splunk;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

import com.creed.gatewayproxy.api.dto.SplunkDtos;
import com.creed.gatewayproxy.config.SplunkProperties;
import com.creed.gatewayproxy.config.TotpProperties;
import com.creed.gatewayproxy.service.splunk.audit.MemoryAuditStore;
import com.creed.gatewayproxy.service.splunk.audit.SplunkAuditRow;

class SplunkBrokerTest {

    private static final String SECRET = "JBSWY3DPEHPK3PXP";
    /** 2026-10-09 12:00 in Asia/Shanghai — outside 22:00-09:00. */
    private static final Instant NOON = Instant.parse("2026-10-09T04:00:00Z");
    /** 23:00 in Asia/Shanghai — inside it. */
    private static final Instant NIGHT = Instant.parse("2026-10-09T15:00:00Z");

    private static final SplunkBroker.Client CLIENT = new SplunkBroker.Client("10.0.0.7", null, "junit");

    private final MemoryAuditStore store = new MemoryAuditStore();

    private SplunkBroker broker(Instant now, SplunkProperties splunk, SplunkLoginClient loginClient, MockEnvironment env) {
        env.setProperty("ENV_MATRIX_TOTP_SECRET", SECRET);
        Clock clock = Clock.fixed(now, ZoneOffset.UTC);
        Totp totp = new Totp(new TotpProperties(60, 6, 1, true, true, 3, Duration.ofSeconds(60)), SECRET, clock::millis);
        SplunkSecrets secrets = new SplunkSecrets(env, splunk, q -> null);
        return new SplunkBroker(totp, loginClient, splunk, secrets, store,
                new BlockWindows(splunk.block().windows(), java.time.ZoneId.of(splunk.block().zone())), clock);
    }

    private SplunkBroker mockBroker(Instant now, String loginUrl) {
        SplunkProperties splunk = SplunkTestSupport.splunk(false, true, null, "22:00-09:00",
                List.of(SplunkTestSupport.target("UAT", loginUrl, "relay.example:3000")));
        return broker(now, splunk, new SplunkLoginClient(splunk), new MockEnvironment());
    }

    private static String code(SplunkBroker broker) {
        return broker.currentCode().code();
    }

    private static SplunkDtos.SessionRequest req(String code, String target, Boolean viaTunnel) {
        return new SplunkDtos.SessionRequest(code, target, null, null, viaTunnel);
    }

    private static SplunkAuditRow loginRow(List<SplunkAuditRow> rows, String correlationId) {
        return rows.stream().filter(r -> r.correlationId().equals(correlationId) && "SPLUNK_LOGIN".equals(r.eventType()))
                .findFirst().orElseThrow();
    }

    @Test
    void revokeLogsOutOnceAuditsItAndOffersAClearScript() {
        SplunkBroker broker = mockBroker(NOON, "https://splunk.example:8000/en-US/account/login");
        SplunkDtos.Session session = broker.issue(req(code(broker), null, false), CLIENT);
        assertThat(loginRow(broker.audit(50), session.correlationId()).revocable()).isTrue();

        SplunkDtos.Revocation r = broker.revoke(session.correlationId(), CLIENT);
        assertThat(r.cleared()).isTrue();
        assertThat(r.clearScript()).isEqualTo(
                "document.cookie = \"splunkd_8089=; path=/; expires=Thu, 01 Jan 1970 00:00:00 GMT; Secure; SameSite=Lax\";");
        List<SplunkAuditRow> rows = broker.audit(50);
        assertThat(rows.getFirst().eventType()).isEqualTo("SPLUNK_LOGOUT");
        assertThat(rows.getFirst().outcome()).isEqualTo("SUCCESS");
        assertThat(rows.getFirst().cookieFingerprint()).isEqualTo(session.cookieFingerprint());
        assertThat(rows.getFirst().revocable()).isNull();
        assertThat(loginRow(rows, session.correlationId()).revocable()).isFalse();

        assertThatThrownBy(() -> broker.revoke(session.correlationId(), CLIENT))
                .isInstanceOfSatisfying(BrokerException.class, e -> assertThat(e.status()).isEqualTo(404));
    }

    @Test
    void failedLogoutKeepsTheSessionForARetry() {
        SplunkProperties splunk = SplunkTestSupport.splunk(false, true, null, "",
                List.of(SplunkTestSupport.target("UAT", "http://splunk.example:8000/en-US/account/login", null)));
        int[] calls = {0};
        SplunkLoginClient flaky = new SplunkLoginClient(splunk) {
            @Override
            public LogoutResult logout(SplunkProperties.Target t, java.util.Map<String, String> c, String s, boolean v) {
                if (calls[0]++ == 0) throw new SplunkLoginException("io_error", 0, "connection refused", null);
                return new LogoutResult(303, true);
            }
        };
        SplunkBroker broker = broker(NOON, splunk, flaky, new MockEnvironment());
        SplunkDtos.Session session = broker.issue(req(code(broker), null, false), CLIENT);
        assertThatThrownBy(() -> broker.revoke(session.correlationId(), CLIENT))
                .isInstanceOfSatisfying(BrokerException.class, e -> assertThat(e.status()).isEqualTo(502));
        assertThat(loginRow(broker.audit(50), session.correlationId()).revocable()).isTrue();
        assertThat(broker.revoke(session.correlationId(), CLIENT).clearScript()).doesNotContain("Secure");
        assertThat(broker.audit(50)).filteredOn(r -> "SPLUNK_LOGOUT".equals(r.eventType()))
                .extracting(SplunkAuditRow::outcome).containsExactly("SUCCESS", "FAILURE");
    }

    @Test
    void httpsLoginUrlGetsASecureCookieHttpDoesNot() {
        SplunkBroker https = mockBroker(NOON, "https://splunk.uat:8000/en-US/account/login");
        SplunkDtos.Session s = https.issue(req(code(https), null, null), CLIENT);
        assertThat(s.secure()).isTrue();
        assertThat(s.script()).startsWith("document.cookie = \"splunkd_8089=mock-").contains("; path=/; Secure; SameSite=Lax\";");

        SplunkBroker http = mockBroker(NOON, "http://splunk.uat:8000/en-US/account/login");
        SplunkDtos.Session plain = http.issue(req(code(http), null, null), CLIENT);
        assertThat(plain.secure()).isFalse();
        assertThat(plain.script()).doesNotContain("Secure").endsWith("; path=/; SameSite=Lax\";");
    }

    @Test
    void scriptEscapesQuotesAndBackslashes() {
        assertThat(SplunkBroker.script("n", "a\"b\\c\n", "/", false)).isEqualTo("document.cookie = \"n=a\\\"b\\\\c; path=/; SameSite=Lax\";");
    }

    @Test
    void blockWindowRefusesBeforeTheCodeIsSpent() {
        SplunkBroker broker = mockBroker(NIGHT, "https://splunk.uat:8000/");
        String code = code(broker);
        assertThatThrownBy(() -> broker.issue(req(code, null, null), CLIENT))
                .isInstanceOfSatisfying(BrokerException.class, e -> {
                    assertThat(e.status()).isEqualTo(403);
                    assertThat(e.error()).isEqualTo("blocked");
                    assertThat(e.getMessage()).contains("22:00-09:00", "Asia/Shanghai", "09:00", "not used");
                    assertThat(e.headers()).containsEntry("Retry-After", String.valueOf(10 * 3600));
                });
        assertThat(store.list(10)).singleElement().satisfies(row -> {
            assertThat(row.eventType()).isEqualTo("SPLUNK_LOGIN");
            assertThat(row.reason()).isEqualTo("blocked");
        });
        assertThat(broker.info().block()).satisfies(b -> {
            assertThat(b.blocked()).isTrue();
            assertThat(b.windows()).containsExactly("22:00-09:00");
            assertThat(b.changesAtMillis()).isEqualTo(Instant.parse("2026-10-10T01:00:00Z").toEpochMilli());
        });
    }

    @Test
    void eachTargetIsBlockedByItsOwnWindows() {
        // UAT: the broker-wide 22:00-09:00. SIT: its own 12:00-13:00, zone inherited. DEV: never blocked.
        SplunkProperties splunk = SplunkTestSupport.splunk(false, true, null, "22:00-09:00", List.of(
                SplunkTestSupport.target("UAT", "https://splunk.uat:8000/", null),
                SplunkTestSupport.target("SIT", "https://splunk.sit:8000/", null, null, new SplunkProperties.TargetBlock("12:00-13:00", null)),
                SplunkTestSupport.target("DEV", "https://splunk.dev:8000/", null, null, new SplunkProperties.TargetBlock("", null))));
        SplunkBroker broker = broker(NOON, splunk, new SplunkLoginClient(splunk), new MockEnvironment());

        assertThatThrownBy(() -> broker.issue(req(code(broker), "SIT", null), CLIENT))
                .isInstanceOfSatisfying(BrokerException.class, e -> {
                    assertThat(e.error()).isEqualTo("blocked");
                    assertThat(e.getMessage()).contains("SIT label", "12:00-13:00", "Asia/Shanghai", "13:00");
                    assertThat(e.headers()).containsEntry("Retry-After", String.valueOf(3600));
                });
        assertThat(store.list(1).getFirst().detail()).startsWith("target SIT: inside block window 12:00-13:00");
        // the code was not spent on SIT, and noon is outside UAT's windows
        assertThat(broker.issue(req(code(broker), "UAT", null), CLIENT).target()).isEqualTo("UAT");

        assertThat(broker.info().block().windows()).as("broker-wide").containsExactly("22:00-09:00");
        assertThat(broker.info().targets()).extracting(t -> t.id() + "=" + t.block().windows() + "/" + t.block().blocked())
                .containsExactly("UAT=[22:00-09:00]/false", "SIT=[12:00-13:00]/true", "DEV=[]/false");
    }

    @Test
    void theClientsHostIsAuditedAndEchoed() {
        SplunkBroker broker = mockBroker(NOON, "https://splunk.uat:8000/");
        SplunkBroker.Client client = new SplunkBroker.Client("10.0.0.7", "pc-0042.corp.example", null, "junit");
        broker.issue(req(code(broker), null, null), client);
        assertThat(store.list(10)).extracting(SplunkAuditRow::clientHost).containsOnly("pc-0042.corp.example");
        assertThat(broker.info(client).client()).isEqualTo(new SplunkDtos.ClientInfo("10.0.0.7", "pc-0042.corp.example"));
        assertThat(broker.info().client()).isNull();
    }

    @Test
    void refusalsBeforeTheOtpLeaveTheCodeUsable() {
        SplunkBroker broker = mockBroker(NOON, "https://splunk.uat:8000/");
        String code = code(broker);
        assertThatThrownBy(() -> broker.issue(req(code, "NOPE", null), CLIENT))
                .isInstanceOfSatisfying(BrokerException.class, e -> assertThat(e.error()).isEqualTo("unknown_target"));
        assertThatThrownBy(() -> broker.issue(new SplunkDtos.SessionRequest(code, null, "bad name", null, null), CLIENT))
                .isInstanceOfSatisfying(BrokerException.class, e -> assertThat(e.error()).isEqualTo("validation_failed"));
        // still unused: it works now
        SplunkDtos.Session s = broker.issue(req(code, "UAT", true), CLIENT);
        assertThat(s.tunnel()).isEqualTo("relay.example:3000");
        assertThatThrownBy(() -> broker.issue(req(code, "UAT", null), CLIENT))
                .isInstanceOfSatisfying(BrokerException.class, e -> assertThat(e.error()).isEqualTo("otp_replayed"));
    }

    @Test
    void realModeWithoutAPasswordIs503AndTheCodeIsKept() {
        SplunkProperties splunk = SplunkTestSupport.splunk(true, true, null, "",
                List.of(SplunkTestSupport.target("UAT", "https://splunk.uat:8000/", null)));
        SplunkBroker broker = broker(NOON, splunk, new SplunkLoginClient(splunk), new MockEnvironment());
        assertThat(broker.info().targets().getFirst().configured()).isFalse();
        assertThatThrownBy(() -> broker.issue(req(code(broker), null, null), CLIENT))
                .isInstanceOfSatisfying(BrokerException.class, e -> {
                    assertThat(e.status()).isEqualTo(503);
                    assertThat(e.getMessage()).contains("not used");
                });
        assertThat(store.list(10).getFirst().detail()).contains("SPLUNK_TARGET_UAT_PASSWORD");
    }

    @Test
    void splunkFailureIs502AndKeepsTheOtpRow() {
        SplunkProperties splunk = SplunkTestSupport.splunk(true, true, null, "",
                List.of(SplunkTestSupport.target("UAT", "https://splunk.uat:8000/", null)));
        SplunkLoginClient failing = new SplunkLoginClient(splunk) {
            @Override
            public LoginResult login(SplunkProperties.Target target, String password, String sessionCookie, boolean viaTunnel) {
                assertThat(password).isEqualTo("s3cret");
                throw new SplunkLoginException("no_session_cookie", 401, "Splunk answered 401 without a splunkd_8000 cookie", null);
            }
        };
        MockEnvironment env = new MockEnvironment().withProperty("SPLUNK_TARGET_UAT_PASSWORD", "s3cret");
        SplunkBroker broker = broker(NOON, splunk, failing, env);
        assertThatThrownBy(() -> broker.issue(req(code(broker), null, null), CLIENT))
                .isInstanceOfSatisfying(BrokerException.class, e -> {
                    assertThat(e.status()).isEqualTo(502);
                    assertThat(e.error()).isEqualTo("splunk_no_session_cookie");
                });
        List<SplunkAuditRow> rows = store.list(10);
        assertThat(rows).extracting(SplunkAuditRow::eventType, SplunkAuditRow::outcome)
                .containsExactly(org.assertj.core.groups.Tuple.tuple("SPLUNK_LOGIN", "FAILURE"),
                        org.assertj.core.groups.Tuple.tuple("OTP_VERIFY", "SUCCESS"));
        assertThat(rows.getFirst().httpStatus()).isEqualTo(401);
    }

    @Test
    void lockoutAfterMaxFailures() {
        SplunkBroker broker = mockBroker(NOON, "https://splunk.uat:8000/");
        for (int i = 0; i < 3; i++) {
            assertThatThrownBy(() -> broker.issue(req("000000", null, null), CLIENT))
                    .isInstanceOfSatisfying(BrokerException.class, e -> assertThat(e.status()).isEqualTo(401));
        }
        assertThatThrownBy(() -> broker.issue(req(code(broker), null, null), CLIENT))
                .isInstanceOfSatisfying(BrokerException.class, e -> {
                    assertThat(e.status()).isEqualTo(429);
                    assertThat(e.headers()).containsEntry("Retry-After", "60");
                });
    }

    @Test
    void auditWriteFailureFailsTheRequest() {
        SplunkProperties splunk = SplunkTestSupport.splunk(false, true, null, "",
                List.of(SplunkTestSupport.target("UAT", "https://splunk.uat:8000/", null)));
        MockEnvironment env = new MockEnvironment();
        Clock clock = Clock.fixed(NOON, ZoneOffset.UTC);
        Totp totp = new Totp(new TotpProperties(60, 6, 1, true, true, 3, Duration.ofSeconds(60)), SECRET, clock::millis);
        SplunkBroker broker = new SplunkBroker(totp, new SplunkLoginClient(splunk), splunk, new SplunkSecrets(env, splunk, q -> null),
                new MemoryAuditStore() {
                    @Override
                    public synchronized void save(SplunkAuditRow row) {
                        throw new IllegalStateException("database down");
                    }
                }, new BlockWindows("", java.time.ZoneId.of("UTC")), clock);
        assertThatThrownBy(() -> broker.issue(req(totp.currentCode(), null, null), CLIENT)).hasMessage("database down");
    }
}
