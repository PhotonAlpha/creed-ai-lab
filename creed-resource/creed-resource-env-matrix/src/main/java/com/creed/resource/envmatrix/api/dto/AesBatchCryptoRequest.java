package com.creed.resource.envmatrix.api.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.util.List;

/**
 * {@code POST /aes/encrypt/batch} and {@code /aes/decrypt/batch}: the page's "Keys and values" rows,
 * each a complete, independent set — its own Secret Key, IV and randomkey.
 *
 * <p>{@code value} is the plaintext for encrypt and the Base64 ciphertext for decrypt. Bean
 * validation only checks shape and size (a violation anywhere is one 400); whether a row's keys are
 * usable and whether its value decrypts is answered per row, in {@link AesBatchCryptoResult}.
 */
public record AesBatchCryptoRequest(@NotEmpty @Size(max = 200) List<@Valid Item> items) {

    public record Item(
            @Size(max = 256) String secretKey,
            @Size(max = 64) String iv,
            @Size(max = 256) String randomKey,
            @NotNull @Size(max = 16384) String value) {

        @Override
        public String toString() {
            return "Item[***]";
        }
    }

    @Override
    public String toString() {
        return "AesBatchCryptoRequest[items=" + items.size() + "]";
    }
}
