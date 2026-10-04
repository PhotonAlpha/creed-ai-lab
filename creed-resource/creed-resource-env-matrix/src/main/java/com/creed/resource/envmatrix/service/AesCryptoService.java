package com.creed.resource.envmatrix.service;

import com.creed.resource.envmatrix.api.dto.AesBatchCryptoRequest;
import com.creed.resource.envmatrix.api.dto.AesBatchCryptoResult;
import org.springframework.stereotype.Service;

import javax.crypto.BadPaddingException;
import javax.crypto.Cipher;
import javax.crypto.IllegalBlockSizeException;
import javax.crypto.spec.IvParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.function.Function;

/**
 * The AES page's cipher — the rule the real configuration files are encrypted with:
 *
 * <pre>
 * secretKey  = randomKey + host + ip                           plain concatenation, no separator
 * key        = SHA-256( UTF-8(secretKey) )                     32 bytes  -> AES-256
 * iv         = UTF-8(iv)                                       exactly 16 bytes
 * ciphertext = Base64( AES/CBC/PKCS5Padding(key, iv, UTF-8(plain)) )
 * </pre>
 *
 * So the same value has a different ciphertext on every server. The node mock
 * ({@code server/aes.js}) implements the same lines; both test suites pin the same vector, which
 * {@code openssl enc} reproduces.
 *
 * <p><b>Every input to the Secret Key is stored or public</b> — the randomkey is saved per record and
 * host/ip come from the endpoint table — so the IV, which is never stored, is the only thing between
 * a copy of the database and the plaintexts. That is the real system's rule, reproduced here so its
 * config files can be checked, not a design this page chose.
 *
 * <p>Nothing here is logged, and no exception message carries a key, an IV or a value — the caller's
 * error body is built from these messages.
 *
 * <p>CBC has no integrity check. A wrong key is almost always caught by the padding check or by the
 * result not being UTF-8, but roughly one wrong key in a few hundred decrypts to a short run of
 * well-formed garbage. That is the mode's nature, not a bug here; GCM would detect it.
 */
@Service
public class AesCryptoService {

    public static final String ALGORITHM =
            "AES-256/CBC/PKCS5Padding, Secret Key = randomKey + host + ip, key = SHA-256(Secret Key), Base64";

    private static final String TRANSFORMATION = "AES/CBC/PKCS5Padding";
    private static final int IV_BYTES = 16;

    private static final String WRONG_KEYS =
            "could not decrypt — the IV or the Secret Key (randomkey + host + ip) differ from the ones it was encrypted with";

    /**
     * The Secret Key for one server. Plain concatenation, as the real system does it — so ("ab", "c")
     * and ("a", "bc") as (randomkey, host) give the same key.
     */
    public static String secretKey(String randomKey, String host, String ip) {
        return nullToEmpty(randomKey) + nullToEmpty(host) + nullToEmpty(ip);
    }

    public String encrypt(String secretKey, String iv, String plainValue) {
        Cipher cipher = cipher(Cipher.ENCRYPT_MODE, secretKey, iv);
        try {
            byte[] encrypted = cipher.doFinal(plainValue.getBytes(StandardCharsets.UTF_8));
            return Base64.getEncoder().encodeToString(encrypted);
        } catch (GeneralSecurityException e) {
            // Unreachable for PKCS5 encryption; surfaced rather than swallowed if it ever is.
            throw new IllegalStateException("AES encryption failed", e);
        }
    }

