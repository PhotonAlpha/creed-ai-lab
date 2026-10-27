package com.creed.resource.migrationrisk.domain;

/**
 * How the element differs between repo1 and repo2: {@code ADDED} = only repo1 has it, {@code DELETED} = only repo2
 * has it, {@code MODIFIED} = both, different. {@code REFACTORED} comes from baseline analyses saved before 2026-10-28.
 * (The name is from those analyses too, where it said what repo1 changed since the baseline.)
 */
public enum UpstreamChange {
    ADDED, MODIFIED, DELETED, REFACTORED
}
