package com.creed.resource.envmatrix.api.dto;

/**
 * One row of a bulk decrypt. Per row rather than all-or-nothing: records saved with different keys
 * sit side by side, and one that does not decrypt must not hide the ones that do.
 *
 * @param plainValue set on success
 * @param error      set on failure — {@code not_found}, {@code invalid_key_material} or {@code decrypt_failed}
 * @param message    the human-readable reason for {@code error}
 */
public record AesRecordDecryptResult(Long id, String plainValue, String error, String message) {

    @Override
    public String toString() {
        return "AesRecordDecryptResult[id=" + id + ", error=" + error + ", plainValue=***]";
    }
}
