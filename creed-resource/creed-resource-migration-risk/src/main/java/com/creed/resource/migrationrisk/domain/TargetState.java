package com.creed.resource.migrationrisk.domain;

/** What repo2 holds for repo1's element. */
public enum TargetState {
    /** The same content (after normalization: formatting, Javadoc, imports do not count). */
    UNCHANGED,
    /** Present, with different content. */
    MODIFIED,
    /** Present under another class, package, name or path. */
    RELOCATED,
    /** Not found. */
    MISSING,
    /** Extracted from, inlined, split or merged. */
    SPLIT,
    /** Only repo2 has it. */
    EXTRA,
    /** Baseline analyses only: nothing there yet, the element was new upstream. */
    ABSENT
}
