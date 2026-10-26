package com.creed.resource.migrationrisk.source;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;

/**
 * Several repositories read as one tree: repo2 when the migration split repo1 into two repositories
 * (repo2A + repo2B). Every lookup the analysis makes — same path, same file name, a config key anywhere — then
 * searches both, so a class repo2A does not have but repo2B does is RELOCATED, not TARGET_MISSING.
 *
 * <p>Paths are not prefixed: a module moved to repo2B at the same relative path must still match by path. A path
 * that exists in more than one member is read from the first ({@link #collisions()} lists them for a warning);
 * {@link #origin} says which member a path came from, for the item's {@code target.repo}.
 */
public final class MergedTree implements SourceTree {

    public record Member(String label, SourceTree tree) {
    }

    private final List<Member> members;
    private final Map<String, Member> owner = new TreeMap<>();
    private final List<String> collisions = new ArrayList<>();
    private Map<String, List<String>> byName;

    public MergedTree(List<Member> members) {
        this.members = List.copyOf(members);
        for (Member m : this.members) {
            for (String path : m.tree().paths()) {
                if (owner.putIfAbsent(path, m) != null) {
                    collisions.add(path);
                }
            }
        }
    }

    public List<Member> members() {
        return members;
    }

    /** Paths present in more than one member; the first member's copy is the one read. */
    public List<String> collisions() {
        return Collections.unmodifiableList(collisions);
    }

    /** The label of the member holding {@code path}; empty when none does. */
    public Optional<String> origin(String path) {
        return Optional.ofNullable(path == null ? null : owner.get(path)).map(Member::label);
    }

    @Override
    public Optional<String> read(String path) {
        Member m = path == null ? null : owner.get(path);
        return m == null ? Optional.empty() : m.tree().read(path);
    }

    @Override
    public Optional<String> contentId(String path) {
        Member m = path == null ? null : owner.get(path);
        return m == null ? Optional.empty() : m.tree().contentId(path);
    }

    @Override
    public Set<String> paths() {
        return Collections.unmodifiableSet(owner.keySet());
    }

    @Override
    public Map<String, List<String>> pathsByFileName() {
        if (byName == null) {
            Map<String, List<String>> map = new LinkedHashMap<>();
            owner.keySet().forEach(p -> map.computeIfAbsent(p.substring(p.lastIndexOf('/') + 1), n -> new ArrayList<>()).add(p));
            byName = map;
        }
        return byName;
    }
}
