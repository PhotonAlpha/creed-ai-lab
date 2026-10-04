package com.creed.resource.envmatrix.api.dto;

/**
 * One row of a batch encrypt/decrypt, in request order.
 *
 * @param index   the row's position in the request
 * @param value   the ciphertext (encrypt) or plaintext (decrypt) on success
 * @param error   {@code invalid_key_material} or {@code decrypt_failed} on failure
 * @param field   for {@code invalid_key_material}: which field — {@code iv}
 * @param message the human-readable reason; never contains a key or a value
 */
public record AesBatchCryptoResult(int index, String value, String error, String field, String message) {

    @Override
    public String toString() {
        return "AesBatchCryptoResult[index=" + index + ", error=" + error + ", value=***]";
    }
}
