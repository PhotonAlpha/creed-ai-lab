package com.creed.resource.migrationrisk.service;

import com.creed.resource.migrationrisk.config.MigrationRiskProperties;
import com.creed.resource.migrationrisk.domain.ElementKind;
import com.creed.resource.migrationrisk.domain.Location;
import com.creed.resource.migrationrisk.domain.RiskCategory;
import com.creed.resource.migrationrisk.domain.RiskItem;
import com.creed.resource.migrationrisk.domain.TargetState;
import com.creed.resource.migrationrisk.domain.UpstreamChange;
import com.creed.resource.migrationrisk.git.ChangedFile;
import com.creed.resource.migrationrisk.service.AnalysisContext.Side;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.regex.Pattern;

/**
 * Config compared key by key across <em>every</em> properties / yml file of each tree, not file by file.
 *
 * <p>A file-by-file diff answers the wrong question: a key repo2 keeps in {@code application.properties} instead of
 * {@code application.yml} (or in another module, under {@code config/}, in repo2B) reads as one removal plus one
 * addition. Here each tree is reduced to {@code key -> {file -> value}} (keys named by {@link ConfigDiff#globalKeys}),
 * and a key differs only when its <em>set of values</em> differs between repo1 and repo2 (both repo2s). A key repo1 has
 * and repo2 holds only under its Spring Boot new name ({@link ConfigRenames}) is compared under that name.
 */
@Component
@Slf4j
public class GlobalConfigAnalyzer {

    /** Files listed per side in an item's details; beyond that "... N more". */
    private static final int MAX_FILES = 5;

    private final Pattern configFiles;
    private final ConfigRenames renames;

    public GlobalConfigAnalyzer(MigrationRiskProperties properties, ConfigRenames renames) {
        this.configFiles = Pattern.compile(properties.configFiles());
        this.renames = renames;
    }

    public boolean handles(String path) {
        return path != null && ConfigDiff.isConfigFile(path, configFiles);
    }

    /** key -> (file -> value) over one tree, plus the files that could not be parsed. */
    private record Index(Map<String, Map<String, String>> keys, Map<String, String> unparseable) {

        Map<String, String> get(String key) {
            return keys.getOrDefault(key, Map.of());
        }
    }

    public List<RiskItem> analyze(AnalysisContext ctx, List<String> warnings) {
        List<ChangedFile> changed = ctx.changes().stream()
                .filter(f -> handles(f.newPath()) || handles(f.oldPath())).toList();
        if (changed.isEmpty()) {
            return List.of(); // every config file identical on both sides: so are the keys
        }
        ctx.progress().stage(Progress.Stage.FILES, "comparing config keys across all properties / yml files");
        Map<String, Object> parsed = new HashMap<>();
        Index mine = index(ctx, Side.REPO1, parsed);
        Index theirs = index(ctx, Side.REPO2, parsed);
        if (!theirs.unparseable().isEmpty()) {
            warnings.add(theirs.unparseable().size() + " repo2 config file(s) could not be parsed and were left out of the"
                    + " key comparison, e.g. " + theirs.unparseable().keySet().iterator().next());
        }

        List<RiskItem> items = new ArrayList<>();
        for (ChangedFile f : changed) {
            String error = f.newPath() == null ? null : mine.unparseable().get(f.newPath());
            if (error != null) {
                // A templated yml, a broken file: still HIGH, just without key detail.
                items.add(RiskItem.of(RiskCategory.CONFIG_CHANGED, ElementKind.CONFIG, f.newPath(), UpstreamChange.MODIFIED,
                        new Location(f.newPath(), null, null), null, null, List.of(),
                        List.of("could not compare keys: " + error)));
            }
        }

        // repo2 keys that are the new name of a repo1 key: compared there, not "only in repo2"
        Set<String> renamedTo = new TreeSet<>();
        for (String key : mine.keys().keySet()) {
            if (theirs.get(key).isEmpty()) {
                renames.renamed(key).filter(r -> !theirs.get(r).isEmpty() && mine.get(r).isEmpty()).ifPresent(renamedTo::add);
            }
        }
        Set<String> keys = new TreeSet<>(mine.keys().keySet());
        keys.addAll(theirs.keys().keySet());
        for (String key : keys) {
            Map<String, String> here = mine.get(key);
            Map<String, String> there = theirs.get(key);
            if (new TreeSet<>(here.values()).equals(new TreeSet<>(there.values())) || renamedTo.contains(key)) {
                continue;
            }
            items.add(here.isEmpty() ? onlyInRepo2(key, there) : item(key, here, there, theirs));
        }
        log.info("config keys: {} repo1 / {} repo2, {} differ", mine.keys().size(), theirs.keys().size(), items.size());
        return items;
    }

