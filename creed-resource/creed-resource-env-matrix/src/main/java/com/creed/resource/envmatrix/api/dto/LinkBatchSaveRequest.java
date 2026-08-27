package com.creed.resource.envmatrix.api.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import java.util.List;

/**
 * Payload of the config page's link editor — one tier's whole wiring in one transaction.
 *
 * <p>Unlike the endpoint batch save, this one is <strong>always</strong> authoritative for a single
 * tier: {@code tier} names the slice being replaced and any link in it that is absent from
 * {@code links} is deleted. Scoping the delete to one tier is what makes it safe to edit SIT
 * without having UAT's rows loaded — the endpoint editor has to hold the entire table for the same
 * reason, and that is the constraint this avoids.
 */
public record LinkBatchSaveRequest(
        @NotBlank String tier,
        @NotNull @Valid List<AppLinkRequest> links) {
}
