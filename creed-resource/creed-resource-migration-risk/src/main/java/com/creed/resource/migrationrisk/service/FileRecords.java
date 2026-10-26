package com.creed.resource.migrationrisk.service;

import com.creed.resource.migrationrisk.domain.ElementKind;
import com.creed.resource.migrationrisk.domain.Location;
import com.creed.resource.migrationrisk.domain.RiskItem;
import com.creed.resource.migrationrisk.domain.UpstreamChange;
import com.creed.resource.migrationrisk.git.ChangedFile;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * One record per file: the unit a reviewer actually works in. The analyzers find methods, config keys and API
 * matches; reviewing each of those separately meant a dozen rows for one class, while the diff page already shows
 * the file line by line. So the element-level findings become the {@code parts} of their file's record, which
 * takes the riskiest part's level, category and target state.
 */
final class FileRecords {

    private FileRecords() {
    }

    /** {@code items}: element-level, tagged, sorted riskiest first. Returns file records in the same order. */
    static List<RiskItem> group(List<RiskItem> items, List<ChangedFile> changes) {
        Map<String, UpstreamChange> changeByPath = new HashMap<>();
        for (ChangedFile f : changes) {
            if (f.newPath() != null) {
                changeByPath.put(f.newPath(), switch (f.change()) {
                    case ADDED -> UpstreamChange.ADDED;
                    case DELETED -> UpstreamChange.DELETED;
                    case MODIFIED, RENAMED -> UpstreamChange.MODIFIED;
                });
            } else {
                changeByPath.put(f.oldPath(), UpstreamChange.DELETED);
            }
        }
        Map<String, List<RiskItem>> byFile = new LinkedHashMap<>();
        for (RiskItem item : items) {
            byFile.computeIfAbsent(Objects.requireNonNullElse(item.path(), item.element()), p -> new ArrayList<>()).add(item);
        }
        List<RiskItem> files = new ArrayList<>();
        byFile.forEach((path, parts) -> files.add(record(path, changeByPath.getOrDefault(path, parts.getFirst().change()), parts)));
        files.sort(Comparator.comparing(RiskItem::level).thenComparing(RiskItem::category).thenComparing(RiskItem::element));
        return files;
    }

    /** The same file record over fewer parts (an ignore rule took some away). */
    static RiskItem regroup(RiskItem file, List<RiskItem> parts) {
        return record(file.element(), file.change(), parts);
    }

    private static RiskItem record(String path, UpstreamChange change, List<RiskItem> parts) {
        RiskItem top = parts.getFirst();
        // where repo2 has the file: the riskiest part that found something
        Location target = parts.stream().map(RiskItem::target).filter(Objects::nonNull).findFirst()
                .map(t -> new Location(t.path(), null, null, t.repo())).orElse(null);
        return new RiskItem("FILE:" + path, top.level(), top.category(), ElementKind.FILE, path, change,
                new Location(path, null, null), target, top.targetState(), List.of(), List.of(), null, top.module(),
                top.test(), parts);
    }
}
