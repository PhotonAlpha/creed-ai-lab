package com.creed.resource.envmatrix.service;

import com.creed.resource.envmatrix.api.dto.ConflictGroup;
import com.creed.resource.envmatrix.api.dto.ConflictScope;
import com.creed.resource.envmatrix.domain.EnvEndpoint;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;

/**
 * Finds endpoints that resolve to the same address where they ought to be unique.
 *
 * <p>Two keys are checked independently:
 * <ul>
 *   <li>{@code host:port} — the obvious clash, two logical endpoints pointing at one listener;</li>
 *   <li>{@code ip:port} — the clash that hides behind DNS, two hostnames resolving to one address.</li>
 * </ul>
 *
 * <p>Comparison happens inside a bucket determined by {@link ConflictScope}
 * ({@code env-matrix.conflict.scope}, default {@code TIER_ENV}), because the same address legitimately
 * recurs across separate environments. Note that endpoints differing only by {@code scheme} still
 * collide: one port cannot serve both http and https, so an http and an https row on the same
 * {@code host:port} is a genuine misconfiguration, not a false positive.
 */
@Component
@Slf4j
public class ConflictDetector {

    private final ConflictScope scope;

    public ConflictDetector(@Value("${env-matrix.conflict.scope:TIER_ENV}") ConflictScope scope) {
        this.scope = scope;
        log.info("conflict detection scope = {}", scope);
    }

    public ConflictScope scope() {
        return scope;
    }

    /**
     * @param endpoints the set to analyse — always the *filtered* set, so the highlighting the user
     *                  sees corresponds to what the current filter shows
     */
    public Report detect(Collection<EnvEndpoint> endpoints) {
        List<RawGroup> groups = new ArrayList<>();
        groups.addAll(groupsFor(endpoints, ConflictGroup.Kind.HOST_PORT, EnvEndpoint::hostPort));
        groups.addAll(groupsFor(endpoints, ConflictGroup.Kind.IP_PORT, EnvEndpoint::ipPort));

        List<RawGroup> deduped = dedupe(groups);

        Map<Long, List<String>> keysByEndpoint = new LinkedHashMap<>();
        for (RawGroup group : deduped) {
            for (EnvEndpoint endpoint : group.members()) {
                keysByEndpoint.computeIfAbsent(endpoint.getId(), k -> new ArrayList<>()).add(group.label());
            }
        }
        return new Report(scope, deduped, keysByEndpoint);
    }

    private List<RawGroup> groupsFor(Collection<EnvEndpoint> endpoints,
                                     ConflictGroup.Kind kind,
                                     Function<EnvEndpoint, String> keyFn) {
        // scopeKey -> address -> endpoints sharing it
        Map<String, Map<String, List<EnvEndpoint>>> buckets = new LinkedHashMap<>();
        for (EnvEndpoint endpoint : endpoints) {
            buckets.computeIfAbsent(scope.keyOf(endpoint), k -> new LinkedHashMap<>())
                    .computeIfAbsent(keyFn.apply(endpoint), k -> new ArrayList<>())
                    .add(endpoint);
        }
        List<RawGroup> groups = new ArrayList<>();
        buckets.forEach((scopeKey, byAddress) -> byAddress.forEach((address, members) -> {
            if (members.size() > 1) {
                groups.add(new RawGroup(kind, scopeKey, address, List.copyOf(members)));
            }
        }));
        return groups;
    }

    /**
     * When a set of endpoints shares both its hostname and its IP, the host:port and ip:port groups
     * carry identical membership and would be reported twice. Keep the host:port one — it names the
     * thing an operator will actually go and change.
     */
    private List<RawGroup> dedupe(List<RawGroup> groups) {
        Map<Set<Long>, Integer> slotByMembership = new HashMap<>();
        List<RawGroup> result = new ArrayList<>();
        for (RawGroup group : groups) {
            Set<Long> membership = new HashSet<>();
            group.members().forEach(e -> membership.add(e.getId()));
            Integer slot = slotByMembership.get(membership);
            if (slot == null) {
                slotByMembership.put(membership, result.size());
                result.add(group);
            } else if (result.get(slot).kind() == ConflictGroup.Kind.IP_PORT
                    && group.kind() == ConflictGroup.Kind.HOST_PORT) {
                result.set(slot, group);
            }
        }
        return result;
    }

    /** A conflict group before DTO assembly — still holding entities. */
    public record RawGroup(ConflictGroup.Kind kind, String scopeKey, String value, List<EnvEndpoint> members) {
        public String label() {
            return (kind == ConflictGroup.Kind.HOST_PORT ? "host:port " : "ip:port ") + value;
        }
    }

    /**
     * Detection result.
     *
     * @param keysByEndpoint endpoint id -&gt; the conflict labels it participates in; empty for clean rows
     */
    public record Report(ConflictScope scope, List<RawGroup> groups, Map<Long, List<String>> keysByEndpoint) {

        public boolean isConflicting(Long endpointId) {
            return keysByEndpoint.containsKey(endpointId);
        }

        public List<String> keysFor(Long endpointId) {
            return keysByEndpoint.getOrDefault(endpointId, List.of());
        }

        public static Report empty(ConflictScope scope) {
            return new Report(scope, List.of(), Map.of());
        }
    }
}
