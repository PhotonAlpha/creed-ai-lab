package com.creed.resource.envmatrix.api.dto;

import com.creed.resource.envmatrix.domain.EnvAesRecord;

import java.time.Instant;

/** Read model for one stored ciphertext. */
public record AesRecordDto(
        Long id,
        String appSystem,
        String host,
        String ip,
        String propertyKey,
        String encryptedValue,
        String randomKey,
        String note,
        Instant createdAt,
        Instant updatedAt,
        Long version) {

    public static AesRecordDto of(EnvAesRecord r) {
        return new AesRecordDto(
                r.getId(), r.getAppSystem(), r.getHost(), r.getIp(), r.getPropertyKey(),
                r.getEncryptedValue(), r.getRandomKey(), r.getNote(), r.getCreatedAt(), r.getUpdatedAt(), r.getVersion());
    }
}
