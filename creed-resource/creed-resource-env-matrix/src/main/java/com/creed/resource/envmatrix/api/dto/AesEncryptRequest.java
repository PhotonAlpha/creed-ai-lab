package com.creed.resource.envmatrix.api.dto;

import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * {@code POST /aes/encrypt}. The keys travel with every request and are never stored.
 *
 * @param iv        exactly 16 bytes in UTF-8 — checked by {@code AesCryptoService}, which can count bytes
 * @param randomKey appended to {@code secretKey} before hashing; may be empty
 */
public record AesEncryptRequest(
        @NotEmpty @Size(max = 256) String secretKey,
        @NotNull @Size(max = 64) String iv,
        @Size(max = 256) String randomKey,
        @NotNull @Size(max = 4000) String plainValue) {

    /** Every field is either a key or the secret itself — none may reach a log line. */
    @Override
    public String toString() {
        return "AesEncryptRequest[***]";
    }
}
