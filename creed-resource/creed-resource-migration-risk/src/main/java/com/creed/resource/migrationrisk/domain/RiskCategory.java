package com.creed.resource.migrationrisk.domain;

/**
 * Why an item has its level. The level is fixed per category except {@link #NON_JAVA_FILE}, whose level
 * depends on what repo2 holds at the same path.
 */
public enum RiskCategory {
    /** HIGH — repo1 changed an element that repo2 no longer has (deleted, or renamed beyond recognition). */
    TARGET_MISSING(RiskLevel.HIGH),
    /** HIGH — the migration extracted from, inlined, split or merged the element: no 1:1 place to sync into. */
    TARGET_SPLIT(RiskLevel.HIGH),
    /** HIGH — added upstream lines use an API the migration replaced (Jackson 2, javax.*, Boot 3 classes ...). */
    MIGRATION_API(RiskLevel.HIGH),
    /**
     * HIGH — a config key (any .properties / .yml, compared across all files) changed upstream and repo2 does not
     * hold the new value anywhere: it may have been renamed or removed in Boot 4.
     */
    CONFIG_CHANGED(RiskLevel.HIGH),
    /** HIGH — pom.xml changed: the dependency may be BOM-managed, renamed or split in Boot 4. */
    DEPENDENCY_CHANGED(RiskLevel.HIGH),
    /** MEDIUM — the element exists in repo2 under another class, package or name. */
    RELOCATED(RiskLevel.MEDIUM),
    /** MEDIUM — the migration changed the same element: a three-way merge, not a copy. */
    BOTH_MODIFIED(RiskLevel.MEDIUM),
    /** MEDIUM — repo1 deleted it, repo2 still has it: decide whether repo2 drops it too. */
    DELETED_UPSTREAM(RiskLevel.MEDIUM),
    /** MEDIUM — repo1 itself renamed / moved / extracted something; replay the refactoring in repo2. */
    UPSTREAM_REFACTORING(RiskLevel.MEDIUM),
    /**
     * MEDIUM — repo2 converted the file to another format ({@code .vm} Velocity -> {@code .ftl} FreeMarker, per
     * {@code migration-risk.extension-mappings}): the upstream change has to be re-written by hand, not copied.
     */
    TEMPLATE_CONVERTED(RiskLevel.MEDIUM),
    /** LOW or MEDIUM — a non-Java file RefactoringMiner cannot read; compared against repo2 byte for byte. */
    NON_JAVA_FILE(null),
    /** LOW — repo2 has the element unchanged since the baseline: the upstream change applies as is. */
    AUTO_SYNC(RiskLevel.LOW),
    /**
     * LOW — repo2 already has repo1 HEAD's version (same normalized method / fields, same file content, same pom
     * values; for API matches: repo2's file no longer uses the replaced API): the change has been synced.
     */
    ALREADY_SYNCED(RiskLevel.LOW),
    /** LOW — a config key repo1 changed, and repo2 already holds the new value somewhere (any file, either repo2). */
    CONFIG_IN_SYNC(RiskLevel.LOW),
    /** LOW — new upstream code whose home exists in repo2. */
    NEW_CODE(RiskLevel.LOW);

    private final RiskLevel level;

    RiskCategory(RiskLevel level) {
        this.level = level;
    }

    public RiskLevel level() {
        if (level == null) {
            throw new IllegalStateException(name() + " has no fixed level");
        }
        return level;
    }
}