    public String decrypt(String secretKey, String iv, String encryptedValue) {
        byte[] encrypted;
        try {
            encrypted = Base64.getDecoder().decode(encryptedValue.strip());
        } catch (IllegalArgumentException e) {
            throw new DecryptException("the encrypted value is not valid Base64");
        }
        if (encrypted.length == 0 || encrypted.length % IV_BYTES != 0) {
            throw new DecryptException("the encrypted value is " + encrypted.length
                    + " bytes; AES/CBC output is a non-empty multiple of 16");
        }

        Cipher cipher = cipher(Cipher.DECRYPT_MODE, secretKey, iv);
        byte[] plain;
        try {
            plain = cipher.doFinal(encrypted);
        } catch (BadPaddingException | IllegalBlockSizeException e) {
            throw new DecryptException(WRONG_KEYS);
        }
        try {
            // Strict decoding: a lenient one would turn a wrong key's output into U+FFFD soup and
            // report it as a successful decryption.
            return StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(plain))
                    .toString();
        } catch (CharacterCodingException e) {
            throw new DecryptException(WRONG_KEYS);
        }
    }

    /** Throws {@link InvalidKeyMaterialException} when the IV could not be used for anything. */
    public void requireUsableIv(String iv) {
        ivBytes(iv);
    }

    /**
     * Encrypts every row with its own randomkey, server and IV. One row's unusable input is that
     * row's result, not a failure of the batch — the rows are independent.
     */
    public List<AesBatchCryptoResult> encryptAll(List<AesBatchCryptoRequest.Item> items) {
        return each(items, item -> encrypt(secretKey(item.randomKey(), item.host(), item.ip()), item.iv(), item.value()));
    }

    /** Decrypts every row with its own inputs; see {@link #encryptAll}. */
    public List<AesBatchCryptoResult> decryptAll(List<AesBatchCryptoRequest.Item> items) {
        return each(items, item -> decrypt(secretKey(item.randomKey(), item.host(), item.ip()), item.iv(), item.value()));
    }

    private static List<AesBatchCryptoResult> each(List<AesBatchCryptoRequest.Item> items,
                                                   Function<AesBatchCryptoRequest.Item, String> op) {
        List<AesBatchCryptoResult> results = new ArrayList<>(items.size());
        for (int i = 0; i < items.size(); i++) {
            try {
                results.add(new AesBatchCryptoResult(i, op.apply(items.get(i)), null, null, null));
            } catch (InvalidKeyMaterialException e) {
                results.add(new AesBatchCryptoResult(i, null, "invalid_key_material", e.field(), e.getMessage()));
            } catch (DecryptException e) {
                results.add(new AesBatchCryptoResult(i, null, "decrypt_failed", null, e.getMessage()));
            }
        }
        return results;
    }

    private static Cipher cipher(int mode, String secretKey, String iv) {
        byte[] ivBytes = ivBytes(iv);
        try {
            Cipher cipher = Cipher.getInstance(TRANSFORMATION);
            cipher.init(mode, new SecretKeySpec(deriveKey(secretKey), "AES"), new IvParameterSpec(ivBytes));
            return cipher;
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException(TRANSFORMATION + " unavailable", e);
        }
    }

    private static byte[] ivBytes(String iv) {
        byte[] bytes = iv == null ? new byte[0] : iv.getBytes(StandardCharsets.UTF_8);
        if (bytes.length != IV_BYTES) {
            throw new InvalidKeyMaterialException("iv",
                    "must be exactly " + IV_BYTES + " bytes in UTF-8, got " + bytes.length);
        }
        return bytes;
    }

    static byte[] deriveKey(String secretKey) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(nullToEmpty(secretKey).getBytes(StandardCharsets.UTF_8));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private static String nullToEmpty(String s) {
        return s == null ? "" : s;
    }

    /** An IV that cannot be used at all — the caller's input, so a 400 naming the field. */
    public static class InvalidKeyMaterialException extends RuntimeException {
        private final String field;

        public InvalidKeyMaterialException(String field, String message) {
            super(message);
            this.field = field;
        }

        public String field() {
            return field;
        }
    }

    /** Well-formed input that does not decrypt — a 422, since the request itself was valid. */
    public static class DecryptException extends RuntimeException {
        public DecryptException(String message) {
            super(message);
        }
    }
}
