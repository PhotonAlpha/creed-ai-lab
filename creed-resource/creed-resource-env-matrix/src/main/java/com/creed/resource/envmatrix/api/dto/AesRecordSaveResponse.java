package com.creed.resource.envmatrix.api.dto;

import java.util.List;

/** What a save did: rows created, rows whose value was replaced, and the rows as they now stand. */
public record AesRecordSaveResponse(int inserted, int updated, List<AesRecordDto> records) {
}
