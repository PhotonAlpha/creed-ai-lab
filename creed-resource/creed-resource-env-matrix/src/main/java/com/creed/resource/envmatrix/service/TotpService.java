package com.creed.resource.envmatrix.service;

import com.creed.resource.envmatrix.config.TotpProperties;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.time.Clock;
import java.util.Locale;

/**
 * RFC 6238 TOTP (HMAC-SHA1, the variant every authenticator app implements), hand-written because it
 * is thirty lines and a library would be the module's only reason to carry one.
 *
 * <p>A code is accepted if it matches any step in {@code now ± allowedDriftSteps}. With
 * {@code rejectReplay} on, a matched step must also be later than the last accepted one, so the same
 * code cannot be used twice inside its 90-second life. That memory is per process: two instances
 * behind a load balancer would each accept the same code once.
 */
@Service
@Slf4j
public class TotpService {

    private final TotpProperties properties;
    private final Clock clock;
    private final byte[] key;
    private long lastAcceptedStep = Long.MIN_VALUE;

    public TotpService(TotpProperties properties, Clock clock) {
        this.properties = properties;
        this.clock = clock;
        this.key = properties.configured() ? Base32.decode(properties.secret()) : new byte[0];
        log.info("totp {}", properties);
    }

    public boolean isConfigured() {
        return key.length > 0;
    }

    public TotpProperties properties() {
        return properties;
    }

    public long currentStep() {
        return Math.floorDiv(clock.millis() / 1000, properties.periodSeconds());
    }

    /** Seconds until the current code rolls over, 1..period. */
    public int secondsRemaining() {
        long epochSeconds = clock.millis() / 1000;
        return properties.periodSeconds() - (int) Math.floorMod(epochSeconds, properties.periodSeconds());
    }

    public String currentCode() {
        return codeAt(currentStep());
    }

    /** RFC 6238 §4 / RFC 4226 §5.3: HMAC over the big-endian step, dynamic truncation, mod 10^digits. */
    public String codeAt(long step) {
        requireConfigured();
        try {
            Mac mac = Mac.getInstance("HmacSHA1");
            mac.init(new SecretKeySpec(key, "HmacSHA1"));
            byte[] hash = mac.doFinal(ByteBuffer.allocate(8).putLong(step).array());
            int offset = hash[hash.length - 1] & 0x0f;
            int binary = ((hash[offset] & 0x7f) << 24)
                    | ((hash[offset + 1] & 0xff) << 16)
                    | ((hash[offset + 2] & 0xff) << 8)
                    | (hash[offset + 3] & 0xff);
            int otp = binary % (int) Math.pow(10, properties.digits());
            return String.format(Locale.ROOT, "%0" + properties.digits() + "d", otp);
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("HmacSHA1 unavailable", e);
        }
    }

    /**
     * Checks a submitted code. Synchronized because accepting a step and recording it as used must be
     * one step — otherwise two concurrent requests with the same code could both get through.
     */
    public synchronized Verification verify(String submitted) {
        requireConfigured();
        String code = submitted == null ? "" : submitted.replaceAll("\\s", "");
        long now = currentStep();
        if (code.length() != properties.digits() || !code.chars().allMatch(Character::isDigit)) {
            return Verification.rejected("malformed", now);
        }

        Long matched = null;
        byte[] candidate = code.getBytes(StandardCharsets.US_ASCII);
        // Every step in the window is computed and compared in constant time, matched or not, so
        // response timing does not say how close a guess was.
        for (int drift = -properties.allowedDriftSteps(); drift <= properties.allowedDriftSteps(); drift++) {
            byte[] expected = codeAt(now + drift).getBytes(StandardCharsets.US_ASCII);
            if (MessageDigest.isEqual(expected, candidate) && matched == null) {
                matched = now + drift;
            }
        }
        if (matched == null) {
            return Verification.rejected("invalid_code", now);
        }
        if (properties.rejectReplay() && matched <= lastAcceptedStep) {
            return new Verification(false, "replayed", now, matched);
        }
        lastAcceptedStep = matched;
        return new Verification(true, null, now, matched);
    }

    private void requireConfigured() {
        if (!isConfigured()) {
            throw new IllegalStateException("env-matrix.totp.secret is not set");
        }
    }

    /**
     * @param reason      {@code null} when valid; otherwise {@code malformed} / {@code invalid_code} /
     *                    {@code replayed} — recorded in the audit trail verbatim
     * @param serverStep  the verifier's current step, so the audit shows the clock it judged against
     * @param matchedStep the step the code belonged to, if any
     */
    public record Verification(boolean valid, String reason, long serverStep, Long matchedStep) {
        static Verification rejected(String reason, long serverStep) {
            return new Verification(false, reason, serverStep, null);
        }

        public Integer drift() {
            return matchedStep == null ? null : (int) (matchedStep - serverStep);
        }
    }

    /** RFC 4648 Base32, tolerant of lower case, spaces and '=' padding as authenticator exports vary. */
    static final class Base32 {
        private static final String ALPHABET = "ABCDEFGHIJKLMNOPQRSTUVWXYZ234567";

        private Base32() {
        }

        static byte[] decode(String input) {
            String clean = input.replaceAll("[\\s=-]", "").toUpperCase(Locale.ROOT);
            ByteBuffer out = ByteBuffer.allocate(clean.length() * 5 / 8);
            int buffer = 0;
            int bits = 0;
            for (char c : clean.toCharArray()) {
                int value = ALPHABET.indexOf(c);
                if (value < 0) {
                    throw new IllegalArgumentException("env-matrix.totp.secret is not valid Base32");
                }
                buffer = (buffer << 5) | value;
                bits += 5;
                if (bits >= 8) {
                    out.put((byte) (buffer >> (bits - 8)));
                    bits -= 8;
                }
            }
            byte[] bytes = new byte[out.position()];
            out.flip().get(bytes);
            return bytes;
        }
    }
}
