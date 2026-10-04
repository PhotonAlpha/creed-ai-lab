package com.creed.resource.envmatrix.api.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Size;

import java.util.List;

/**
 * {@code POST /aes/records/batch}: every item saved against every server, in one transaction.
 *
 * <p>A property key may appear only once per batch — two values for the same server and property
 * would leave the last one silently winning. Secret Keys and IVs are never part of a save.
 */
public record AesRecordBatchSaveRequest(
        @NotEmpty @Size(max = 200) List<@Valid Item> items,
        @NotEmpty @Size(max = 500) List<AesRecordSaveRequest.@Valid Server> servers) {

    public record Item(
            @NotBlank @Size(max = 255) String propertyKey,
            @NotBlank @Size(max = 16384) String encryptedValue,
            @Size(max = 256) String randomKey,
            @Size(max = 512) String note) {
    }
}
