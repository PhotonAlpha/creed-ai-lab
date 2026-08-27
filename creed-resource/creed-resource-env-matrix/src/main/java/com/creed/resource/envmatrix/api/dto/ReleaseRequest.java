package com.creed.resource.envmatrix.api.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/** Create/update payload for a release's own fields. Participants and links are saved separately. */
public record ReleaseRequest(
        @NotBlank @Size(max = 64) String name,
        @NotBlank @Size(max = 32) String tier,
        @NotNull ReleaseStatus status,
        @Size(max = 512) String note) {
}
