package com.creed.resource.envmatrix.api.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.util.List;

/**
 * {@code POST /aes/records/batch}: every item encrypted for, and saved against, every server, in one
 * transaction.
 *
 * <p>Plain values are sent, not ciphertexts: the Secret Key is {@code randomKey + host + ip}, so each
 * server needs its own ciphertext and only the backend, which encrypts per server, can make them.
 * The plain value and the IV are used and dropped — only the ciphertext and the randomkey are stored.
 *
 * <p>A property key may appear only once per batch — two values for the same server and property
 * would leave the last one silently winning.
 */
public record AesRecordBatchSaveRequest(
        @NotEmpty @Size(max = 200) List<@Valid Item> items,
        @NotEmpty @Size(max = 500) List<AesRecordSaveRequest.@Valid Server> servers) {

    public record Item(
            @NotBlank @Size(max = 255) String propertyKey,
            @NotNull @Size(max = 4000) String plainValue,
            @NotNull @Size(max = 64) String iv,
            @Size(max = 256) String randomKey,
            @Size(max = 512) String note) {

        @Override
        public String toString() {
            return "Item[propertyKey=" + propertyKey + ", values=***]";
        }
    }

    @Override
    public String toString() {
        return "AesRecordBatchSaveRequest[items=" + items.size() + ", servers=" + servers.size() + "]";
    }
}
