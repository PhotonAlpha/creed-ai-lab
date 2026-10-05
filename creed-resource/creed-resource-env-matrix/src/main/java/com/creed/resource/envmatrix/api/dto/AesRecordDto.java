package com.creed.resource.envmatrix.api.dto;

import com.creed.resource.envmatrix.domain.EnvAesRecord;
import com.creed.resource.envmatrix.service.AesCryptoService;

import java.time.Instant;

/**
 * Read model for one stored ciphertext.
 *
 * @param iv        the IV it was encrypted with ({@code null} before V9) — shown for checking
 * @param salt      the salt it was encrypted with ({@code null} before V9)
 * @param secretKey {@code randomKey + host + ip} — derived, not stored, and shown so the value can be
 *                  compared with the real configuration file the record describes
 */
public record AesRecordDto(
        Long id,
        String appSystem,
        String host,
        String ip,
        String propertyKey,
        String encryptedValue,
        String randomKey,
        String secretKey,
        String iv,
        String salt,
        String note,
        Instant createdAt,
        Instant updatedAt,
        Long version) {

    public static AesRecordDto of(EnvAesRecord r) {
        return new AesRecordDto(
                r.getId(), r.getAppSystem(), r.getHost(), r.getIp(), r.getPropertyKey(),
                r.getEncryptedValue(), r.getRandomKey(),
                AesCryptoService.secretKey(r.getRandomKey(), r.getHost(), r.getIp()), r.getIv(), r.getSalt(), r.getNote(),
                r.getCreatedAt(), r.getUpdatedAt(), r.getVersion());
    }
}
