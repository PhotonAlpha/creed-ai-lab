package com.creed.resource.envmatrix.api.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Size;

import java.util.List;

/**
 * {@code POST /aes/records}: one ciphertext, saved against every listed server.
 *
 * <p>{@code randomKey} is the one the ciphertext was produced with, stored for display; the Secret
 * Key and IV are never sent with a save.
 *
 * <p>Each server carries its own {@code appSystem} because the page's server list may span app
 * systems when its filter is cleared. A server that already has {@code propertyKey} gets its value
 * replaced — the identity is {@code (appSystem, host, ip, propertyKey)}.
 */
public record AesRecordSaveRequest(
        @NotBlank @Size(max = 255) String propertyKey,
        @NotBlank @Size(max = 16384) String encryptedValue,
        @Size(max = 256) String randomKey,
        @Size(max = 512) String note,
        @NotEmpty @Size(max = 500) List<@Valid Server> servers) {

    public record Server(
            @NotBlank @Size(max = 64) String appSystem,
            @NotBlank @Size(max = 255) String host,
            @NotBlank @Size(max = 45) String ip) {
    }
}
