package com.creed.resource.envmatrix.api.dto;

import java.util.List;

/**
 * The matrix view model: {@code service} rows × {@code country} columns.
 *
 * <p>The row/column header lists are derived from the *filtered* result set, so the grid never shows
 * an empty row for a service that the current filter excluded. Cells are returned as a flat list
 * (rather than a nested array) so the frontend can index them by {@code service|country} and leave
 * genuinely empty intersections blank.
 *
 * @param services  row headers, sorted
 * @param countries column headers, sorted
 * @param cells     one entry per non-empty {@code (service, country)} intersection
 * @param conflicts every conflict group found in the filtered set, for the summary panel
 * @param total     number of endpoints in the filtered set
 * @param scope     the conflict scope the highlighting was computed with
 */
public record MatrixResponse(
        List<String> services,
        List<String> countries,
        List<MatrixCell> cells,
        List<ConflictGroup> conflicts,
        int total,
        ConflictScope scope) {
}
