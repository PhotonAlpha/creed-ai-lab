package com.creed.gatewayproxy.service.splunk;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicLong;

import org.junit.jupiter.api.Test;

import com.creed.gatewayproxy.config.TotpProperties;

class TotpTest {

    /** RFC 6238 appendix B: ASCII "12345678901234567890", SHA-1, 8 digits, 30-second steps. */
    private static final String RFC_SECRET = "GEZDGNBVGY3TQOJQGEZDGNBVGY3TQOJQ";

    private static TotpProperties props(int period, int digits) {
        return new TotpProperties(period, digits, 1, true, true, 5, Duration.ofSeconds(60));
    }

    @Test
    void rfc6238AppendixBVectors() {
        AtomicLong now = new AtomicLong();
        Totp totp = new Totp(props(30, 8), RFC_SECRET, now::get);
        long[][] vectors = {{59, 94287082}, {1111111109, 7081804}, {1111111111, 14050471},
                {1234567890, 89005924}, {2000000000, 69279037}, {20000000000L, 65353130}};
        for (long[] v : vectors) {
            now.set(v[0] * 1000);
            assertThat(totp.currentCode()).as("T=%d", v[0]).isEqualTo(String.format("%08d", v[1]));
        }
    }

    @Test
    void defaultsToSixtySecondSteps() {
        TotpProperties defaults = new TotpProperties(null, null, null, null, null, null, null);
        assertThat(defaults.periodSeconds()).isEqualTo(60);
        AtomicLong now = new AtomicLong(119_000);
        Totp totp = new Totp(defaults, RFC_SECRET, now::get);
        assertThat(totp.currentStep()).isEqualTo(1);
        assertThat(totp.secondsRemaining()).isEqualTo(1);
        now.set(120_000);
        assertThat(totp.currentStep()).isEqualTo(2);
        assertThat(totp.secondsRemaining()).isEqualTo(60);
    }

    @Test
    void acceptsDriftRejectsReplayAndMalformed() {
        AtomicLong now = new AtomicLong(1_000_000_000_000L);
        Totp totp = new Totp(props(60, 6), RFC_SECRET, now::get);
        long step = totp.currentStep();
        assertThat(totp.verify("12ab56").reason()).isEqualTo("malformed");
        assertThat(totp.verify("12345").reason()).isEqualTo("malformed");

        Totp.Verification previous = totp.verify(totp.codeAt(step - 1));
        assertThat(previous.valid()).isTrue();
        assertThat(previous.drift()).isEqualTo(-1);
        // A later step is still fine; the same or an older one is a replay.
        assertThat(totp.verify(totp.codeAt(step)).valid()).isTrue();
        assertThat(totp.verify(totp.codeAt(step)).reason()).isEqualTo("replayed");
        assertThat(totp.verify(totp.codeAt(step - 1)).reason()).isEqualTo("replayed");
        assertThat(totp.verify(totp.codeAt(step + 2)).reason()).isEqualTo("invalid_code");
    }

    @Test
    void base32IsTolerantOfCaseSpacesAndPadding() {
        assertThat(Totp.base32Decode("gezd gnbv-gy3t qojq====")).isEqualTo("1234567890".getBytes());
    }
}
