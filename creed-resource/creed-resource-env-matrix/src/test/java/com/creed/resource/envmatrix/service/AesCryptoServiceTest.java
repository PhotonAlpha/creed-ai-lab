package com.creed.resource.envmatrix.service;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AesCryptoServiceTest {

    private final AesCryptoService crypto = new AesCryptoService();

    private static final String RANDOM = "r4nd0m";
    private static final String HOST = "ms1.cn.uat1";
    private static final String IP = "10.1.1.11";
    private static final String IV = "0123456789abcdef";
    /** Multi-byte on purpose: the salt is used as UTF-8 bytes. */
    private static final String SALT = "s4lt 盐";
    private static final String PLAIN = "db-p@ss 密码";
    /** {@code randomKey + host + ip} for the server above. */
    private static final String SECRET = "r4nd0mms1.cn.uat110.1.1.11";
    /**
     * Produced independently by `openssl kdf -kdfopt digest:SHA256 -kdfopt iter:65536 PBKDF2` piped
     * into `openssl enc -aes-256-cbc`, and by node's crypto — and pinned in server/aes.test.js, so the
     * Java service and the node mock cannot drift apart.
     */
    private static final String VECTOR = "H24JNkxfMPSBHI3vtO41cQ==";
    /** The PBKDF2 output for SECRET + SALT, from the same openssl run. */
    private static final String KEY_HEX = "5ca1498eb5694b6b59b2eae797c6a820a4366446891d1962a5941178238ff339";

    @Test
    @DisplayName("the Secret Key is randomkey + host + ip, concatenated with no separator")
    void secretKey() {
        assertThat(AesCryptoService.secretKey(RANDOM, HOST, IP)).isEqualTo(SECRET);
        assertThat(AesCryptoService.secretKey(null, HOST, IP)).isEqualTo("ms1.cn.uat110.1.1.11");
        assertThat(AesCryptoService.secretKey("", HOST, IP)).isEqualTo("ms1.cn.uat110.1.1.11");
    }

    @Test
    @DisplayName("key = PBKDF2WithHmacSHA256(Secret Key, UTF-8 salt, 65536, 256) — openssl's output")
    void derivedKey() {
        assertThat(java.util.HexFormat.of().formatHex(AesCryptoService.deriveKey(SECRET, SALT))).isEqualTo(KEY_HEX);
    }

    @Test
    @DisplayName("matches the vector openssl and node produce")
    void vector() {
        assertThat(crypto.encrypt(SECRET, SALT, IV, PLAIN)).isEqualTo(VECTOR);
        assertThat(crypto.decrypt(SECRET, SALT, IV, VECTOR)).isEqualTo(PLAIN);
    }

    @Test
    @DisplayName("each server gets its own ciphertext; an empty randomkey is just host + ip")
    void perServer() {
        assertThat(crypto.encrypt(AesCryptoService.secretKey(RANDOM, "ms2.cn.uat1", "10.1.1.12"), SALT, IV, PLAIN))
                .isEqualTo("+xwHQYYKsIdcswqiV0wq2Q==");
        assertThat(crypto.encrypt(AesCryptoService.secretKey("", HOST, IP), SALT, IV, PLAIN))
                .isEqualTo("3kIVwWqttsGhLWV8i/F1pA==");
    }

    @Test
    @DisplayName("another salt gives another ciphertext and does not decrypt")
    void otherSalt() {
        assertThat(crypto.encrypt(SECRET, "other", IV, PLAIN)).isEqualTo("MKxaHOqwjN1t2Bs4KzmxpQ==");
        assertThatThrownBy(() -> crypto.decrypt(SECRET, "other", IV, VECTOR))
                .isInstanceOf(AesCryptoService.DecryptException.class);
    }

    @Test
    @DisplayName("another server's Secret Key, or another IV, does not decrypt")
    void wrongKeys() {
        assertThatThrownBy(() -> crypto.decrypt(AesCryptoService.secretKey(RANDOM, "ms2.cn.uat1", "10.1.1.12"), SALT, IV, VECTOR))
                .isInstanceOf(AesCryptoService.DecryptException.class);
        // A wrong IV in CBC only garbles the first block; on a one-block value that is all of it.
        assertThatThrownBy(() -> crypto.decrypt(SECRET, SALT, "fedcba9876543210", VECTOR))
                .isInstanceOf(AesCryptoService.DecryptException.class);
    }

    @Test
    @DisplayName("the salt is required — empty or missing is invalid key material naming salt")
    void saltRequired() {
        for (String salt : new String[]{null, ""}) {
            assertThatThrownBy(() -> crypto.encrypt(SECRET, salt, IV, PLAIN))
                    .isInstanceOf(AesCryptoService.InvalidKeyMaterialException.class)
                    .extracting(e -> ((AesCryptoService.InvalidKeyMaterialException) e).field()).isEqualTo("salt");
        }
        // Spaces are bytes like any other — part of the salt, as in the randomkey.
        assertThat(crypto.decrypt(SECRET, " ", IV, crypto.encrypt(SECRET, " ", IV, PLAIN))).isEqualTo(PLAIN);
    }

    @Test
    @DisplayName("the IV must be exactly 16 UTF-8 bytes — characters are not bytes — and is checked before the salt")
    void ivLength() {
        assertThatThrownBy(() -> crypto.encrypt(SECRET, SALT, "short", PLAIN))
                .isInstanceOf(AesCryptoService.InvalidKeyMaterialException.class)
                .hasMessageContaining("got 5");
        // 6 characters, 18 bytes.
        assertThatThrownBy(() -> crypto.encrypt(SECRET, SALT, "密码密码密码", PLAIN))
                .isInstanceOf(AesCryptoService.InvalidKeyMaterialException.class)
                .hasMessageContaining("got 18")
                .extracting(e -> ((AesCryptoService.InvalidKeyMaterialException) e).field()).isEqualTo("iv");
        assertThatThrownBy(() -> crypto.encrypt(SECRET, "", "short", PLAIN))
                .extracting(e -> ((AesCryptoService.InvalidKeyMaterialException) e).field()).isEqualTo("iv");
    }

    @Test
    @DisplayName("malformed ciphertext is a decrypt failure, not a crash")
    void malformed() {
        assertThatThrownBy(() -> crypto.decrypt(SECRET, SALT, IV, "not base64 !!"))
                .isInstanceOf(AesCryptoService.DecryptException.class).hasMessageContaining("Base64");
        assertThatThrownBy(() -> crypto.decrypt(SECRET, SALT, IV, "AAAA"))
                .isInstanceOf(AesCryptoService.DecryptException.class).hasMessageContaining("multiple of 16");
    }

    @Test
    @DisplayName("no message carries a key, IV, salt or value")
    void noSecretsInMessages() {
        Throwable wrong = org.junit.jupiter.api.Assertions.assertThrows(RuntimeException.class,
                () -> crypto.decrypt("other", SALT, IV, VECTOR));
        Throwable badIv = org.junit.jupiter.api.Assertions.assertThrows(RuntimeException.class,
                () -> crypto.encrypt(SECRET, SALT, "short-iv", PLAIN));
        Throwable noSalt = org.junit.jupiter.api.Assertions.assertThrows(RuntimeException.class,
                () -> crypto.encrypt(SECRET, "", IV, PLAIN));
        for (Throwable t : new Throwable[]{wrong, badIv, noSalt}) {
            assertThat(t.getMessage()).doesNotContain(SECRET, RANDOM, PLAIN, "short-iv", VECTOR, SALT, IV);
        }
    }

    @Test
    @DisplayName("the key cache derives once per (Secret Key, salt) and keeps pairs apart")
    void keyCache() {
        AesCryptoService.KeyCache keys = new AesCryptoService.KeyCache();
        byte[] first = keys.key(SECRET, SALT);
        assertThat(keys.key(SECRET, SALT)).isSameAs(first);
        assertThat(keys.key(SECRET, "other")).isNotEqualTo(first);
        assertThat(crypto.encrypt(first, IV, PLAIN)).isEqualTo(VECTOR);
    }

    @Test
    @DisplayName("round trip of an empty string and a long multi-byte value")
    void roundTrip() {
        String longValue = "值".repeat(4000);
        for (String plain : new String[]{"", longValue}) {
            assertThat(crypto.decrypt(SECRET, SALT, IV, crypto.encrypt(SECRET, SALT, IV, plain))).isEqualTo(plain);
        }
        // The V7 column is sized for the API's 4000-character limit at 3 bytes per character.
        assertThat(crypto.encrypt(SECRET, SALT, IV, longValue)).hasSizeLessThanOrEqualTo(16384);
    }
}
