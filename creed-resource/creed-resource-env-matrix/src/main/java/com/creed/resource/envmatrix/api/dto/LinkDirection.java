package com.creed.resource.envmatrix.api.dto;

/**
 * Whether traffic on a declared link flows one way or both.
 *
 * <p>Purely presentational — it decides the arrowheads. Layering always follows the stored
 * {@code sourceApp -> targetApp} orientation, so a two-way link still has a defined upstream end.
 */
public enum LinkDirection {
    ONE_WAY,
    BIDIRECTIONAL
}
