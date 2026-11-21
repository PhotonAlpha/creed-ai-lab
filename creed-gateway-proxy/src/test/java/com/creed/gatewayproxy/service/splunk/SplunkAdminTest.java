package com.creed.gatewayproxy.service.splunk;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

import com.creed.gatewayproxy.api.dto.SplunkDtos;
import com.creed.gatewayproxy.config.SplunkProperties;
import com.creed.gatewayproxy.config.TotpProperties;
import com.creed.gatewayproxy.service.splunk.audit.MemoryAuditStore;

/** Allowed windows: the issue check, what the page learns, and the token-guarded admin API. */
class SplunkAdminTest {

    private static final String SECRET = "JBSWY3DPEHPK3PXP";
    private static final String TOKEN = "0123456789abcdef0123456789abcdef";
    private static final String BEARER = "Bearer " + TOKEN;
    private static final ZoneId SHANGHAI = ZoneId.of("Asia/Shanghai");
    /** 2026-11-15 21:00 Shanghai: before the range below. */
    private static final Instant BEFORE = ZonedDateTime.of(2026, 11, 15, 21, 0, 0, 0, SHANGHAI).toInstant();
    /** 2026-11-17 12:00 Shanghai: inside it. */
    private static final Instant INSIDE = ZonedDateTime.of(2026, 11, 17, 12, 0, 0, 0, SHANGHAI).toInstant();
    private static final String RANGE = "2026-11-16 20:00:00 - 2026-11-20 00:00:00";
    private static final SplunkBroker.Client CLIENT = new SplunkBroker.Client("10.0.0.7", "pc-7.corp", null, "junit");

    private final MemoryAuditStore store = new MemoryAuditStore();

    private SplunkBroker broker(Instant now, SplunkProperties.TargetAllow sitAllow) {
        return broker(now, sitAllow, "");
    }

    private SplunkBroker broker(Instant now, SplunkProperties.TargetAllow sitAllow, String blockWindows) {
        SplunkProperties splunk = SplunkTestSupport.splunk(false, true, null, blockWindows, List.of(
                SplunkTestSupport.target("UAT", "https://splunk.uat:8000/", null),
                new SplunkProperties.Target("SIT", "SIT", "https://splunk.sit:8000/", "admin", null, null, null, null, false,
                        null, null, sitAllow)));
        MockEnvironment env = new MockEnvironment().withProperty("ENV_MATRIX_TOTP_SECRET", SECRET);
        Clock clock = Clock.fixed(now, ZoneOffset.UTC);
        Totp totp = new Totp(new TotpProperties(60, 6, 1, true, true, 3, Duration.ofSeconds(60)), SECRET, clock::millis);
        return new SplunkBroker(totp, new SplunkLoginClient(splunk), splunk, new SplunkSecrets(env, splunk, q -> null), store,
                new BlockWindows(blockWindows, SHANGHAI), clock);
    }

    private SplunkAdmin admin(SplunkBroker broker, Instant now, String token) {
        SplunkProperties splunk = SplunkTestSupport.splunk(false, true, null, "", List.of(
                SplunkTestSupport.target("UAT", "https://splunk.uat:8000/", null),
                SplunkTestSupport.target("SIT", "https://splunk.sit:8000/", null)));
        return new SplunkAdmin(broker, splunk, token, Clock.fixed(now, ZoneOffset.UTC));
    }

    private static SplunkDtos.SessionRequest req(SplunkBroker broker, String target) {
        return new SplunkDtos.SessionRequest(broker.currentCode().code(), target, null, null, null);
    }

    @Test
    void aTargetWithAllowedWindowsIssuesNothingOutsideThem() {
        SplunkBroker broker = broker(BEFORE, new SplunkProperties.TargetAllow(RANGE));
        assertThatThrownBy(() -> broker.issue(req(broker, "SIT"), CLIENT))
                .isInstanceOfSatisfying(BrokerException.class, e -> {
                    assertThat(e.status()).isEqualTo(403);
                    assertThat(e.error()).isEqualTo("not_available");
                    assertThat(e.getMessage()).contains("available again at 2026-11-16 20:00", "not used")
                            .doesNotContain("2026-11-20");  // the allowed windows themselves are not told
                });
        // the code was not spent, and UAT has no restriction
        assertThat(broker.issue(req(broker, "UAT"), CLIENT).target()).isEqualTo("UAT");

        SplunkDtos.BlockInfo sit = broker.info().targets().get(1).block();
        assertThat(sit.blocked()).isTrue();
        assertThat(sit.reason()).isEqualTo("not_available");
        assertThat(sit.windows()).as("block windows only — never the allowed ones").isEmpty();
        assertThat(sit.changesAtMillis()).isEqualTo(ZonedDateTime.of(2026, 11, 16, 20, 0, 0, 0, SHANGHAI).toInstant().toEpochMilli());
    }

    @Test
    void insideTheAllowedRangeItWorks() {
        SplunkBroker broker = broker(INSIDE, new SplunkProperties.TargetAllow(RANGE));
        assertThat(broker.issue(req(broker, "SIT"), CLIENT).target()).isEqualTo("SIT");
        assertThat(broker.info().targets().get(1).block().changesAtMillis())
                .isEqualTo(ZonedDateTime.of(2026, 11, 20, 0, 0, 0, 0, SHANGHAI).toInstant().toEpochMilli());
    }

