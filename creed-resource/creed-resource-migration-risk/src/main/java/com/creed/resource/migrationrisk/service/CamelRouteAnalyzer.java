package com.creed.resource.migrationrisk.service;

import com.creed.resource.migrationrisk.domain.ElementKind;
import com.creed.resource.migrationrisk.domain.Location;
import com.creed.resource.migrationrisk.domain.RiskCategory;
import com.creed.resource.migrationrisk.domain.RiskItem;
import com.creed.resource.migrationrisk.domain.TargetState;
import com.creed.resource.migrationrisk.domain.UpstreamChange;
import com.creed.resource.migrationrisk.git.ChangedFile;
import com.creed.resource.migrationrisk.service.AnalysisContext.Side;
import lombok.extern.slf4j.Slf4j;
import org.eclipse.jgit.diff.Edit;
import org.eclipse.jgit.diff.HistogramDiff;
import org.eclipse.jgit.diff.RawText;
import org.eclipse.jgit.diff.RawTextComparator;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Camel XML routes compared <em>by route id</em>, across every route file of each tree — not file by file, and not
 * line by line.
 *
 * <p>A CamelContext keys its routes by id, and a migration moves them freely: into another XML file, into repo2B, from
 * {@code <camelContext>} into a {@code <routes>} file. So each tree is reduced to {@code route id -> steps}
 * ({@link CamelRoutes}) and a route is compared with the route of the same id wherever repo2 keeps it: same steps ->
 * nothing; different -> {@code CONTENT_DIFFERS} (or {@code RELOCATED} when in another file), the step diff in the
 * details; only repo1 -> {@code TARGET_MISSING}; only repo2 -> {@code ONLY_IN_REPO2}. What is not a route (beans,
 * {@code restConfiguration}, the REST DSL ...) is compared per file and reported as {@code NON_JAVA_FILE} when it differs.
 * A file that is not Camel XML, or does not parse, is left to {@link FileRiskAnalyzer}'s line diff.
 */
@Component
@Slf4j
public class CamelRouteAnalyzer {

    /** Step lines listed per finding; beyond that "... N more lines". */
    private static final int MAX_LINES = 20;

    /** The findings, and the differing files that are Camel route files (repo1's or repo2's path): not line-diffed again. */
    public record Result(List<RiskItem> items, Set<String> files) {
    }

    /** One tree: route key -> where and what; the non-route lines of each route file. */
    private record Index(Map<String, Placed> routes, Map<String, List<String>> rest) {
    }

    private record Placed(String path, CamelRoutes.Route route) {
    }

    public Result analyze(AnalysisContext ctx, List<String> warnings) {
        List<ChangedFile> changed = ctx.changes().stream().filter(f -> isXml(f.path())).toList();
        if (changed.isEmpty()) {
            return new Result(List.of(), Set.of());
        }
        ctx.progress().stage(Progress.Stage.FILES, "comparing Camel routes by route id");
        Map<String, Optional<CamelRoutes.Parsed>> parsed = new HashMap<>();
        Index mine = index(ctx, Side.REPO1, parsed, warnings);
        Index theirs = index(ctx, Side.REPO2, parsed, warnings);
        Set<String> files = new LinkedHashSet<>();
        for (ChangedFile f : changed) {
            if (f.newPath() != null && mine.rest().containsKey(f.newPath())) {
                files.add(f.newPath());
            }
            if (f.oldPath() != null && theirs.rest().containsKey(f.oldPath())) {
                files.add(f.oldPath());
            }
        }
        if (files.isEmpty()) {
            return new Result(List.of(), Set.of());
        }

        List<RiskItem> items = new ArrayList<>();
        Set<String> keys = new LinkedHashSet<>(mine.routes().keySet());
        keys.addAll(theirs.routes().keySet());
        for (String key : keys) {
            Placed here = mine.routes().get(key);
            Placed there = theirs.routes().get(key);
            String element = "route " + key;
            if (here == null) {
                items.add(RiskItem.of(RiskCategory.ONLY_IN_REPO2, ElementKind.ROUTE, element, UpstreamChange.DELETED,
                        null, new Location(there.path(), null, element), TargetState.EXTRA, List.of(),
                        prefixed("+ ", there.route().steps())));
            } else if (there == null) {
                items.add(RiskItem.of(RiskCategory.TARGET_MISSING, ElementKind.ROUTE, element, UpstreamChange.ADDED,
                        new Location(here.path(), null, element), null, TargetState.MISSING,
                        List.of("no route with this id in any repo2 Camel XML file"), prefixed("- ", here.route().steps())));
            } else if (!here.route().steps().equals(there.route().steps())) {
                boolean moved = !here.path().equals(there.path());
                items.add(RiskItem.of(moved ? RiskCategory.RELOCATED : RiskCategory.CONTENT_DIFFERS, ElementKind.ROUTE,
                        element, UpstreamChange.MODIFIED, new Location(here.path(), null, element),
                        new Location(there.path(), null, element), moved ? TargetState.RELOCATED : TargetState.MODIFIED,
                        moved ? List.of("repo2 has this route in " + there.path()) : List.of(),
                        diff(here.route().steps(), there.route().steps())));
            }
        }
        // what is not a route, per file present on both sides
        for (ChangedFile f : changed) {
            if (f.newPath() == null || f.oldPath() == null) {
                continue;
            }
            List<String> a = mine.rest().get(f.newPath());
            List<String> b = theirs.rest().get(f.oldPath());
            if (a != null && b != null && !a.equals(b)) {
                items.add(RiskItem.of(RiskCategory.NON_JAVA_FILE, ElementKind.FILE, f.newPath(), UpstreamChange.MODIFIED,
                        new Location(f.newPath(), null, null), new Location(f.oldPath(), null, null),
                        TargetState.MODIFIED, List.of("outside the routes (beans, contexts, REST DSL ...)"), diff(a, b)));
            }
        }
        log.info("Camel routes: {} repo1 / {} repo2, {} findings in {} route files", mine.routes().size(),
                theirs.routes().size(), items.size(), files.size());
        return new Result(items, files);
    }

