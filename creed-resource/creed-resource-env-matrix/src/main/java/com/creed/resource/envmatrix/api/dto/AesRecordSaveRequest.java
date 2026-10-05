package com.creed.resource.envmatrix.api.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.util.List;

/**
 * {@code POST /aes/records}: one plain value, encrypted and saved once per listed server — a batch
 * of one; see {@link AesRecordBatchSaveRequest}.
 */
public record AesRecordSaveRequest(
        @NotBlank @Size(max = 255) String propertyKey,
        @NotNull @Size(max = 4000) String plainValue,
        @NotNull @Size(max = 64) String iv,
        @Size(max = 256) String salt,
        @Size(max = 256) String randomKey,
        @Size(max = 512) String note,
        @NotEmpty @Size(max = 500) List<@Valid Server> servers) {

    public record Server(
            @NotBlank @Size(max = 64) String appSystem,
            @NotBlank @Size(max = 255) String host,
            @NotBlank @Size(max = 45) String ip) {
    }

    @Override
    public String toString() {
        return "AesRecordSaveRequest[propertyKey=" + propertyKey + ", servers=" + servers.size() + ", values=***]";
    }
}
