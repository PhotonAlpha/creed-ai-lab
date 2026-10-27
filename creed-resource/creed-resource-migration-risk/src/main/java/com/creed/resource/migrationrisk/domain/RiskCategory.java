package com.creed.resource.migrationrisk.domain;

/**
 * Why an item has its level: repo1 HEAD compared with repo2 (repo2A + repo2B as one code base) directly.
 *
 * <p>Without a baseline nobody can tell which side made a difference — the migration on purpose, or the other team
 * since — so the categories describe <em>what differs</em>, not who changed it. The last five values come from the
 * baseline analyses this module used to run; they stay so releases saved before 2026-10-28 still load.
 */
public enum RiskCategory {
    /** HIGH — repo1 has the class / method / file and repo2 has it nowhere (same path, same file name, a move). */
    TARGET_MISSING(RiskLevel.HIGH),
    /** HIGH — repo2 extracted from, inlined, split or merged the element: no 1:1 counterpart to sync into. */
    TARGET_SPLIT(RiskLevel.HIGH),
    /** HIGH — a config key (any .properties / .yml, compared across all files) has another value in repo2, or none. */
    CONFIG_CHANGED(RiskLevel.HIGH),
    /** HIGH — the poms differ: a dependency, managed version, property or parent. */
    DEPENDENCY_CHANGED(RiskLevel.HIGH),
    /** MEDIUM — repo1 and repo2 both have the method / field set / class, with different content. */
    CONTENT_DIFFERS(RiskLevel.MEDIUM),
    /** MEDIUM — repo2 has the element under another class, package or name, and it differs. */
    RELOCATED(RiskLevel.MEDIUM),
    /**
     * MEDIUM — lines repo1 has and repo2 does not use an API the migration replaced (Jackson 2, javax.*, Boot 3
     * classes ...): port them with the conversion. Only reported next to another difference in the same file — on
     * its own it is the migration's own rewrite.
     */
    MIGRATION_API(RiskLevel.MEDIUM),
    /**
     * MEDIUM — repo2 has the file in another format ({@code .vm} Velocity -> {@code .ftl} FreeMarker, per
     * {@code migration-risk.extension-mappings}): compared through a drafted conversion, ported by hand.
     */
    TEMPLATE_CONVERTED(RiskLevel.MEDIUM),
    /** MEDIUM — a non-Java file (XML, scripts, templates ...) differs; the details carry the line diff. */
    NON_JAVA_FILE(RiskLevel.MEDIUM),
    /** LOW — repo2 has something repo1 does not (a class, method, file or config key the migration added). */
    ONLY_IN_REPO2(RiskLevel.LOW),
    /** LOW — repo2 has the same thing somewhere else (moved, renamed, converted template identical to the draft). */
    ALREADY_SYNCED(RiskLevel.LOW),
    /** LOW — a config key repo2 holds with repo1's value under its Spring Boot new name. */
    CONFIG_IN_SYNC(RiskLevel.LOW),

    /** Baseline analyses only (saved before 2026-10-28). */
    @Deprecated BOTH_MODIFIED(RiskLevel.MEDIUM),
    /** Baseline analyses only. */
    @Deprecated DELETED_UPSTREAM(RiskLevel.MEDIUM),
    /** Baseline analyses only. */
    @Deprecated UPSTREAM_REFACTORING(RiskLevel.MEDIUM),
    /** Baseline analyses only. */
    @Deprecated AUTO_SYNC(RiskLevel.LOW),
    /** Baseline analyses only. */
    @Deprecated NEW_CODE(RiskLevel.LOW);

    private final RiskLevel level;

    RiskCategory(RiskLevel level) {
        this.level = level;
    }

    public RiskLevel level() {
        return level;
    }
}
