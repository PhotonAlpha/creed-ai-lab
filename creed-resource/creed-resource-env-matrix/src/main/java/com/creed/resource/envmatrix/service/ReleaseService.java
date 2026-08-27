package com.creed.resource.envmatrix.service;

import com.creed.resource.envmatrix.api.dto.ReleaseDto;
import com.creed.resource.envmatrix.api.dto.ReleaseLinkDto;
import com.creed.resource.envmatrix.api.dto.ReleaseNodeDto;
import com.creed.resource.envmatrix.api.dto.ReleaseRequest;
import com.creed.resource.envmatrix.api.dto.ReleaseStatus;
import com.creed.resource.envmatrix.api.dto.ReleaseTopologyDto;
import com.creed.resource.envmatrix.api.dto.ReleaseTopologyRequest;
import com.creed.resource.envmatrix.api.dto.ReleaseTopologySaveResponse;
import com.creed.resource.envmatrix.domain.EnvRelease;
import com.creed.resource.envmatrix.domain.EnvReleaseLink;
import com.creed.resource.envmatrix.domain.EnvReleaseLinkRepository;
import com.creed.resource.envmatrix.domain.EnvReleaseNode;
import com.creed.resource.envmatrix.domain.EnvReleaseNodeRepository;
import com.creed.resource.envmatrix.domain.EnvReleaseRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * The release topology — participants and the connections between them.
 *
 * <p>Separate from {@link EnvMatrixService} because it shares nothing with endpoints: no conflict
 * detection, no health probe, no seven-dimension identity. The only thing the two have in common is
 * that a participant's {@code (appSystem, country, envInstance)} triple is matched against endpoint
 * rows at render time, and that happens in the frontend, not here.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class ReleaseService {

    private final EnvReleaseRepository releaseRepository;
    private final EnvReleaseNodeRepository nodeRepository;
    private final EnvReleaseLinkRepository linkRepository;

    // ------------------------------------------------------------------ releases

    @Transactional(readOnly = true)
    public List<ReleaseDto> list(String tier, ReleaseStatus status) {
        List<EnvRelease> rows;
        if (StringUtils.hasText(tier) && status != null) {
            rows = releaseRepository.findByTierAndStatusOrderByNameAsc(tier, status);
        } else if (StringUtils.hasText(tier)) {
            rows = releaseRepository.findByTierOrderByNameAsc(tier);
        } else if (status != null) {
            rows = releaseRepository.findByStatusOrderByTierAscNameAsc(status);
        } else {
            rows = releaseRepository.findAllByOrderByTierAscNameAsc();
        }
        return rows.stream().map(this::toDto).toList();
    }

    @Transactional(readOnly = true)
    public ReleaseDto get(Long id) {
        return toDto(require(id));
    }

    @Transactional
    public ReleaseDto create(ReleaseRequest request) {
        releaseRepository.findByName(request.name()).ifPresent(existing -> {
            throw new DuplicateReleaseException(request.name(), existing.getId());
        });
        EnvRelease saved = releaseRepository.save(apply(new EnvRelease(), request));
        log.info("release created id={} name={}", saved.getId(), saved.getName());
        return toDto(saved);
    }

    @Transactional
    public ReleaseDto update(Long id, ReleaseRequest request) {
        EnvRelease existing = require(id);
        releaseRepository.findByName(request.name())
                .filter(other -> !other.getId().equals(id))
                .ifPresent(other -> {
                    throw new DuplicateReleaseException(request.name(), other.getId());
                });
        return toDto(releaseRepository.save(apply(existing, request)));
    }

    /**
     * Deletes a release and everything in it.
     *
     * <p>Children are removed explicitly rather than left to the {@code on delete cascade} in
     * {@code V4}: the H2 schema the tests run against is generated from the entities, which map the
     * relationship as a plain id column and therefore carry no foreign key at all. Doing it here
     * makes the behaviour the same in both.
     */
    @Transactional
    public void delete(Long id) {
        EnvRelease existing = require(id);
        linkRepository.deleteByReleaseId(id);
        nodeRepository.deleteByReleaseId(id);
        releaseRepository.delete(existing);
        log.info("release deleted id={} name={}", id, existing.getName());
    }

    // ------------------------------------------------------------------ topology

    @Transactional(readOnly = true)
    public ReleaseTopologyDto topology(Long releaseId) {
        EnvRelease release = require(releaseId);
        List<EnvReleaseNode> nodes = nodesOf(releaseId);
        List<EnvReleaseLink> links = linkRepository.findByReleaseIdOrderByIdAsc(releaseId);
        return new ReleaseTopologyDto(
                ReleaseDto.of(release, nodes.size(), links.size()),
                nodes.stream().map(ReleaseNodeDto::of).toList(),
                links.stream().map(ReleaseLinkDto::of).toList());
    }

    /**
     * Replaces one release's participants and links in a single transaction.
     *
     * <p>Nodes are written first so that a link created in the same payload can point at a
     * participant that had no id when the request was built — see {@code ReleaseTopologyRequest}.
     * Everything is validated before anything is written, so a bad row cannot leave the release
     * half-saved.
     */
    @Transactional
    public ReleaseTopologySaveResponse saveTopology(Long releaseId, ReleaseTopologyRequest request) {
        require(releaseId);

        List<EnvReleaseNode> existingNodes = nodesOf(releaseId);
        Map<Long, EnvReleaseNode> existingById = new HashMap<>();
        existingNodes.forEach(node -> existingById.put(node.getId(), node));
        List<EnvReleaseLink> existingLinks = linkRepository.findByReleaseIdOrderByIdAsc(releaseId);
        Map<Long, EnvReleaseLink> existingLinkById = new HashMap<>();
        existingLinks.forEach(link -> existingLinkById.put(link.getId(), link));

        List<ReleaseTopologySaveResponse.Issue> issues =
                validate(request, existingById.keySet(), existingLinkById.keySet());
        if (!issues.isEmpty()) {
            log.warn("release {} topology save rejected with {} issue(s)", releaseId, issues.size());
            return ReleaseTopologySaveResponse.rejected(issues);
        }

        // ---- participants ----
        int nodesInserted = 0;
        int nodesUpdated = 0;
        List<Long> keptNodeIds = new ArrayList<>();
        /** Resolves a payload-local ref to the id the row actually got. */
        Map<String, Long> refToId = new HashMap<>();

        for (ReleaseTopologyRequest.Node row : request.nodes()) {
            EnvReleaseNode entity;
            if (row.id() == null) {
                entity = nodeRepository.save(apply(new EnvReleaseNode(), releaseId, row));
                nodesInserted++;
            } else {
                EnvReleaseNode stored = existingById.get(row.id());
                if (differs(stored, row)) {
                    entity = nodeRepository.save(apply(stored, releaseId, row));
                    nodesUpdated++;
                } else {
                    entity = stored;
                }
            }
            keptNodeIds.add(entity.getId());
            if (StringUtils.hasText(row.ref())) {
                refToId.put(row.ref(), entity.getId());
            }
        }

        // ---- links ----
        int linksInserted = 0;
        int linksUpdated = 0;
        List<Long> keptLinkIds = new ArrayList<>();
        for (ReleaseTopologyRequest.Link row : request.links()) {
            Long source = resolve(row.source(), refToId);
            Long target = resolve(row.target(), refToId);
            if (row.id() == null) {
                keptLinkIds.add(linkRepository.save(apply(new EnvReleaseLink(), releaseId, row, source, target)).getId());
                linksInserted++;
            } else {
                EnvReleaseLink stored = existingLinkById.get(row.id());
                if (differs(stored, row, source, target)) {
                    linkRepository.save(apply(stored, releaseId, row, source, target));
                    linksUpdated++;
                }
                keptLinkIds.add(row.id());
            }
        }

        // ---- removals, links first so no link is ever left pointing at a deleted participant ----
        List<EnvReleaseLink> removableLinks = existingLinks.stream()
                .filter(link -> !keptLinkIds.contains(link.getId()))
                .toList();
        linkRepository.deleteAll(removableLinks);

        List<EnvReleaseNode> removableNodes = existingNodes.stream()
                .filter(node -> !keptNodeIds.contains(node.getId()))
                .toList();
        nodeRepository.deleteAll(removableNodes);

        log.info("release {} topology saved nodes(+{} ~{} -{}) links(+{} ~{} -{})",
                releaseId, nodesInserted, nodesUpdated, removableNodes.size(),
                linksInserted, linksUpdated, removableLinks.size());
        return new ReleaseTopologySaveResponse(true,
                nodesInserted, nodesUpdated, removableNodes.size(),
                linksInserted, linksUpdated, removableLinks.size(), List.of());
    }

    // ------------------------------------------------------------------ validation

    private List<ReleaseTopologySaveResponse.Issue> validate(
            ReleaseTopologyRequest request, Set<Long> knownNodeIds, Set<Long> knownLinkIds) {

        List<ReleaseTopologySaveResponse.Issue> issues = new ArrayList<>();
        Set<String> identities = new HashSet<>();
        Set<String> refs = new HashSet<>();
        Set<Long> payloadNodeIds = new HashSet<>();

        for (int index = 0; index < request.nodes().size(); index++) {
            ReleaseTopologyRequest.Node row = request.nodes().get(index);
            if (row.id() != null && !knownNodeIds.contains(row.id())) {
                issues.add(issue("nodes", index, row.id(), "id",
                        "participant " + row.id() + " does not belong to this release"));
                continue;
            }
            if (row.id() != null) {
                payloadNodeIds.add(row.id());
            }
            String identity = row.appSystem() + "|" + row.country() + "|" + row.envInstance();
            if (!identities.add(identity)) {
                issues.add(issue("nodes", index, row.id(), "appSystem",
                        "duplicate participant " + identity.replace('|', ' ')));
            }
            if (StringUtils.hasText(row.ref()) && !refs.add(row.ref())) {
                issues.add(issue("nodes", index, row.id(), "ref", "duplicate ref " + row.ref()));
            }
        }

        Set<String> pairs = new HashSet<>();
        for (int index = 0; index < request.links().size(); index++) {
            ReleaseTopologyRequest.Link row = request.links().get(index);
            if (row.id() != null && !knownLinkIds.contains(row.id())) {
                issues.add(issue("links", index, row.id(), "id",
                        "link " + row.id() + " does not belong to this release"));
                continue;
            }
            String source = endOf(row.source(), payloadNodeIds, refs);
            String target = endOf(row.target(), payloadNodeIds, refs);
            if (source == null) {
                issues.add(issue("links", index, row.id(), "source",
                        "no participant in this payload matches " + row.source().describe()));
                continue;
            }
            if (target == null) {
                issues.add(issue("links", index, row.id(), "target",
                        "no participant in this payload matches " + row.target().describe()));
                continue;
            }
            if (source.equals(target)) {
                issues.add(issue("links", index, row.id(), "target",
                        "a link cannot start and end at the same participant"));
                continue;
            }
            // A -> B and B -> A are the same fact stated twice; BIDIRECTIONAL is how you say it once.
            String forward = source + ">" + target;
            String backward = target + ">" + source;
            if (pairs.contains(forward) || pairs.contains(backward)) {
                issues.add(issue("links", index, row.id(), "target",
                        "these two participants are already connected"));
                continue;
            }
            pairs.add(forward);
        }
        return issues;
    }

    /** Canonical key for a link end, or {@code null} when it resolves to nothing in the payload. */
    private static String endOf(ReleaseTopologyRequest.NodeRef ref, Set<Long> payloadIds, Set<String> refs) {
        if (ref == null || ref.isEmpty()) return null;
        if (ref.id() != null) return payloadIds.contains(ref.id()) ? "id:" + ref.id() : null;
        return refs.contains(ref.ref()) ? "ref:" + ref.ref() : null;
    }

    private static ReleaseTopologySaveResponse.Issue issue(
            String section, int index, Long id, String field, String message) {
        return new ReleaseTopologySaveResponse.Issue(section, index, id, field, message);
    }

    // ------------------------------------------------------------------ helpers

    private EnvRelease require(Long id) {
        return releaseRepository.findById(id).orElseThrow(() -> new ReleaseNotFoundException(id));
    }

    private List<EnvReleaseNode> nodesOf(Long releaseId) {
        return nodeRepository.findByReleaseIdOrderByAppSystemAscCountryAscEnvInstanceAsc(releaseId);
    }

    private ReleaseDto toDto(EnvRelease release) {
        return ReleaseDto.of(release,
                nodesOf(release.getId()).size(),
                linkRepository.findByReleaseIdOrderByIdAsc(release.getId()).size());
    }

    private static Long resolve(ReleaseTopologyRequest.NodeRef ref, Map<String, Long> refToId) {
        return ref.id() != null ? ref.id() : refToId.get(ref.ref());
    }

    private static EnvRelease apply(EnvRelease release, ReleaseRequest request) {
        release.setName(request.name());
        release.setTier(request.tier());
        release.setStatus(request.status());
        release.setNote(blankToNull(request.note()));
        return release;
    }

    private static EnvReleaseNode apply(EnvReleaseNode node, Long releaseId, ReleaseTopologyRequest.Node row) {
        node.setReleaseId(releaseId);
        node.setAppSystem(row.appSystem());
        node.setCountry(row.country());
        node.setEnvInstance(row.envInstance());
        node.setLabel(blankToNull(row.label()));
        node.setNote(blankToNull(row.note()));
        return node;
    }

    private static EnvReleaseLink apply(EnvReleaseLink link, Long releaseId,
                                        ReleaseTopologyRequest.Link row, Long source, Long target) {
        link.setReleaseId(releaseId);
        link.setSourceNodeId(source);
        link.setTargetNodeId(target);
        link.setDirection(row.direction());
        link.setNote(blankToNull(row.note()));
        return link;
    }

    private static boolean differs(EnvReleaseNode stored, ReleaseTopologyRequest.Node row) {
        return !Objects.equals(stored.getAppSystem(), row.appSystem())
                || !Objects.equals(stored.getCountry(), row.country())
                || !Objects.equals(stored.getEnvInstance(), row.envInstance())
                || !Objects.equals(stored.getLabel(), blankToNull(row.label()))
                || !Objects.equals(stored.getNote(), blankToNull(row.note()));
    }

    private static boolean differs(EnvReleaseLink stored, ReleaseTopologyRequest.Link row,
                                   Long source, Long target) {
        return !Objects.equals(stored.getSourceNodeId(), source)
                || !Objects.equals(stored.getTargetNodeId(), target)
                || !Objects.equals(stored.getDirection(), row.direction())
                || !Objects.equals(stored.getNote(), blankToNull(row.note()));
    }

    private static String blankToNull(String value) {
        return StringUtils.hasText(value) ? value : null;
    }

    public static class ReleaseNotFoundException extends RuntimeException {
        public ReleaseNotFoundException(Long id) {
            super("no release with id " + id);
        }
    }

    public static class DuplicateReleaseException extends RuntimeException {
        public DuplicateReleaseException(String name, Long existingId) {
            super("release '" + name + "' already exists (id " + existingId + ")");
        }
    }
}
