package com.creed.resource.envmatrix.api.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * Create/update payload for a single endpoint.
 *
 * <p>{@code id} is only meaningful in a batch save, where it distinguishes "update this row" from
 * "insert a new row"; the single-row {@code POST}/{@code PUT} routes take the id from the path.
 */
public record EndpointRequest(
        Long id,

        @NotBlank @Size(max = 64) String appSystem,
        @NotBlank @Size(max = 32) String tier,
        @NotBlank @Size(max = 32) String envInstance,
        @NotBlank @Size(max = 16) String country,
        @NotBlank @Size(max = 64) String service,
        @NotBlank @Size(max = 32) String instance,

        // Only http/https are meaningful for the scheme filter; anything else would silently
        // create a filter option the UI has no sensible label for.
        @NotBlank @Pattern(regexp = "https?", message = "scheme must be 'http' or 'https'") String scheme,

        @NotBlank @Size(max = 255) String host,

        // Accepts IPv4 dotted-quad or a bare IPv6 form; deliberately permissive because the matrix
        // also records placeholder addresses for environments that are not built out yet.
        @NotBlank @Size(max = 45) String ip,

        @NotNull @Min(1) @Max(65535) Integer port,

        @Size(max = 512) String note) {
}