    private static boolean isXml(String path) {
        return path != null && path.endsWith(".xml") && !ConfigDiff.isPom(path);
    }

    /** Every Camel XML file of one tree, parsed once per content id. Test resources follow {@code skipTests}. */
    private static Index index(AnalysisContext ctx, Side side, Map<String, Optional<CamelRoutes.Parsed>> parsed,
                               List<String> warnings) {
        Map<String, Placed> routes = new LinkedHashMap<>();
        Map<String, List<String>> rest = new LinkedHashMap<>();
        Set<String> duplicates = new HashSet<>();
        for (String path : ctx.paths(side)) {
            if (!isXml(path) || (ctx.skipTests() && CodeTags.isTest(path))) {
                continue;
            }
            Optional<String> id = ctx.contentId(side, path);
            if (id.isEmpty()) {
                continue;
            }
            Optional<CamelRoutes.Parsed> p = parsed.computeIfAbsent(id.get(), k -> {
                String text = ctx.text(side, path).orElse(null);
                if (!CamelRoutes.mayContainRoutes(text)) {
                    return Optional.empty();
                }
                try {
                    CamelRoutes.Parsed result = CamelRoutes.parse(text);
                    return result.routes().isEmpty() ? Optional.empty() : Optional.of(result);
                } catch (IllegalArgumentException e) {
                    return Optional.empty(); // broken XML: compared line by line instead
                }
            });
            if (p.isEmpty()) {
                continue;
            }
            rest.put(path, p.get().rest());
            p.get().routes().forEach((key, route) -> {
                if (routes.putIfAbsent(key, new Placed(path, route)) != null) {
                    duplicates.add(key);
                }
            });
        }
        if (!duplicates.isEmpty()) {
            warnings.add(duplicates.size() + " Camel route id(s) defined twice in " + (side == Side.REPO1 ? "repo1" : "repo2")
                    + "; the first file's route is compared, e.g. " + duplicates.iterator().next());
        }
        return new Index(routes, rest);
    }

    private static List<String> prefixed(String prefix, List<String> steps) {
        List<String> lines = new ArrayList<>();
        for (String s : steps) {
            if (lines.size() == MAX_LINES) {
                lines.add("... " + (steps.size() - MAX_LINES) + " more lines");
                break;
            }
            lines.add(prefix + s);
        }
        return lines;
    }

    /** {@code - step} (repo1's) / {@code + step} (repo2's), like the rest of the details: {@code git diff repo1 repo2}. */
    static List<String> diff(List<String> repo1, List<String> repo2) {
        RawText a = new RawText((String.join("\n", repo1) + "\n").getBytes(StandardCharsets.UTF_8));
        RawText b = new RawText((String.join("\n", repo2) + "\n").getBytes(StandardCharsets.UTF_8));
        List<String> lines = new ArrayList<>();
        int count = 0;
        for (Edit edit : new HistogramDiff().diff(RawTextComparator.DEFAULT, a, b)) {
            for (int i = edit.getBeginA(); i < edit.getEndA(); i++) {
                if (count++ < MAX_LINES) {
                    lines.add("- " + a.getString(i));
                }
            }
            for (int i = edit.getBeginB(); i < edit.getEndB(); i++) {
                if (count++ < MAX_LINES) {
                    lines.add("+ " + b.getString(i));
                }
            }
        }
        if (count > MAX_LINES) {
            lines.add("... " + (count - MAX_LINES) + " more lines");
        }
        return lines;
    }
}
