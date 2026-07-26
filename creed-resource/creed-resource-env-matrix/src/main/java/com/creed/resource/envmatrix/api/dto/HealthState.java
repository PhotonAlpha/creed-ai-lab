package com.creed.resource.envmatrix.api.dto;

/** Health state of an endpoint, as reported by the (mockable) probe. */
public enum HealthState {
    UP,
    /** Reachable but slow / partially failing — only ever produced by the mock probe today. */
    DEGRADED,
    DOWN,
    /** Not probed, or the probe itself could not run. */
    UNKNOWN
}
