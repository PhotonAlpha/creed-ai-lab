package com.creed.resource.envmatrix.api.dto;

import java.util.List;

/**
 * One {@code (service, country)} intersection of the matrix.
 *
 * <p>A cell aggregates every endpoint at that intersection — typically the Active-Standby pair
 * ({@code Green} / {@code Green2}) times the schemes in use — which is why {@code endpoints} is a
 * list rather than a single mapping.
 *
 * @param conflict      {@code true} when any endpoint in the cell is in a conflict group; drives the
 *                      cell highlight
 * @param conflictCount how many of the cell's endpoints are conflicting, shown as a badge
 */
public record MatrixCell(
        String service,
        String country,
        List<EndpointDto> endpoints,
        boolean conflict,
        int conflictCount) {
}