    private RiskItem item(String key, Map<String, String> here, Map<String, String> there, Index theirs) {
        Set<String> values = new TreeSet<>(here.values());
        // Boot renamed the key: what repo2 holds under the new name is the answer (spring.redis -> spring.data.redis)
        Optional<String> renamed = there.isEmpty() ? renames.renamed(key).filter(r -> !theirs.get(r).isEmpty())
                : Optional.empty();
        Map<String, String> found = renamed.map(theirs::get).orElse(there);
        List<String> details = new ArrayList<>();
        details.add(found.isEmpty() ? "- " + key + " = " + String.join(", ", values) + "  (repo2: missing)"
                : "~ " + key + ": " + String.join(", ", values) + "  (repo2: " + String.join(", ", new TreeSet<>(found.values())) + ")");
        files("repo1: ", here, details);
        List<String> evidence = new ArrayList<>();
        if (renamed.isPresent()) {
            evidence.add("renamed in Spring Boot: " + key + " -> " + renamed.get());
            files("repo2 (" + renamed.get() + "): ", found, details);
        } else {
            files("repo2: ", found, details);
        }
        boolean inSync = renamed.isPresent() && new TreeSet<>(found.values()).equals(values);
        Location location = found.isEmpty() ? null : new Location(found.keySet().iterator().next(), null, null);
        return RiskItem.of(inSync ? RiskCategory.CONFIG_IN_SYNC : RiskCategory.CONFIG_CHANGED, ElementKind.CONFIG, key,
                found.isEmpty() ? UpstreamChange.ADDED : UpstreamChange.MODIFIED,
                new Location(here.keySet().iterator().next(), null, null), location,
                found.isEmpty() ? TargetState.MISSING : renamed.isPresent() ? TargetState.RELOCATED : TargetState.MODIFIED,
                evidence, details);
    }

    private static RiskItem onlyInRepo2(String key, Map<String, String> there) {
        List<String> details = new ArrayList<>();
        details.add("+ " + key + " = " + String.join(", ", new TreeSet<>(there.values())));
        files("repo2: ", there, details);
        return RiskItem.of(RiskCategory.ONLY_IN_REPO2, ElementKind.CONFIG, key, UpstreamChange.DELETED, null,
                new Location(there.keySet().iterator().next(), null, null), TargetState.EXTRA, List.of(), details);
    }

    private static void files(String prefix, Map<String, String> valueByFile, List<String> details) {
        int shown = 0;
        for (Map.Entry<String, String> e : valueByFile.entrySet()) {
            if (shown++ == MAX_FILES) {
                details.add(prefix + "... " + (valueByFile.size() - MAX_FILES) + " more files");
                break;
            }
            details.add(prefix + e.getValue() + " @ " + e.getKey());
        }
    }

    @SuppressWarnings("unchecked")
    private Index index(AnalysisContext ctx, Side side, Map<String, Object> parsed) {
        Map<String, Map<String, String>> keys = new TreeMap<>();
        Map<String, String> unparseable = new TreeMap<>();
        for (String path : ctx.paths(side)) {
            if (!handles(path)) {
                continue;
            }
            boolean test = CodeTags.isTest(path);
            if (test && ctx.skipTests()) {
                continue;
            }
            Optional<String> id = ctx.contentId(side, path);
            if (id.isEmpty()) {
                continue;
            }
            // The key names depend on the file name and the test scope, not only on the content.
            String cacheKey = id.get() + "|" + path.substring(path.lastIndexOf('/') + 1) + "|" + test;
            Object result = parsed.computeIfAbsent(cacheKey, k -> {
                try {
                    return ConfigDiff.globalKeys(path, ctx.text(side, path).orElse(null), test);
                } catch (RuntimeException e) {
                    return String.valueOf(e.getMessage()).lines().findFirst().orElse(e.getClass().getSimpleName());
                }
            });
            if (result instanceof String error) {
                unparseable.put(path, error);
                continue;
            }
            ((Map<String, String>) result).forEach((key, value) ->
                    keys.computeIfAbsent(key, k -> new TreeMap<>()).put(path, value));
        }
        return new Index(keys, unparseable);
    }

}
