package com.creed.resource.envmatrix.api.dto;

import com.creed.resource.envmatrix.domain.EnvRelease;

import java.time.Instant;

/**
 * Read model for a release.
 *
 * @param nodeCount how many participants it declares — shown in the picker so an empty release is
 *                  obvious before you select it
 * @param linkCount how many connections it declares
 */
public record ReleaseDto(
        Long id,
        String name,
        String tier,
        ReleaseStatus status,
        String note,
        int nodeCount,
        int linkCount,
        Instant createdAt,
        Instant updatedAt,
        Long version) {

    public static ReleaseDto of(EnvRelease release, int nodeCount, int linkCount) {
        return new ReleaseDto(
                release.getId(), release.getName(), release.getTier(), release.getStatus(),
                release.getNote(), nodeCount, linkCount,
                release.getCreatedAt(), release.getUpdatedAt(), release.getVersion());
    }
}
