package com.creed.gatewayproxy.service.splunk;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.springframework.security.crypto.encrypt.Encryptors;
import org.springframework.security.crypto.keygen.KeyGenerators;

/**
 * Encrypted values in configuration: {@code ENC(<salt hex>:<ciphertext hex>)}, decrypted at startup
 * with the master key {@code CREED_SECRETS_KEY}.
 *
 * <p>Spring Security Crypto's {@link Encryptors#delux}: AES-256/GCM, key = PBKDF2 over the master key
 * and a per-value random salt, a random IV per encryption (inside the ciphertext). GCM authenticates,
 * so a wrong key or an edited value fails loudly instead of yielding a garbage password.
 *
 * <p>Not {@code {cipher}…}: that prefix belongs to Spring Cloud Config, whose client would try (and
 * fail) to decrypt it with its own key if this module ever picked one up.
 */
public final class SecretCipher {

    /** The environment variable (or {@code _FILE}) holding the master key. */
    public static final String KEY_VARIABLE = "CREED_SECRETS_KEY";

    /** PBKDF2 here runs only 1024 iterations — the key has to carry the entropy itself. */
    static final int MIN_KEY_LENGTH = 16;

    private static final Pattern ENCRYPTED = Pattern.compile("^ENC\\(([0-9a-f]{16,}):([0-9a-f]+)\\)$");

    private final String key;

    public SecretCipher(String key) {
        if (key == null || key.length() < MIN_KEY_LENGTH) {
            throw new IllegalArgumentException(KEY_VARIABLE + " must be at least " + MIN_KEY_LENGTH
                    + " characters (e.g. `openssl rand -hex 32`)");
        }
        this.key = key;
    }

    public static boolean isEncrypted(String value) {
        return value != null && ENCRYPTED.matcher(value.strip()).matches();
    }

    public String encrypt(String plain) {
        String salt = KeyGenerators.string().generateKey(); // 8 random bytes, hex
        return "ENC(" + salt + ":" + Encryptors.delux(key, salt).encrypt(plain) + ")";
    }

    /** @throws IllegalArgumentException on a malformed value, a wrong key or a tampered ciphertext */
    public String decrypt(String value) {
        Matcher m = value == null ? null : ENCRYPTED.matcher(value.strip());
        if (m == null || !m.matches()) throw new IllegalArgumentException("not an ENC(salt:ciphertext) value");
        try {
            return Encryptors.delux(key, m.group(1)).decrypt(m.group(2));
        } catch (RuntimeException e) {
            // The cause says only "bad tag"; never include the value or the key.
            throw new IllegalArgumentException("cannot decrypt — wrong " + KEY_VARIABLE + " or a damaged value", e);
        }
    }
}
