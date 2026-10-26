package com.creed.resource.migrationrisk.service;

import com.creed.resource.migrationrisk.config.MigrationRiskProperties;
import com.creed.resource.migrationrisk.domain.ElementKind;
import com.creed.resource.migrationrisk.domain.Location;
import com.creed.resource.migrationrisk.domain.RiskCategory;
import com.creed.resource.migrationrisk.domain.RiskItem;
import com.creed.resource.migrationrisk.domain.UpstreamChange;
import com.creed.resource.migrationrisk.git.ChangedFile;
import com.creed.resource.migrationrisk.service.AnalysisContext.Side;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * Config compared key by key across <em>every</em> properties / yml file of each tree, not file by file.
 *
 * <p>A file-by-file diff answers the wrong question twice: a key repo1 moved from {@code application.yml} into
 * {@code application.properties} (or into another module, or under {@code config/}) reads as one removal plus one
 * addition, and a key repo2 keeps in a different file than repo1 — or in repo2B — reads as "missing". Here each
 * tree is reduced to {@code key -> {file -> value}} (keys named by {@link ConfigDiff#globalKeys}), and a key is an
 * upstream change only when its <em>set of values</em> changed between the baseline and HEAD. repo2 is then
 * asked the same way: does it hold exactly the new values, in any file of either repo2?
 */
@Component
@Slf4j
public class GlobalConfigAnalyzer {

    /** Files listed per side in an item's details; beyond that "... N more". */
    private static final int MAX_FILES = 5;

    private final Pattern configFiles;

    public GlobalConfigAnalyzer(MigrationRiskProperties properties) {
        this.configFiles = Pattern.compile(properties.configFiles());
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
            return List.of();
        }
        ctx.progress().stage(Progress.Stage.FILES, "comparing config keys across all properties / yml files");
        // Unchanged files have the same content id on both repo1 sides: parse each content once.
        Map<String, Object> parsed = new HashMap<>();
        Index before = index(ctx, Side.BASELINE, parsed);
        Index after = index(ctx, Side.HEAD, parsed);
        Index target = index(ctx, Side.TARGET, parsed);
        if (!target.unparseable().isEmpty()) {
            warnings.add(target.unparseable().size() + " repo2 config file(s) could not be parsed and were left out of the"
                    + " key comparison, e.g. " + target.unparseable().keySet().iterator().next());
        }

        List<RiskItem> items = new ArrayList<>();
        for (ChangedFile f : changed) {
            String path = f.newPath() != null ? f.newPath() : f.oldPath();
            // TreeMap: get(null) throws, and an added file has no old path.
            String error = f.newPath() != null ? after.unparseable().get(f.newPath()) : null;
            if (error == null && f.oldPath() != null) {
                error = before.unparseable().get(f.oldPath());
            }
            if (error != null) {
                // A templated yml, a broken file: still HIGH, just without key detail.
                items.add(RiskItem.of(RiskCategory.CONFIG_CHANGED, ElementKind.CONFIG, path, change(f),
                        new Location(path, null, null), null, null, List.of(),
                        List.of("could not compare keys: " + error)));
            }
        }

        Set<String> keys = new TreeSet<>(before.keys().keySet());
        keys.addAll(after.keys().keySet());
        int moved = 0;
        for (String key : keys) {
            Map<String, String> was = before.get(key);
            Map<String, String> now = after.get(key);
            Set<String> oldValues = new TreeSet<>(was.values());
            Set<String> newValues = new TreeSet<>(now.values());
            if (oldValues.equals(newValues)) {
                moved += was.keySet().equals(now.keySet()) ? 0 : 1;
                continue;
            }
            items.add(item(key, was, now, oldValues, newValues, target.get(key)));
        }
        if (moved > 0) {
            warnings.add(moved + " config key(s) only moved between files upstream, values unchanged: not reported");
        }
        log.info("config keys: {} baseline / {} head / {} repo2, {} changed, {} moved", before.keys().size(),
                after.keys().size(), target.keys().size(), items.size(), moved);
        return items;
    }

    private RiskItem item(String key, Map<String, String> was, Map<String, String> now, Set<String> oldValues,
                          Set<String> newValues, Map<String, String> inTarget) {
        UpstreamChange change = oldValues.isEmpty() ? UpstreamChange.ADDED
                : newValues.isEmpty() ? UpstreamChange.DELETED : UpstreamChange.MODIFIED;
        List<String> details = new ArrayList<>();
        details.add(switch (change) {
            case ADDED -> "+ " + key + " = " + String.join(", ", newValues);
            case DELETED -> "- " + key;
            default -> "~ " + key + ": " + String.join(", ", oldValues) + " -> " + String.join(", ", newValues);
        });
        // Where repo1 changed it: files whose value differs (a key that stayed put in one file is not news).
        Set<String> files = new TreeSet<>(was.keySet());
        files.addAll(now.keySet());
        List<String> changedIn = files.stream().filter(p -> !Objects.equals(was.get(p), now.get(p))).toList();
        files("repo1: ", changedIn.stream().collect(Collectors.toMap(p -> p,
                p -> now.containsKey(p) ? now.get(p) : "(removed)", (a, b) -> a, LinkedHashMap::new)), details);
        if (inTarget.isEmpty()) {
            details.add("repo2: missing");
        } else {
            files("repo2: ", inTarget, details);
        }
        boolean inSync = new TreeSet<>(inTarget.values()).equals(newValues);
        String upstreamPath = changedIn.stream().filter(now::containsKey).findFirst().orElse(changedIn.getFirst());
        Location target = inTarget.isEmpty() ? null : new Location(inTarget.keySet().iterator().next(), null, null);
        return RiskItem.of(inSync ? RiskCategory.CONFIG_IN_SYNC : RiskCategory.CONFIG_CHANGED, ElementKind.CONFIG, key,
                change, new Location(upstreamPath, null, null), target, null, List.of(), details);
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

    private static UpstreamChange change(ChangedFile file) {
        return switch (file.change()) {
            case ADDED -> UpstreamChange.ADDED;
            case DELETED -> UpstreamChange.DELETED;
            case MODIFIED, RENAMED -> UpstreamChange.MODIFIED;
        };
    }
}
