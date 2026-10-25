package com.creed.resource.migrationrisk.domain;

/** What repo2 holds for the element, compared with repo1's baseline version of it. */
public enum TargetState {
    /** Same normalized declaration as at the baseline — the migration did not touch it. */
    UNCHANGED,
    /** Present, but the migration changed it. */
    MODIFIED,
    /** Present under another class, package or name. */
    RELOCATED,
    /** Not found. */
    MISSING,
    /** Extracted from, inlined, split or merged by the migration. */
    SPLIT,
    /** Nothing at that place yet — the element is new upstream. */
    ABSENT
}
