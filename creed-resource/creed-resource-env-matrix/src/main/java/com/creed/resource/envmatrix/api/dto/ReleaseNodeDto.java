package com.creed.resource.envmatrix.api.dto;

import com.creed.resource.envmatrix.domain.EnvReleaseNode;

/**
 * Read model for one participant.
 *
 * @param country {@code "*"} when the slice is not country-specific
 */
public record ReleaseNodeDto(
        Long id,
        String appSystem,
        String country,
        String envInstance,
        String label,
        String note) {

    public static ReleaseNodeDto of(EnvReleaseNode node) {
        return new ReleaseNodeDto(
                node.getId(), node.getAppSystem(), node.getCountry(), node.getEnvInstance(),
                node.getLabel(), node.getNote());
    }
}
