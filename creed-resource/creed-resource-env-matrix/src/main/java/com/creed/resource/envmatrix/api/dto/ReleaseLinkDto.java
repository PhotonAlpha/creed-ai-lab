package com.creed.resource.envmatrix.api.dto;

import com.creed.resource.envmatrix.domain.EnvReleaseLink;

/** Read model for one connection. Both ends are participant ids within the same release. */
public record ReleaseLinkDto(
        Long id,
        Long sourceNodeId,
        Long targetNodeId,
        LinkDirection direction,
        String note) {

    public static ReleaseLinkDto of(EnvReleaseLink link) {
        return new ReleaseLinkDto(
                link.getId(), link.getSourceNodeId(), link.getTargetNodeId(),
                link.getDirection(), link.getNote());
    }
}
