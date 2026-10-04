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
    private static final String PLAIN = "db-p@ss 密码";
    /** {@code randomKey + host + ip} for the server above. */
    private static final String SECRET = "r4nd0mms1.cn.uat110.1.1.11";
    /**
     * Produced independently by `openssl enc -aes-256-cbc -K $(sha256 of SECRET) -iv <hex of IV>` and
     * by node's crypto — and pinned in server/aes.test.js, so the Java service and the node mock
     * cannot drift apart.
     */
    private static final String VECTOR = "jD7BgEr6wW5uZCYJi4j5Rw==";

    @Test
    @DisplayName("the Secret Key is randomkey + host + ip, concatenated with no separator")
    void secretKey() {
        assertThat(AesCryptoService.secretKey(RANDOM, HOST, IP)).isEqualTo(SECRET);
        assertThat(AesCryptoService.secretKey(null, HOST, IP)).isEqualTo("ms1.cn.uat110.1.1.11");
        assertThat(AesCryptoService.secretKey("", HOST, IP)).isEqualTo("ms1.cn.uat110.1.1.11");
    }

    @Test
    @DisplayName("matches the vector openssl and node produce: key = SHA-256(Secret Key)")
    void vector() {
        assertThat(crypto.encrypt(SECRET, IV, PLAIN)).isEqualTo(VECTOR);
        assertThat(crypto.decrypt(SECRET, IV, VECTOR)).isEqualTo(PLAIN);
    }

    @Test
    @DisplayName("each server gets its own ciphertext; an empty randomkey is just host + ip")
    void perServer() {
        assertThat(crypto.encrypt(AesCryptoService.secretKey(RANDOM, "ms2.cn.uat1", "10.1.1.12"), IV, PLAIN))
                .isEqualTo("zvgQBvL3DkQo62CFSqDT4w==");
        assertThat(crypto.encrypt(AesCryptoService.secretKey("", HOST, IP), IV, PLAIN))
                .isEqualTo("77E6/C/RavNY+erjoa3itA==");
    }

    @Test
    @DisplayName("another server's Secret Key, or another IV, does not decrypt")
    void wrongKeys() {
        assertThatThrownBy(() -> crypto.decrypt(AesCryptoService.secretKey(RANDOM, "ms2.cn.uat1", "10.1.1.12"), IV, VECTOR))
                .isInstanceOf(AesCryptoService.DecryptException.class);
        // A wrong IV in CBC only garbles the first block; on a one-block value that is all of it.
        assertThatThrownBy(() -> crypto.decrypt(SECRET, "fedcba9876543210", VECTOR))
                .isInstanceOf(AesCryptoService.DecryptException.class);
    }

    @Test
    @DisplayName("the IV must be exactly 16 UTF-8 bytes — characters are not bytes")
    void ivLength() {
        assertThatThrownBy(() -> crypto.encrypt(SECRET, "short", PLAIN))
                .isInstanceOf(AesCryptoService.InvalidKeyMaterialException.class)
                .hasMessageContaining("got 5");
        // 6 characters, 18 bytes.
        assertThatThrownBy(() -> crypto.encrypt(SECRET, "密码密码密码", PLAIN))
                .isInstanceOf(AesCryptoService.InvalidKeyMaterialException.class)
                .hasMessageContaining("got 18")
                .extracting(e -> ((AesCryptoService.InvalidKeyMaterialException) e).field()).isEqualTo("iv");
    }

    @Test
    @DisplayName("malformed ciphertext is a decrypt failure, not a crash")
    void malformed() {
        assertThatThrownBy(() -> crypto.decrypt(SECRET, IV, "not base64 !!"))
                .isInstanceOf(AesCryptoService.DecryptException.class).hasMessageContaining("Base64");
        assertThatThrownBy(() -> crypto.decrypt(SECRET, IV, "AAAA"))
                .isInstanceOf(AesCryptoService.DecryptException.class).hasMessageContaining("multiple of 16");
    }

    @Test
    @DisplayName("no message carries a key, IV or value")
    void noSecretsInMessages() {
        Throwable wrong = org.junit.jupiter.api.Assertions.assertThrows(RuntimeException.class,
                () -> crypto.decrypt("other", IV, VECTOR));
        Throwable badIv = org.junit.jupiter.api.Assertions.assertThrows(RuntimeException.class,
                () -> crypto.encrypt(SECRET, "short-iv", PLAIN));
        for (Throwable t : new Throwable[]{wrong, badIv}) {
            assertThat(t.getMessage()).doesNotContain(SECRET, RANDOM, PLAIN, "short-iv", VECTOR);
        }
    }

    @Test
    @DisplayName("round trip of an empty string and a long multi-byte value")
    void roundTrip() {
        String longValue = "值".repeat(4000);
        for (String plain : new String[]{"", longValue}) {
            assertThat(crypto.decrypt(SECRET, IV, crypto.encrypt(SECRET, IV, plain))).isEqualTo(plain);
        }
        // The V7 column is sized for the API's 4000-character limit at 3 bytes per character.
        assertThat(crypto.encrypt(SECRET, IV, longValue)).hasSizeLessThanOrEqualTo(16384);
    }
}
