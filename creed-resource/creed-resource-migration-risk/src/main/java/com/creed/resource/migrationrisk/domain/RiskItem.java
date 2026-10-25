package com.creed.resource.migrationrisk.domain;

import java.util.List;

/**
 * One thing to look at before syncing repo1 into repo2.
 *
 * @param id          stable within one analysis (category + kind + element), for the UI's row keys
 * @param element     {@code com.acme.OrderService#cancel(String)}, a class name, a file path ...
 * @param upstream    where it is in repo1 (HEAD, or the baseline for a deletion)
 * @param target      where it is in repo2, when found
 * @param targetState what repo2 holds there; null where the question does not apply (config, API usage)
 * @param evidence    the RefactoringMiner refactorings / rules that decided the level, verbatim
 * @param details     data lines: changed config keys, dependency versions, matched API lines ...
 * @param review      null for an analysis that was not saved under a release
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
        Review review) {

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
                upstream, target, targetState, evidence, details, null);
    }

    public RiskItem withReview(Review review) {
        return new RiskItem(id, level, category, kind, element, change, upstream, target, targetState, evidence,
                details, review);
    }
}
