package com.creed.resource.envmatrix.api.dto;

/**
 * The answer to {@code /aes/encrypt} or {@code /aes/decrypt} — exactly one of the two values is set.
 *
 * @param algorithm spelled out so the page can show what produced the value
 */
public record AesCryptoResponse(String encryptedValue, String plainValue, String algorithm) {

    @Override
    public String toString() {
        return "AesCryptoResponse[algorithm=" + algorithm + ", values=***]";
    }
}
