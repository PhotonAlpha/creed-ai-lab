package com.creed.resource.migrationrisk.service;

import com.creed.resource.migrationrisk.config.MigrationRiskProperties;
import com.creed.resource.migrationrisk.domain.Location;
import com.creed.resource.migrationrisk.domain.RiskCategory;
import com.creed.resource.migrationrisk.domain.RiskItem;
import com.creed.resource.migrationrisk.domain.UpstreamChange;
import com.creed.resource.migrationrisk.service.AnalysisContext.Side;
import com.creed.resource.migrationrisk.service.JavaStructure.MethodInfo;
import com.creed.resource.migrationrisk.service.JavaStructure.TypeInfo;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * Has the upstream change already arrived in repo2? The analyzers compare repo2 with the <em>baseline</em> (that is
 * how they tell migration edits from upstream ones), so a file someone already synced shows up as "changed on both
 * sides". This pass compares repo2 with repo1 <em>HEAD</em> instead and turns what matches into
 * {@link RiskCategory#ALREADY_SYNCED}: re-running a release then measures how far the sync has come.
 *
 * <p>"Matches" is per kind: a method's normalized text (imports and formatting do not count, so a method ported
 * with Jackson 3 imports still matches), a type's field set, a file's content id, a pom's changed keys. An API match
 * is synced once the rest of its file is and repo2's copy no longer uses the replaced API. Templates converted to
 * another syntax cannot be compared — a reviewer marks those SYNCED by hand.
 */
@Component
public class SyncDetector {

    /** Categories that still describe work to do and can be checked against HEAD. */
    private static final Set<RiskCategory> CHECKED = EnumSet.of(RiskCategory.AUTO_SYNC, RiskCategory.BOTH_MODIFIED,
            RiskCategory.RELOCATED, RiskCategory.NEW_CODE, RiskCategory.NON_JAVA_FILE, RiskCategory.DEPENDENCY_CHANGED);

    private final Map<String, Pattern> rules;

    public SyncDetector(MigrationRiskProperties properties) {
        this.rules = properties.apiRules().stream().collect(Collectors.toMap(MigrationRiskProperties.ApiRule::id,
                MigrationRiskProperties.ApiRule::compiled, (a, b) -> a, LinkedHashMap::new));
    }

    public List<RiskItem> mark(AnalysisContext ctx, List<RiskItem> items) {
        List<RiskItem> marked = new ArrayList<>(items.size());
        for (RiskItem item : items) {
            marked.add(CHECKED.contains(item.category()) && synced(ctx, item) ? synced(item) : item);
        }
        // API matches last: they depend on whether the rest of their file is synced.
        for (int i = 0; i < marked.size(); i++) {
            RiskItem item = marked.get(i);
            if (item.category() == RiskCategory.MIGRATION_API && apiPorted(ctx, item, marked)) {
                marked.set(i, synced(item));
            }
        }
        return marked;
    }

    private static RiskItem synced(RiskItem item) {
        List<String> evidence = new ArrayList<>(item.evidence());
        evidence.add("repo2 already has repo1 HEAD's version (was: " + item.category() + ")");
        // the old details argued why it differed ("repo2 changed this method ..."); only data lines stay
        List<String> details = item.kind() == com.creed.resource.migrationrisk.domain.ElementKind.DEPENDENCY
                ? item.details() : List.of();
        return RiskItem.of(RiskCategory.ALREADY_SYNCED, item.kind(), item.element(), item.change(), item.upstream(),
                item.target(), item.targetState(), evidence, details);
    }

    private boolean synced(AnalysisContext ctx, RiskItem item) {
        Location up = item.upstream();
        Location target = item.target();
        if (up == null || target == null || item.change() == UpstreamChange.DELETED) {
            return false;
        }
        return switch (item.kind()) {
            case METHOD -> method(ctx, up, target);
            case FIELDS -> type(ctx, Side.HEAD, up.path(), up.element()).map(TypeInfo::fields)
                    .equals(type(ctx, Side.TARGET, target.path(), target.element()).map(TypeInfo::fields))
                    && type(ctx, Side.HEAD, up.path(), up.element()).isPresent();
            case CLASS, FILE -> sameContent(ctx, up.path(), target.path());
            case DEPENDENCY -> {
                try {
                    yield ConfigDiff.pomInSync(ctx.text(Side.BASELINE, up.path()).orElse(null),
                            ctx.text(Side.HEAD, up.path()).orElse(null), ctx.text(Side.TARGET, target.path()).orElse(null));
                } catch (RuntimeException e) {
                    yield false;
                }
            }
            default -> false;
        };
    }

    /** {@code pkg.Type#key} on both sides (RefactoringMiner's own method names cannot be looked up). */
    private static boolean method(AnalysisContext ctx, Location up, Location target) {
        if (up.element() == null || target.element() == null || !up.element().contains("#") || !target.element().contains("#")) {
            return false;
        }
        Optional<MethodInfo> head = methodAt(ctx, Side.HEAD, up.path(), up.element());
        Optional<MethodInfo> there = methodAt(ctx, Side.TARGET, target.path(), target.element());
        return head.isPresent() && there.isPresent() && head.get().normalized().equals(there.get().normalized());
    }

    private static Optional<MethodInfo> methodAt(AnalysisContext ctx, Side side, String path, String element) {
        int hash = element.indexOf('#');
        return type(ctx, side, path, element.substring(0, hash)).flatMap(t -> t.method(element.substring(hash + 1)));
    }

    private static Optional<TypeInfo> type(AnalysisContext ctx, Side side, String path, String qualifiedName) {
        if (qualifiedName == null) {
            return Optional.empty();
        }
        String name = qualifiedName.contains("#") ? qualifiedName.substring(0, qualifiedName.indexOf('#')) : qualifiedName;
        return ctx.structure(side, path).flatMap(s -> s.type(name));
    }

    private static boolean sameContent(AnalysisContext ctx, String headPath, String targetPath) {
        Optional<String> head = ctx.contentId(Side.HEAD, headPath);
        return head.isPresent() && head.equals(ctx.contentId(Side.TARGET, targetPath));
    }

    /**
     * {@code path [ruleId]}: ported once every other finding in the file is synced and repo2's copy of the file
     * has no line the rule matches. Without any other finding to say where the file went, repo2's same path is used.
     */
    private boolean apiPorted(AnalysisContext ctx, RiskItem item, List<RiskItem> all) {
        String path = item.upstream() == null ? null : item.upstream().path();
        String element = item.element();
        int open = element.lastIndexOf(" [");
        Pattern rule = open < 0 ? null : rules.get(element.substring(open + 2, element.length() - 1));
        if (path == null || rule == null) {
            return false;
        }
        List<RiskItem> others = all.stream().filter(o -> o != item && o.upstream() != null
                && path.equals(o.upstream().path()) && o.category() != RiskCategory.MIGRATION_API).toList();
        if (!others.stream().allMatch(o -> o.category() == RiskCategory.ALREADY_SYNCED)) {
            return false;
        }
        String targetPath = others.stream().map(RiskItem::target).filter(Objects::nonNull).map(Location::path)
                .findFirst().orElse(path);
        return ctx.text(Side.TARGET, targetPath)
                .map(text -> text.lines().noneMatch(line -> rule.matcher(line).find()))
                .orElse(false);
    }
}
