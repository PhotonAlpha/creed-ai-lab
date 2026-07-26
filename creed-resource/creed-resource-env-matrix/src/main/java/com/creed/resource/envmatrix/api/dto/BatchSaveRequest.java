package com.creed.resource.envmatrix.api.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;

import java.util.List;

/**
 * Payload of the config page's "save" action — the whole edited table in one transaction.
 *
 * <p>Rows with an {@code id} are updated, rows without one are inserted. When
 * {@code deleteMissing} is {@code true} the save is treated as authoritative for the rows it
 * carries: any endpoint whose id is absent from {@code endpoints} is deleted. That is what makes the
 * page's "delete a row then save" flow work, but it also means a partial payload with the flag set
 * would wipe the rest of the table — so the flag defaults to {@code false} and the UI only sets it
 * when it has the full, unfiltered set loaded.
 */
public record BatchSaveRequest(
        @NotNull @Valid List<EndpointRequest> endpoints,
        boolean deleteMissing) {
}
