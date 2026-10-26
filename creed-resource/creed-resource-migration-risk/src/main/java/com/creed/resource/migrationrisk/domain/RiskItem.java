package com.creed.resource.migrationrisk.domain;

import java.util.List;

/**
 * One thing to look at before syncing repo1 into repo2.
 *
 * @param id          stable within one analysis (category + kind + element), for the UI's row keys
 * @param element     {@code com.acme.OrderService#cancel(String)}, a class name, a file path, a config key ...
 * @param upstream    where it is in repo1 (HEAD, or the baseline for a deletion)
 * @param target      where it is in repo2, when found ({@code target.repo} says which one when there are two)
 * @param targetState what repo2 holds there; null where the question does not apply (config, API usage)
 * @param evidence    the RefactoringMiner refactorings / rules that decided the level, verbatim
 * @param details     data lines: changed config keys, dependency versions, matched API lines ...
 * @param review      null for an analysis that was not saved under a release
 * @param module      the repo1 build module the file belongs to (nearest directory with a pom.xml /
 *                    build.gradle; {@code (root)} for the top level) — a filter tag, not part of the identity
 * @param test        test code or test resources ({@code src/test/**}, {@code *Test.java} ...)
 */
public record RiskItem(
        String id,
        RiskLevel level,
        RiskCategory category,
        ElementKind kind,
        String element,
        UpstreamChange change,
        Location upstream,
        Location target,
        TargetState targetState,
        List<String> evidence,
        List<String> details,
        Review review,
        String module,
        boolean test) {

    public RiskItem {
        evidence = evidence == null ? List.of() : List.copyOf(evidence);
        details = details == null ? List.of() : List.copyOf(details);
    }

    public static RiskItem of(RiskCategory category, ElementKind kind, String element, UpstreamChange change,
                              Location upstream, Location target, TargetState targetState,
                              List<String> evidence, List<String> details) {
        return of(category.level(), category, kind, element, change, upstream, target, targetState, evidence, details);
    }

    public static RiskItem of(RiskLevel level, RiskCategory category, ElementKind kind, String element,
                              UpstreamChange change, Location upstream, Location target, TargetState targetState,
                              List<String> evidence, List<String> details) {
        return new RiskItem(category + ":" + kind + ":" + element, level, category, kind, element, change,
                upstream, target, targetState, evidence, details, null, null, false);
    }

    public RiskItem withReview(Review review) {
        return new RiskItem(id, level, category, kind, element, change, upstream, target, targetState, evidence,
                details, review, module, test);
    }

    /** The module / test tags and, with two repo2s, which one holds the target. */
    public RiskItem tagged(String module, boolean test, Location target) {
        return new RiskItem(id, level, category, kind, element, change, upstream, target, targetState, evidence,
                details, review, module, test);
    }

    /** The path the item is about: repo1's, else repo2's. */
    public String path() {
        return upstream != null ? upstream.path() : target != null ? target.path() : null;
    }
}
