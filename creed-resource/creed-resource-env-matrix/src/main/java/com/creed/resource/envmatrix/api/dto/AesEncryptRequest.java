package com.creed.resource.envmatrix.api.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * {@code POST /aes/encrypt} for one server. There is no Secret Key field: it is
 * {@code randomKey + host + ip}, derived by {@code AesCryptoService.secretKey}.
 *
 * @param iv        exactly 16 bytes in UTF-8 — checked by {@code AesCryptoService}, which can count bytes
 * @param salt      the PBKDF2 salt; required, but checked by {@code AesCryptoService} so a missing one
 *                  is a 400 naming {@code salt} like a bad IV, not a generic bean-validation error
 * @param randomKey may be empty
 */
public record AesEncryptRequest(
        @NotNull @Size(max = 64) String iv,
        @Size(max = 256) String salt,
        @Size(max = 256) String randomKey,
        @NotBlank @Size(max = 255) String host,
        @NotBlank @Size(max = 45) String ip,
        @NotNull @Size(max = 4000) String plainValue) {

    /** The IV, the salt and the value are secrets, and the rest derive the key — none may reach a log line. */
    @Override
    public String toString() {
        return "AesEncryptRequest[***]";
    }
}
