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

/**
 * The AES page's cipher:
 *
 * <pre>
 * key        = SHA-256( UTF-8(secretKey + randomKey) )        32 bytes  -> AES-256
 * iv         = UTF-8(iv)                                       exactly 16 bytes
 * ciphertext = Base64( AES/CBC/PKCS5Padding(key, iv, UTF-8(plain)) )
 * </pre>
 *
 * The node mock ({@code server/aes.js}) implements the same three lines; a value encrypted by one
 * decrypts in the other, and both test suites pin the same vector.
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

    public static final String ALGORITHM = "AES-256/CBC/PKCS5Padding, key = SHA-256(secretKey + randomKey), Base64";

    private static final String TRANSFORMATION = "AES/CBC/PKCS5Padding";
    private static final int IV_BYTES = 16;

    public String encrypt(String secretKey, String iv, String randomKey, String plainValue) {
        Cipher cipher = cipher(Cipher.ENCRYPT_MODE, secretKey, iv, randomKey);
        try {
            byte[] encrypted = cipher.doFinal(plainValue.getBytes(StandardCharsets.UTF_8));
            return Base64.getEncoder().encodeToString(encrypted);
        } catch (GeneralSecurityException e) {
            // Unreachable for PKCS5 encryption; surfaced rather than swallowed if it ever is.
            throw new IllegalStateException("AES encryption failed", e);
        }
    }

    /**
     * Encrypts every row with its own keys. One row's unusable keys are that row's result, not a
     * failure of the batch — the rows are independent sets.
     */
    public List<AesBatchCryptoResult> encryptAll(List<AesBatchCryptoRequest.Item> items) {
        return each(items, item -> encrypt(item.secretKey(), item.iv(), item.randomKey(), item.value()));
    }

    /** Decrypts every row with its own keys; see {@link #encryptAll}. */
    public List<AesBatchCryptoResult> decryptAll(List<AesBatchCryptoRequest.Item> items) {
        return each(items, item -> decrypt(item.secretKey(), item.iv(), item.randomKey(), item.value()));
    }

    private static List<AesBatchCryptoResult> each(List<AesBatchCryptoRequest.Item> items,
                                                   java.util.function.Function<AesBatchCryptoRequest.Item, String> op) {
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

    /** Throws {@link InvalidKeyMaterialException} when the keys could not be used for anything. */
    public void requireUsableKeys(String secretKey, String iv, String randomKey) {
        cipher(Cipher.ENCRYPT_MODE, secretKey, iv, randomKey);
    }

    public String decrypt(String secretKey, String iv, String randomKey, String encryptedValue) {
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

        Cipher cipher = cipher(Cipher.DECRYPT_MODE, secretKey, iv, randomKey);
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

    private static final String WRONG_KEYS =
            "could not decrypt — the Secret Key, IV or randomkey differ from the ones it was encrypted with";

    private static Cipher cipher(int mode, String secretKey, String iv, String randomKey) {
        if (secretKey == null || secretKey.isEmpty()) {
            throw new InvalidKeyMaterialException("secretKey", "must not be empty");
        }
        byte[] ivBytes = iv == null ? new byte[0] : iv.getBytes(StandardCharsets.UTF_8);
        if (ivBytes.length != IV_BYTES) {
            throw new InvalidKeyMaterialException("iv",
                    "must be exactly " + IV_BYTES + " bytes in UTF-8, got " + ivBytes.length);
        }
        try {
            Cipher cipher = Cipher.getInstance(TRANSFORMATION);
            cipher.init(mode, new SecretKeySpec(deriveKey(secretKey, randomKey), "AES"), new IvParameterSpec(ivBytes));
            return cipher;
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException(TRANSFORMATION + " unavailable", e);
        }
    }

    /**
     * SHA-256 over the plain concatenation. Note what that implies: ("ab", "c") and ("a", "bc")
     * derive the same key — the randomkey varies the key, it is not a separate secret.
     */
    static byte[] deriveKey(String secretKey, String randomKey) {
        try {
            String material = secretKey + (randomKey == null ? "" : randomKey);
            return MessageDigest.getInstance("SHA-256").digest(material.getBytes(StandardCharsets.UTF_8));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    /** A key or IV that cannot be used at all — the caller's input, so a 400 naming the field. */
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
