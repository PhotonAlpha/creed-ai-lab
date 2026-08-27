package com.creed.resource.envmatrix.api.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * Create/update payload for one link.
 *
 * <p>{@code id} is only meaningful in a batch save, where it separates "update this row" from
 * "insert a new one"; the single-row routes take the id from the path.
 */
public record AppLinkRequest(
        Long id,

        @NotBlank @Size(max = 32) String tier,
        @NotBlank @Size(max = 64) String sourceApp,
        @NotBlank @Size(max = 64) String targetApp,
        @NotNull LinkDirection direction,

        @Size(max = 512) String note) {
}
