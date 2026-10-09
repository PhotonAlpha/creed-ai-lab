package com.creed.gatewayproxy.service.splunk;

import java.nio.ByteBuffer;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.util.Locale;
import java.util.function.LongSupplier;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

import com.creed.gatewayproxy.config.TotpProperties;

/**
 * RFC 6238 TOTP, HMAC-SHA1 — a port of the Node broker's totp.js.
 *
 * A code is accepted if it matches any step in {@code now ± allowedDriftSteps}. With
 * {@code rejectReplay} a matched step must also be later than the last accepted one, so a code works
 * once. That memory is per process: two instances behind a load balancer would each accept it once.
 */
public class Totp {

    /** What a verification found; {@code reason} is malformed / invalid_code / replayed, audited verbatim. */
    public record Verification(boolean valid, String reason, long serverStep, Long matchedStep) {
        public Long drift() {
            return matchedStep == null ? null : matchedStep - serverStep;
        }
    }

    private final TotpProperties config;
    private final byte[] key;
    private final LongSupplier clock;
    private long lastAcceptedStep = Long.MIN_VALUE;

    /** @param clock epoch millis — TOTP is nothing but a function of it, and tests pin it */
    public Totp(TotpProperties config, String base32Secret, LongSupplier clock) {
        this.config = config;
        this.key = base32Secret == null || base32Secret.isBlank() ? new byte[0] : base32Decode(base32Secret);
        this.clock = clock;
    }

    public TotpProperties config() {
        return config;
    }

    public boolean configured() {
        return key.length > 0;
    }

    public long currentStep() {
        return Math.floorDiv(clock.getAsLong() / 1000, config.periodSeconds());
    }

    /** Seconds until the current code rolls over, 1..period. */
    public int secondsRemaining() {
        long epochSeconds = clock.getAsLong() / 1000;
        return (int) (config.periodSeconds() - epochSeconds % config.periodSeconds());
    }

    public String currentCode() {
        return codeAt(currentStep());
    }

    /** RFC 6238 §4 / RFC 4226 §5.3: HMAC over the big-endian step, dynamic truncation, mod 10^digits. */
    public String codeAt(long step) {
        if (!configured()) throw new IllegalStateException("TOTP secret is not set");
        byte[] hash;
        try {
            Mac mac = Mac.getInstance("HmacSHA1");
            mac.init(new SecretKeySpec(key, "HmacSHA1"));
            hash = mac.doFinal(ByteBuffer.allocate(8).putLong(step).array());
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException(e);
        }
        int offset = hash[hash.length - 1] & 0x0f;
        int binary = ((hash[offset] & 0x7f) << 24) | ((hash[offset + 1] & 0xff) << 16)
                | ((hash[offset + 2] & 0xff) << 8) | (hash[offset + 3] & 0xff);
        int mod = (int) Math.pow(10, config.digits());
        return String.format(Locale.ROOT, "%0" + config.digits() + "d", binary % mod);
    }

    /**
     * Checks a submitted code. {@code synchronized}: accepting a step and recording it as used must
     * be one atomic step, or two concurrent requests with the same code could both get through.
     */
    public synchronized Verification verify(String submitted) {
        String code = submitted == null ? "" : submitted.replaceAll("\\s", "");
        long serverStep = currentStep();
        if (code.length() != config.digits() || !code.chars().allMatch(Character::isDigit)) {
            return new Verification(false, "malformed", serverStep, null);
        }
        // Every step in the window is compared in constant time, matched or not, so response timing
        // does not say how close a guess was.
        byte[] candidate = code.getBytes(java.nio.charset.StandardCharsets.US_ASCII);
        Long matched = null;
        for (int drift = -config.allowedDriftSteps(); drift <= config.allowedDriftSteps(); drift++) {
            byte[] expected = codeAt(serverStep + drift).getBytes(java.nio.charset.StandardCharsets.US_ASCII);
            if (MessageDigest.isEqual(expected, candidate) && matched == null) matched = serverStep + drift;
        }
        if (matched == null) return new Verification(false, "invalid_code", serverStep, null);
        if (config.rejectReplay() && matched <= lastAcceptedStep) return new Verification(false, "replayed", serverStep, matched);
        lastAcceptedStep = matched;
        return new Verification(true, null, serverStep, matched);
    }

    /** RFC 4648 Base32, tolerant of lower case, spaces, dashes and '=' padding as authenticator exports vary. */
    public static byte[] base32Decode(String input) {
        String alphabet = "ABCDEFGHIJKLMNOPQRSTUVWXYZ234567";
        String clean = input.replaceAll("[\\s=-]", "").toUpperCase(Locale.ROOT);
        ByteBuffer out = ByteBuffer.allocate(clean.length() * 5 / 8 + 1);
        int buffer = 0;
        int bits = 0;
        for (char c : clean.toCharArray()) {
            int value = alphabet.indexOf(c);
            if (value < 0) throw new IllegalArgumentException("TOTP secret is not valid Base32");
            buffer = ((buffer << 5) | value) & 0xffff;
            bits += 5;
            if (bits >= 8) {
                out.put((byte) ((buffer >> (bits - 8)) & 0xff));
                bits -= 8;
            }
        }
        byte[] bytes = new byte[out.position()];
        out.flip().get(bytes);
        return bytes;
    }
}