    @Test
    void allowedWindowsOverrideTheInheritedBlockWindows() {
        // 2026-11-17 23:00: inside the broker-wide 22:00-09:00, inside SIT's allowed range.
        Instant night = ZonedDateTime.of(2026, 11, 17, 23, 0, 0, 0, SHANGHAI).toInstant();
        SplunkBroker broker = broker(night, new SplunkProperties.TargetAllow(RANGE), "22:00-09:00");
        // UAT, without allowed windows, is still blocked at night (refused before the code is spent)
        assertThatThrownBy(() -> broker.issue(req(broker, "UAT"), CLIENT))
                .isInstanceOfSatisfying(BrokerException.class, e -> assertThat(e.error()).isEqualTo("blocked"));
        assertThat(broker.issue(req(broker, "SIT"), CLIENT).target()).isEqualTo("SIT");
        SplunkDtos.BlockInfo sit = broker.info().targets().get(1).block();
        assertThat(sit.blocked()).isFalse();
        assertThat(sit.windows()).as("block windows do not apply to SIT").isEmpty();
        assertThat(sit.changesAtMillis()).as("only the end of the range")
                .isEqualTo(ZonedDateTime.of(2026, 11, 20, 0, 0, 0, 0, SHANGHAI).toInstant().toEpochMilli());
    }

    @Test
    void theAdminApiReplacesTheWindowsAtOnceAndResetGoesBackToTheConfiguration() {
        SplunkBroker broker = broker(BEFORE, null);
        SplunkAdmin admin = admin(broker, BEFORE, TOKEN);
        assertThat(admin.get("SIT", BEARER, CLIENT)).satisfies(v -> {
            assertThat(v.windows()).isEmpty();
            assertThat(v.source()).isEqualTo("config");
            assertThat(v.available()).isTrue();
        });

        SplunkDtos.Availability set = admin.set("SIT", RANGE, BEARER, CLIENT);
        assertThat(set.windows()).isEqualTo("2026-11-16 20:00 - 2026-11-20 00:00");
        assertThat(set.source()).isEqualTo("api");
        assertThat(set.updatedBy()).isEqualTo("10.0.0.7 (pc-7.corp)");
        assertThat(set.available()).isFalse();
        assertThat(set.reason()).isEqualTo("not_available");
        assertThatThrownBy(() -> broker.issue(req(broker, "SIT"), CLIENT))
                .isInstanceOfSatisfying(BrokerException.class, e -> assertThat(e.error()).isEqualTo("not_available"));

        assertThat(admin.reset("SIT", BEARER, CLIENT).available()).isTrue();
        assertThat(broker.issue(req(broker, "SIT"), CLIENT).target()).isEqualTo("SIT");

        // audited, but not in the page's list
        assertThat(broker.audit(50)).noneMatch(r -> r.eventType().startsWith("ADMIN_"));
        assertThat(store.listAll()).filteredOn(r -> SplunkBroker.ADMIN_EVENT.equals(r.eventType()))
                .extracting(r -> r.detail()).containsExactly(
                        "target SIT allowed: unrestricted (config; was 2026-11-16 20:00 - 2026-11-20 00:00)",
                        "target SIT allowed: 2026-11-16 20:00 - 2026-11-20 00:00 (api; was unrestricted)");
    }

    @Test
    void badWindowsAndUnknownTargetsAreRefused() {
        SplunkBroker broker = broker(BEFORE, null);
        SplunkAdmin admin = admin(broker, BEFORE, TOKEN);
        assertThatThrownBy(() -> admin.set("SIT", "2026-11-20 00:00 - 2026-11-16 20:00", BEARER, CLIENT))
                .isInstanceOfSatisfying(BrokerException.class, e -> {
                    assertThat(e.status()).isEqualTo(400);
                    assertThat(e.getMessage()).contains("ends before it starts").doesNotContain("creed.splunk");
                });
        assertThatThrownBy(() -> admin.set("SIT", null, BEARER, CLIENT))
                .isInstanceOfSatisfying(BrokerException.class, e -> assertThat(e.status()).isEqualTo(400));
        assertThatThrownBy(() -> admin.get("PROD", BEARER, CLIENT))
                .isInstanceOfSatisfying(BrokerException.class, e -> assertThat(e.status()).isEqualTo(404));
    }

    @Test
    void theTokenIsRequiredAndGuessingIsCut() {
        SplunkBroker broker = broker(BEFORE, null);
        assertThatThrownBy(() -> admin(broker, BEFORE, null).get("SIT", BEARER, CLIENT)).as("no token configured: off")
                .isInstanceOfSatisfying(BrokerException.class, e -> assertThat(e.status()).isEqualTo(404));

        SplunkAdmin admin = admin(broker, BEFORE, TOKEN);
        assertThatThrownBy(() -> admin.get("SIT", null, CLIENT))
                .isInstanceOfSatisfying(BrokerException.class, e -> assertThat(e.status()).isEqualTo(401));
        for (int i = 1; i < SplunkAdmin.MAX_FAILURES; i++) {
            assertThatThrownBy(() -> admin.get("SIT", "Bearer wrong", CLIENT))
                    .isInstanceOfSatisfying(BrokerException.class, e -> assertThat(e.status()).isEqualTo(401));
        }
        assertThatThrownBy(() -> admin.get("SIT", BEARER, CLIENT)).as("even the right token, once locked out")
                .isInstanceOfSatisfying(BrokerException.class, e -> {
                    assertThat(e.status()).isEqualTo(429);
                    assertThat(e.headers()).containsKey("Retry-After");
                });
        // another IP is not affected
        assertThat(admin.get("SIT", BEARER, new SplunkBroker.Client("10.0.0.8", null, null)).target()).isEqualTo("SIT");
    }
}
