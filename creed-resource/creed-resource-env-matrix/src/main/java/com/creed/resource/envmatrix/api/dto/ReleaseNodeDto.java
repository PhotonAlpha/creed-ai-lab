package com.creed.resource.envmatrix.api.dto;

import com.creed.resource.envmatrix.domain.EnvReleaseNode;

/**
 * Read model for one participant.
 *
 * @param country   {@code "*"} when the slice is not country-specific
 * @param layer     the layer to draw it in, or {@code null} to derive it from the links
 * @param sortOrder position within that layer; {@code 0} is the default order
 */
public record ReleaseNodeDto(
        Long id,
        String appSystem,
        String country,
        String envInstance,
        String label,
        String note,
        Integer layer,
        Integer sortOrder) {

    public static ReleaseNodeDto of(EnvReleaseNode node) {
        return new ReleaseNodeDto(
                node.getId(), node.getAppSystem(), node.getCountry(), node.getEnvInstance(),
                node.getLabel(), node.getNote(),
                node.getLayer(), node.getSortOrder() == null ? 0 : node.getSortOrder());
    }
}
