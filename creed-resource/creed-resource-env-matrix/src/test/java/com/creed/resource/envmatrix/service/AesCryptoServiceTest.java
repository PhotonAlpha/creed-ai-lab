package com.creed.resource.envmatrix.service;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AesCryptoServiceTest {

    private final AesCryptoService crypto = new AesCryptoService();

    private static final String SECRET = "creed-secret";
    private static final String IV = "0123456789abcdef";
    private static final String RANDOM = "r4nd0m";
    private static final String PLAIN = "db-p@ss 密码";
    /**
     * Produced independently by both `openssl enc -aes-256-cbc -K $(sha256 of "creed-secretr4nd0m")
     * -iv <hex of IV>` and node's crypto — and pinned in server/aes.test.js, so the Java service and
     * the node mock cannot drift apart.
     */
    private static final String VECTOR = "EFZDc/Ok6Ib89heJb1OkHw==";

    @Test
    @DisplayName("matches the vector openssl and node produce")
    void vector() {
        assertThat(crypto.encrypt(SECRET, IV, RANDOM, PLAIN)).isEqualTo(VECTOR);
        assertThat(crypto.decrypt(SECRET, IV, RANDOM, VECTOR)).isEqualTo(PLAIN);
    }

    @Test
    @DisplayName("an empty or null randomkey derives the key from the Secret Key alone")
    void emptyRandomKey() {
        assertThat(crypto.encrypt(SECRET, IV, "", PLAIN))
                .isEqualTo(crypto.encrypt(SECRET, IV, null, PLAIN))
                .isEqualTo("16yJ7k5vZlC4Dylj/nGKeA==")
                .isNotEqualTo(VECTOR);
    }

    @Test
    @DisplayName("a different randomkey, Secret Key or IV does not decrypt")
    void wrongKeys() {
        assertThatThrownBy(() -> crypto.decrypt(SECRET, IV, "other", VECTOR))
                .isInstanceOf(AesCryptoService.DecryptException.class);
        assertThatThrownBy(() -> crypto.decrypt("other-secret", IV, RANDOM, VECTOR))
                .isInstanceOf(AesCryptoService.DecryptException.class);
        // A wrong IV in CBC only garbles the first block; on a one-block value that is all of it.
        assertThatThrownBy(() -> crypto.decrypt(SECRET, "fedcba9876543210", RANDOM, VECTOR))
                .isInstanceOf(AesCryptoService.DecryptException.class);
    }

    @Test
    @DisplayName("the IV must be exactly 16 UTF-8 bytes — characters are not bytes")
    void ivLength() {
        assertThatThrownBy(() -> crypto.encrypt(SECRET, "short", RANDOM, PLAIN))
                .isInstanceOf(AesCryptoService.InvalidKeyMaterialException.class)
                .hasMessageContaining("got 5");
        // 6 characters, 18 bytes.
        assertThatThrownBy(() -> crypto.encrypt(SECRET, "密码密码密码", RANDOM, PLAIN))
                .isInstanceOf(AesCryptoService.InvalidKeyMaterialException.class)
                .hasMessageContaining("got 18")
                .extracting(e -> ((AesCryptoService.InvalidKeyMaterialException) e).field()).isEqualTo("iv");
        assertThatThrownBy(() -> crypto.encrypt("", IV, RANDOM, PLAIN))
                .isInstanceOf(AesCryptoService.InvalidKeyMaterialException.class);
    }

    @Test
    @DisplayName("malformed ciphertext is a decrypt failure, not a crash")
    void malformed() {
        assertThatThrownBy(() -> crypto.decrypt(SECRET, IV, RANDOM, "not base64 !!"))
                .isInstanceOf(AesCryptoService.DecryptException.class).hasMessageContaining("Base64");
        assertThatThrownBy(() -> crypto.decrypt(SECRET, IV, RANDOM, "AAAA"))
                .isInstanceOf(AesCryptoService.DecryptException.class).hasMessageContaining("multiple of 16");
    }

    @Test
    @DisplayName("no message carries a key, IV or value")
    void noSecretsInMessages() {
        Throwable wrong = org.junit.jupiter.api.Assertions.assertThrows(RuntimeException.class,
                () -> crypto.decrypt(SECRET, IV, "other", VECTOR));
        Throwable badIv = org.junit.jupiter.api.Assertions.assertThrows(RuntimeException.class,
                () -> crypto.encrypt(SECRET, "short-iv", RANDOM, PLAIN));
        for (Throwable t : new Throwable[]{wrong, badIv}) {
            assertThat(t.getMessage()).doesNotContain(SECRET, RANDOM, PLAIN, "short-iv", VECTOR);
        }
    }

    @Test
    @DisplayName("round trip of an empty string and a long multi-byte value")
    void roundTrip() {
        String longValue = "值".repeat(4000);
        for (String plain : new String[]{"", longValue}) {
            String encrypted = crypto.encrypt(SECRET, IV, RANDOM, plain);
            assertThat(crypto.decrypt(SECRET, IV, RANDOM, encrypted)).isEqualTo(plain);
        }
        // The V7 column is sized for the API's 4000-character limit at 3 bytes per character.
        assertThat(crypto.encrypt(SECRET, IV, RANDOM, longValue)).hasSizeLessThanOrEqualTo(16384);
    }
}
