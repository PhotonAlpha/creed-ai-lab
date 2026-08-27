package com.creed.resource.envmatrix.api.dto;

import java.util.List;

/**
 * Everything the graph needs for one release, in one response.
 *
 * <p>Returned whole rather than as three separate routes because a link is meaningless without its
 * participants — fetching them separately would let the UI render a half-built graph while the
 * second request is still in flight.
 */
public record ReleaseTopologyDto(
        ReleaseDto release,
        List<ReleaseNodeDto> nodes,
        List<ReleaseLinkDto> links) {
}
