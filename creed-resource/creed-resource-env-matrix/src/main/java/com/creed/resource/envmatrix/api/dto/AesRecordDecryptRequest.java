package com.creed.resource.envmatrix.api.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.util.List;

/**
 * {@code POST /aes/records/decrypt}: decrypt stored rows, each with its own keys — the page picks,
 * per record, the "Keys and values" row with the same property key.
 */
public record AesRecordDecryptRequest(@NotEmpty @Size(max = 500) List<@Valid Item> items) {

    public record Item(
            @NotNull Long id,
            @Size(max = 256) String secretKey,
            @Size(max = 64) String iv,
            @Size(max = 256) String randomKey) {

        @Override
        public String toString() {
            return "Item[id=" + id + ", keys=***]";
        }
    }

    @Override
    public String toString() {
        return "AesRecordDecryptRequest[items=" + items.size() + "]";
    }
}
