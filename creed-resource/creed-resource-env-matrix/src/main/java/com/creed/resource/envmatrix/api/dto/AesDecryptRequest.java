package com.creed.resource.envmatrix.api.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/** {@code POST /aes/decrypt}. Same key fields as {@link AesEncryptRequest}. */
public record AesDecryptRequest(
        @NotEmpty @Size(max = 256) String secretKey,
        @NotNull @Size(max = 64) String iv,
        @Size(max = 256) String randomKey,
        @NotBlank @Size(max = 16384) String encryptedValue) {

    @Override
    public String toString() {
        return "AesDecryptRequest[***]";
    }
}
