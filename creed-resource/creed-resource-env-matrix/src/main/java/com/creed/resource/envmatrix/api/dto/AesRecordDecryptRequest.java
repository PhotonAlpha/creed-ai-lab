package com.creed.resource.envmatrix.api.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.util.List;

/**
 * {@code POST /aes/records/decrypt}: decrypt stored rows. Only the IV and the salt are supplied — the
 * Secret Key comes from the record itself ({@code randomKey + host + ip}). The page picks, per
 * record, the IV and salt of the "Keys and values" row with the same property key.
 */
public record AesRecordDecryptRequest(@NotEmpty @Size(max = 500) List<@Valid Item> items) {

    public record Item(@NotNull Long id, @Size(max = 64) String iv, @Size(max = 256) String salt) {

        @Override
        public String toString() {
            return "Item[id=" + id + ", iv=***, salt=***]";
        }
    }

    @Override
    public String toString() {
        return "AesRecordDecryptRequest[items=" + items.size() + "]";
    }
}
