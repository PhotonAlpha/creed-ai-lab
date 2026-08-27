package com.creed.resource.envmatrix.api.dto;

import com.creed.resource.envmatrix.domain.EnvAppLink;

import java.time.Instant;

/** Read model for one declared app-system link. */
public record AppLinkDto(
        Long id,
        String tier,
        String sourceApp,
        String targetApp,
        LinkDirection direction,
        String note,
        Instant createdAt,
        Instant updatedAt,
        Long version) {

    public static AppLinkDto of(EnvAppLink link) {
        return new AppLinkDto(
                link.getId(), link.getTier(), link.getSourceApp(), link.getTargetApp(),
                link.getDirection(), link.getNote(),
                link.getCreatedAt(), link.getUpdatedAt(), link.getVersion());
    }
}
