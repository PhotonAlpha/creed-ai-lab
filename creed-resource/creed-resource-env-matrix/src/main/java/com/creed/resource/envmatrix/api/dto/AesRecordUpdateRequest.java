package com.creed.resource.envmatrix.api.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/** {@code PUT /aes/records/{id}} — every field is editable; the identity must stay unique. */
public record AesRecordUpdateRequest(
        @NotBlank @Size(max = 64) String appSystem,
        @NotBlank @Size(max = 255) String host,
        @NotBlank @Size(max = 45) String ip,
        @NotBlank @Size(max = 255) String propertyKey,
        @NotBlank @Size(max = 16384) String encryptedValue,
        @Size(max = 256) String randomKey,
        @Size(max = 64) String iv,
        @Size(max = 256) String salt,
        @Size(max = 512) String note) {
}
