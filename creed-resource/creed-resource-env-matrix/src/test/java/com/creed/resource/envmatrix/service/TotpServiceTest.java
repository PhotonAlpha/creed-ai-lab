package com.creed.resource.envmatrix.service;

import com.creed.resource.envmatrix.config.TotpProperties;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;

import static org.assertj.core.api.Assertions.assertThat;

class TotpServiceTest {

    /** RFC 6238 appendix B's SHA-1 key, "12345678901234567890", in Base32. */
    static final String RFC_SECRET = "GEZDGNBVGY3TQOJQGEZDGNBVGY3TQOJQ";

    static TotpService at(long epochSeconds, int digits, boolean rejectReplay) {
        var properties = new TotpProperties(RFC_SECRET, 30, digits, 1, rejectReplay, true, 5, Duration.ofSeconds(60));
        return new TotpService(properties, Clock.fixed(Instant.ofEpochSecond(epochSeconds), ZoneOffset.UTC));
    }

    @DisplayName("matches the RFC 6238 appendix B SHA-1 test vectors")
    @ParameterizedTest
    @CsvSource({
            "59,          94287082",
            "1111111109,  07081804",
            "1111111111,  14050471",
            "1234567890,  89005924",
            "2000000000,  69279037",
            "20000000000, 65353130",
    })
    void rfcVectors(long epochSeconds, String expected) {
        assertThat(at(epochSeconds, 8, false).currentCode()).isEqualTo(expected);
    }

    @Test
    @DisplayName("6 digits is the 8-digit value's last six")
    void sixDigits() {
        assertThat(at(59, 6, false).currentCode()).isEqualTo("287082");
    }

    @Test
    @DisplayName("accepts one step either side of now, and no further")
    void driftWindow() {
        TotpService totp = at(1_111_111_111, 6, false);
        long now = totp.currentStep();

        assertThat(totp.verify(totp.codeAt(now - 1)).valid()).isTrue();
        assertThat(totp.verify(totp.codeAt(now)).valid()).isTrue();
        assertThat(totp.verify(totp.codeAt(now + 1)).drift()).isEqualTo(1);
        assertThat(totp.verify(totp.codeAt(now - 2)).reason()).isEqualTo("invalid_code");
        assertThat(totp.verify(totp.codeAt(now + 2)).reason()).isEqualTo("invalid_code");
    }

    @Test
    @DisplayName("a code is accepted once, and so is anything from an earlier step")
    void replay() {
        TotpService totp = at(1_111_111_111, 6, true);
        long now = totp.currentStep();

        assertThat(totp.verify(totp.codeAt(now)).valid()).isTrue();
        assertThat(totp.verify(totp.codeAt(now)).reason()).isEqualTo("replayed");
        assertThat(totp.verify(totp.codeAt(now - 1)).reason()).isEqualTo("replayed");
        assertThat(totp.verify(totp.codeAt(now + 1)).valid()).isTrue();
    }

    @Test
    @DisplayName("rejects wrong length and non-digits before comparing anything")
    void malformed() {
        TotpService totp = at(59, 6, false);
        assertThat(totp.verify("12345").reason()).isEqualTo("malformed");
        assertThat(totp.verify("12a456").reason()).isEqualTo("malformed");
        assertThat(totp.verify(null).reason()).isEqualTo("malformed");
        assertThat(totp.verify(" 287 082 ").valid()).isTrue();
    }

    @Test
    @DisplayName("seconds remaining counts down to the step boundary")
    void secondsRemaining() {
        assertThat(at(60, 6, false).secondsRemaining()).isEqualTo(30);
        assertThat(at(89, 6, false).secondsRemaining()).isEqualTo(1);
    }
}
