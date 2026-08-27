package com.creed.resource.envmatrix.service;

import com.creed.resource.envmatrix.api.dto.AppLinkDto;
import com.creed.resource.envmatrix.api.dto.AppLinkRequest;
import com.creed.resource.envmatrix.api.dto.LinkBatchSaveRequest;
import com.creed.resource.envmatrix.api.dto.LinkBatchSaveResponse;
import com.creed.resource.envmatrix.domain.EnvAppLink;
import com.creed.resource.envmatrix.domain.EnvAppLinkRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * CRUD for the declared app-system links — the topology graph's edges.
 *
 * <p>Deliberately separate from {@link EnvMatrixService}: links share no logic with endpoints. They
 * have no conflict detection, no health probe and no seven-dimension identity, and folding them into
 * a service already carrying all three would only obscure both.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class AppLinkService {

    private final EnvAppLinkRepository repository;

    @Transactional(readOnly = true)
    public List<AppLinkDto> list(String tier) {
        List<EnvAppLink> rows = StringUtils.hasText(tier)
                ? repository.findByTierOrderBySourceAppAscTargetAppAsc(tier)
                : repository.findAllByOrderByTierAscSourceAppAscTargetAppAsc();
        return rows.stream().map(AppLinkDto::of).toList();
    }

    @Transactional(readOnly = true)
    public AppLinkDto get(Long id) {
        return AppLinkDto.of(repository.findById(id).orElseThrow(() -> new LinkNotFoundException(id)));
    }

    @Transactional
    public AppLinkDto create(AppLinkRequest request) {
        rejectSelfLink(request);
        repository.findByTierAndSourceAppAndTargetApp(request.tier(), request.sourceApp(), request.targetApp())
                .ifPresent(existing -> {
                    throw new DuplicateLinkException(identityOf(request), existing.getId());
                });
        EnvAppLink saved = repository.save(apply(new EnvAppLink(), request));
        log.info("link created id={} {}", saved.getId(), identityOf(request));
        return AppLinkDto.of(saved);
    }

    @Transactional
    public AppLinkDto update(Long id, AppLinkRequest request) {
        rejectSelfLink(request);
        EnvAppLink existing = repository.findById(id).orElseThrow(() -> new LinkNotFoundException(id));
        repository.findByTierAndSourceAppAndTargetApp(request.tier(), request.sourceApp(), request.targetApp())
                .filter(other -> !other.getId().equals(id))
                .ifPresent(other -> {
                    throw new DuplicateLinkException(identityOf(request), other.getId());
                });
        return AppLinkDto.of(repository.save(apply(existing, request)));
    }

    @Transactional
    public void delete(Long id) {
        EnvAppLink existing = repository.findById(id).orElseThrow(() -> new LinkNotFoundException(id));
        repository.delete(existing);
        log.info("link deleted id={}", id);
    }

    /**
     * Replaces one tier's wiring in a single transaction — the config page's save.
     *
     * <p>Rows are validated in full before anything is written, so a bad row cannot leave the tier
     * half-saved. Links belonging to <em>other</em> tiers are never touched, however wrong the
     * payload is.
     */
    @Transactional
    public LinkBatchSaveResponse batchSave(LinkBatchSaveRequest request) {
        List<AppLinkRequest> rows = request.links();
        List<LinkBatchSaveResponse.Issue> issues = validate(request.tier(), rows);
        if (!issues.isEmpty()) {
            log.warn("link batch save rejected with {} issue(s)", issues.size());
            return LinkBatchSaveResponse.rejected(issues);
        }

        int inserted = 0;
        int updated = 0;
        List<Long> keptIds = new ArrayList<>();
        for (AppLinkRequest row : rows) {
            if (row.id() == null) {
                keptIds.add(repository.save(apply(new EnvAppLink(), row)).getId());
                inserted++;
            } else {
                EnvAppLink existing = repository.findById(row.id())
                        .orElseThrow(() -> new LinkNotFoundException(row.id()));
                // Same reasoning as the endpoint batch save: report what actually changed, and
                // leave untouched rows' updatedAt alone.
                if (differs(existing, row)) {
                    repository.save(apply(existing, row));
                    updated++;
                }
                keptIds.add(row.id());
            }
        }

        List<EnvAppLink> removable = repository.findByTierOrderBySourceAppAscTargetAppAsc(request.tier()).stream()
                .filter(link -> !keptIds.contains(link.getId()))
                .toList();
        repository.deleteAll(removable);

        log.info("link batch save applied tier={} inserted={} updated={} deleted={}",
                request.tier(), inserted, updated, removable.size());
        return new LinkBatchSaveResponse(true, inserted, updated, removable.size(), List.of());
    }

    // ---------------------------------------------------------------- helpers

    private List<LinkBatchSaveResponse.Issue> validate(String tier, List<AppLinkRequest> rows) {
        List<LinkBatchSaveResponse.Issue> issues = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        for (int index = 0; index < rows.size(); index++) {
            AppLinkRequest row = rows.get(index);
            if (!tier.equals(row.tier())) {
                issues.add(new LinkBatchSaveResponse.Issue(index, row.id(), "tier",
                        "row belongs to tier " + row.tier() + " but the save targets " + tier));
                continue;
            }
            if (row.sourceApp().equals(row.targetApp())) {
                issues.add(new LinkBatchSaveResponse.Issue(index, row.id(), "targetApp",
                        "a link cannot start and end at the same app system"));
                continue;
            }
            if (!seen.add(identityOf(row))) {
                issues.add(new LinkBatchSaveResponse.Issue(index, row.id(), "targetApp",
                        "duplicate link " + identityOf(row)));
            }
        }
        return issues;
    }

    private void rejectSelfLink(AppLinkRequest request) {
        if (request.sourceApp().equals(request.targetApp())) {
            throw new SelfLinkException(request.sourceApp());
        }
    }

    private static EnvAppLink apply(EnvAppLink link, AppLinkRequest request) {
        link.setTier(request.tier());
        link.setSourceApp(request.sourceApp());
        link.setTargetApp(request.targetApp());
        link.setDirection(request.direction());
        link.setNote(StringUtils.hasText(request.note()) ? request.note() : null);
        return link;
    }

    private static boolean differs(EnvAppLink stored, AppLinkRequest request) {
        return !Objects.equals(stored.getTier(), request.tier())
                || !Objects.equals(stored.getSourceApp(), request.sourceApp())
                || !Objects.equals(stored.getTargetApp(), request.targetApp())
                || !Objects.equals(stored.getDirection(), request.direction())
                || !Objects.equals(stored.getNote(), StringUtils.hasText(request.note()) ? request.note() : null);
    }

    private static String identityOf(AppLinkRequest request) {
        return request.tier() + " " + request.sourceApp() + " -> " + request.targetApp();
    }

    public static class LinkNotFoundException extends RuntimeException {
        public LinkNotFoundException(Long id) {
            super("no app link with id " + id);
        }
    }

    public static class DuplicateLinkException extends RuntimeException {
        public DuplicateLinkException(String identity, Long existingId) {
            super("link " + identity + " already exists (id " + existingId + ")");
        }
    }

    public static class SelfLinkException extends RuntimeException {
        public SelfLinkException(String appSystem) {
            super("a link cannot start and end at the same app system (" + appSystem + ")");
        }
    }
}
