package com.creed.resource.envmatrix.api.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/** {@code POST /aes/decrypt} for one server. Same key fields as {@link AesEncryptRequest}. */
public record AesDecryptRequest(
        @NotNull @Size(max = 64) String iv,
        @Size(max = 256) String salt,
        @Size(max = 256) String randomKey,
        @NotBlank @Size(max = 255) String host,
        @NotBlank @Size(max = 45) String ip,
        @NotBlank @Size(max = 16384) String encryptedValue) {

    @Override
    public String toString() {
        return "AesDecryptRequest[***]";
    }
}
