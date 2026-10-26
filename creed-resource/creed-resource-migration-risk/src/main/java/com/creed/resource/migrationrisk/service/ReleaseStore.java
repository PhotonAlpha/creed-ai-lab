package com.creed.resource.migrationrisk.service;

import com.creed.resource.migrationrisk.api.dto.AnalysisResponse;
import com.creed.resource.migrationrisk.api.dto.ReleaseViews.AnalysisSummary;
import com.creed.resource.migrationrisk.api.dto.ReleaseViews.ReleaseView;
import com.creed.resource.migrationrisk.api.dto.ReleaseViews.ReviewProgress;
import com.creed.resource.migrationrisk.domain.MrAnalysis;
import com.creed.resource.migrationrisk.domain.MrAnalysisRepository;
import com.creed.resource.migrationrisk.domain.MrItem;
import com.creed.resource.migrationrisk.domain.MrItemRepository;
import com.creed.resource.migrationrisk.domain.MrRelease;
import com.creed.resource.migrationrisk.domain.MrReleaseRepository;
import com.creed.resource.migrationrisk.domain.Review;
import com.creed.resource.migrationrisk.domain.ReviewStatus;
import com.creed.resource.migrationrisk.domain.RiskItem;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.json.JsonMapper;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * Saves analyses under a release and keeps each item's review.
 *
 * <p>A release is re-analysed as repo1 keeps moving, and nobody should review the same item twice: a new
 * run copies the verdict of the release's previous run for every item whose {@link #fingerprint} did not
 * change. Anything that did change comes back {@code PENDING}, remembering the verdict it had and which of its
 * parts are new or different since — the reviewer looks at those, not at the whole file again.
 *
 * <p>Re-runs also track the sync itself: a file repo2 already matches is SYNCED automatically ({@code auto}, never
 * carried over — the next run decides again), each item is tagged NEW / CHANGED / SAME against the previous run,
 * and what the previous run had but this one does not is "resolved" ({@link #resolved}).
 */
@Service
@Slf4j
public class ReleaseStore {

    /**
     * "L12: " prefixes on matched API lines ("+ L12: " / "- L12: " on template diffs) move with unrelated edits;
     * they must not reset a review.
     */
    private static final Pattern LINE_PREFIX = Pattern.compile("^([+≈-] )?L\\d+: ");

    private final MrReleaseRepository releases;
    private final MrAnalysisRepository analyses;
    private final MrItemRepository items;
    private final JsonMapper json;
    private final Clock clock;

    public ReleaseStore(MrReleaseRepository releases, MrAnalysisRepository analyses, MrItemRepository items,
                        JsonMapper json, Clock clock) {
        this.releases = releases;
        this.analyses = analyses;
        this.items = items;
        this.json = json;
        this.clock = clock;
    }

    @Transactional
    public AnalysisResponse save(String releaseName, String baselineRef, AnalysisResponse result) {
        MrRelease release = releases.findByName(releaseName).orElseGet(() -> {
            MrRelease r = new MrRelease();
            r.setName(releaseName);
            r.setCreatedAt(Instant.now(clock));
            return releases.save(r);
        });
        Optional<MrAnalysis> previousRun = analyses.findFirstByReleaseIdOrderByAnalyzedAtDescIdDesc(release.getId());
        Map<String, MrItem> previous = previousRun
                .map(a -> items.findByAnalysisIdOrderByIdAsc(a.getId()).stream()
                        .collect(Collectors.toMap(MrItem::getItemKey, Function.identity(), (a1, a2) -> a1)))
                .orElse(Map.of());

        MrAnalysis run = new MrAnalysis();
        run.setReleaseId(release.getId());
        run.setMode(result.source().mode());
        run.setRepo1Path(result.source().repo1Path());
        run.setRepo2Path(result.source().repo2Path());
        run.setRepo2bPath(result.source().repo2bPath());
        run.setPreviousAnalysisId(previousRun.map(MrAnalysis::getId).orElse(null));
        run.setBaselineRef(baselineRef);
        run.setTotalItems(result.counts().total());
        run.setHighItems(result.counts().high());
        run.setMediumItems(result.counts().medium());
        run.setLowItems(result.counts().low());
        run.setDurationMs(result.durationMillis());
        run.setAnalyzedAt(result.analyzedAt());
        run.setResultJson(json.writeValueAsString(result.withoutItems()));
        run = analyses.save(run);

        List<RiskItem> saved = new ArrayList<>();
        int carried = 0;
        int synced = 0;
        Instant now = Instant.now(clock);
        for (RiskItem item : result.items()) {
            MrItem row = new MrItem();
            row.setAnalysisId(run.getId());
            row.setItemKey(item.id());
            row.setFingerprint(fingerprint(item));
            row.setLevel(item.level());
            row.setCategory(item.category());
            row.setElement(item.element());
            row.setItemJson(json.writeValueAsString(item.withReview(null)));
            MrItem before = previous.get(item.id());
            boolean same = before != null && before.getFingerprint().equals(row.getFingerprint());
            row.setDelta(previousRun.isEmpty() ? null
                    : before == null ? Review.Delta.NEW : same ? Review.Delta.SAME : Review.Delta.CHANGED);
            if (item.inSync()) {
                row.setReviewStatus(ReviewStatus.SYNCED);
                row.setReviewAuto(true);
                row.setReviewNote("repo2 already has repo1 HEAD's version");
                row.setReviewedAt(now);
                synced++;
            } else if (before != null && same && before.getReviewStatus() != ReviewStatus.PENDING && !before.isReviewAuto()) {
                row.setReviewStatus(before.getReviewStatus());
                row.setReviewNote(before.getReviewNote());
                row.setReviewedBy(before.getReviewedBy());
                row.setReviewedAt(before.getReviewedAt());
                row.setCarriedOverFrom(before.getId());
                row.setJiraKey(before.getJiraKey());
                carried++;
            } else if (before != null && !same) {
                remember(before, row, item);
            } else if (before != null) {
                // unchanged and still pending (or an auto verdict that no longer holds): keep what it remembered
                row.setPreviousStatus(before.getPreviousStatus());
                row.setPreviousNote(before.getPreviousNote());
                row.setPreviousReviewer(before.getPreviousReviewer());
                row.setChangedParts(before.getChangedParts());
                row.setJiraKey(before.getJiraKey());
            }
            row = items.save(row);
            saved.add(item.withReview(row.review()));
        }
        log.info("saved analysis #{} under release '{}': {} items, {} reviews carried over, {} already synced",
                run.getId(), releaseName, saved.size(), carried, synced);
        return result.withSaved(run.getId(), releaseName, saved);
    }

    /**
     * The file changed since it was reviewed: back to PENDING, but keep the verdict it had and name the parts that
     * are new or different, so the reviewer re-checks those only. A verdict remembered from an earlier change (the
     * row was still PENDING) is kept, and its changed parts accumulate.
     */
    private void remember(MrItem before, MrItem row, RiskItem item) {
        row.setJiraKey(before.getJiraKey());
        Set<String> changed = new LinkedHashSet<>();
        if (before.getReviewStatus() != ReviewStatus.PENDING && !before.isReviewAuto()) {
            row.setPreviousStatus(before.getReviewStatus());
            row.setPreviousNote(before.getReviewNote());
            row.setPreviousReviewer(before.getReviewedBy());
        } else if (before.getPreviousStatus() != null) {
            row.setPreviousStatus(before.getPreviousStatus());
            row.setPreviousNote(before.getPreviousNote());
            row.setPreviousReviewer(before.getPreviousReviewer());
            if (before.getChangedParts() != null && !before.getChangedParts().isEmpty()) {
                changed.addAll(List.of(before.getChangedParts().split("\n")));
            }
        } else {
            return;
        }
        Map<String, String> old = new HashMap<>();
        partsOf(json.readValue(before.getItemJson(), RiskItem.class)).forEach(p -> old.put(p.id(), fingerprintText(p)));
        for (RiskItem part : partsOf(item)) {
            if (!fingerprintText(part).equals(old.get(part.id()))) {
                changed.add(part.id());
            }
        }
        row.setChangedParts(changed.isEmpty() ? null : String.join("\n", changed));
    }

    private static List<RiskItem> partsOf(RiskItem item) {
        return item.parts().isEmpty() ? List.of(item) : item.parts();
    }

    /**
     * What the release's previous run had and this one does not — synced and reverted alike: the file is no longer
     * a difference to sync. Empty for a first run.
     */
    @Transactional(readOnly = true)
    public List<RiskItem> resolved(long analysisId) {
        MrAnalysis run = analyses.findById(analysisId)
                .orElseThrow(() -> new NotFoundException("no saved analysis #" + analysisId));
        if (run.getPreviousAnalysisId() == null) {
            return List.of();
        }
        Set<String> current = items.findByAnalysisIdOrderByIdAsc(analysisId).stream()
                .map(MrItem::getItemKey).collect(Collectors.toSet());
        return items.findByAnalysisIdOrderByIdAsc(run.getPreviousAnalysisId()).stream()
                .filter(row -> !current.contains(row.getItemKey()))
                .map(row -> json.readValue(row.getItemJson(), RiskItem.class).withReview(row.review()))
                .toList();
    }

    /**
     * One Jira issue per saved item: summary = file + category, description = its findings and where repo2 has it.
     * Asking again returns the item with the key it already has.
     */
    @Transactional
    public RiskItem createJira(long itemId, JiraIssues jira) {
        MrItem row = items.findById(itemId).orElseThrow(() -> new NotFoundException("no item #" + itemId));
        RiskItem item = json.readValue(row.getItemJson(), RiskItem.class);
        if (row.getJiraKey() == null) {
            MrAnalysis run = analyses.findById(row.getAnalysisId()).orElseThrow();
            String release = releases.findById(run.getReleaseId()).map(MrRelease::getName).orElse("");
            StringBuilder d = new StringBuilder();
            d.append("Sync from repo1 (").append(run.getRepo1Path()).append(") into repo2 (").append(run.getRepo2Path())
                    .append(run.getRepo2bPath() == null ? "" : " + " + run.getRepo2bPath()).append("), release ")
                    .append(release).append(", analysis #").append(run.getId()).append(".\n\n");
            if (item.target() != null) {
                d.append("In repo2: ").append(item.target().repo() == null ? "" : item.target().repo() + ": ")
                        .append(item.target().path()).append("\n\n");
            }
            for (RiskItem p : partsOf(item)) {
                d.append("* ").append(p.level()).append(' ').append(p.category()).append(' ').append(p.element()).append('\n');
                p.details().forEach(line -> d.append("** {{").append(line).append("}}\n"));
            }
            if (row.getReviewNote() != null) {
                d.append("\nReview note: ").append(row.getReviewNote()).append('\n');
            }
            row.setJiraKey(jira.create("[migration " + release + "] " + item.element() + " — " + item.category(),
                    d.toString(), List.of("migration-sync")));
            row = items.saveAndFlush(row);
        }
        return item.withReview(row.review());
    }

    @Transactional(readOnly = true)
    public AnalysisResponse load(long analysisId) {
        MrAnalysis run = analyses.findById(analysisId)
                .orElseThrow(() -> new NotFoundException("no saved analysis #" + analysisId));
        AnalysisResponse stored = json.readValue(run.getResultJson(), AnalysisResponse.class);
        List<RiskItem> reviewed = items.findByAnalysisIdOrderByIdAsc(analysisId).stream()
                .map(row -> json.readValue(row.getItemJson(), RiskItem.class).withReview(row.review()))
                .toList();
        String release = releases.findById(run.getReleaseId()).map(MrRelease::getName).orElse(null);
        return stored.withSaved(run.getId(), release, reviewed);
    }

    @Transactional(readOnly = true)
    public List<ReleaseView> releases() {
        return releases.findAll().stream()
                .sorted((a, b) -> b.getCreatedAt().compareTo(a.getCreatedAt()))
                .map(r -> {
                    List<MrAnalysis> runs = analyses.findByReleaseIdOrderByAnalyzedAtDescIdDesc(r.getId());
                    return new ReleaseView(r.getId(), r.getName(), r.getDescription(), r.getCreatedAt(), runs.size(),
                            runs.isEmpty() ? null : summary(runs.getFirst(), r.getName()));
                })
                .toList();
    }

    @Transactional(readOnly = true)
    public List<AnalysisSummary> analyses(long releaseId) {
        MrRelease release = releases.findById(releaseId)
                .orElseThrow(() -> new NotFoundException("no release #" + releaseId));
        return analyses.findByReleaseIdOrderByAnalyzedAtDescIdDesc(releaseId).stream()
                .map(a -> summary(a, release.getName())).toList();
    }

    @Transactional
    public List<RiskItem> review(List<Long> itemIds, ReviewStatus status, String note, String reviewer) {
        List<MrItem> rows = items.findAllById(itemIds);
        if (rows.size() != itemIds.stream().distinct().count()) {
            throw new NotFoundException("unknown item id(s) among " + itemIds);
        }
        Instant now = Instant.now(clock);
        for (MrItem row : rows) {
            row.setReviewStatus(status);
            row.setReviewAuto(false);
            if (status != ReviewStatus.PENDING) {
                // a new verdict replaces what was remembered from before the change
                row.setPreviousStatus(null);
                row.setPreviousNote(null);
                row.setPreviousReviewer(null);
                row.setChangedParts(null);
            }
            row.setReviewNote(note == null || note.isBlank() ? null : note.trim());
            row.setReviewedBy(status == ReviewStatus.PENDING ? null : blankToNull(reviewer));
            row.setReviewedAt(status == ReviewStatus.PENDING ? null : now);
            row.setCarriedOverFrom(null);
        }
        // saveAllAndFlush: an optimistic-lock conflict must surface here (409), not at commit after the response.
        return items.saveAllAndFlush(rows).stream()
                .map(row -> json.readValue(row.getItemJson(), RiskItem.class).withReview(row.review()))
                .toList();
    }

    private AnalysisSummary summary(MrAnalysis a, String release) {
        int pending = 0;
        int safe = 0;
        int attention = 0;
        int synced = 0;
        for (Object[] row : items.countByStatus(a.getId())) {
            int n = ((Number) row[1]).intValue();
            switch ((ReviewStatus) row[0]) {
                case PENDING -> pending = n;
                case SAFE -> safe = n;
                case ATTENTION -> attention = n;
                case SYNCED -> synced = n;
            }
        }
        return new AnalysisSummary(a.getId(), release, a.getMode(), a.getRepo1Path(), a.getRepo2Path(), a.getRepo2bPath(),
                a.getBaselineRef(),
                new AnalysisResponse.Counts(a.getTotalItems(), a.getHighItems(), a.getMediumItems(), a.getLowItems()),
                new ReviewProgress(pending, safe, attention, synced), a.getDurationMs(), a.getAnalyzedAt(),
                a.getPreviousAnalysisId());
    }

    /**
     * What a reviewer judged: category, level, the element, where it is in repo2 and in what state, and the
     * evidence/details text — without line numbers, which shift whenever anything above them is edited.
     */
    static String fingerprint(RiskItem item) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(fingerprintText(item).getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    /** A file record's text includes its parts: any finding in the file that changes resets the file's review. */
    static String fingerprintText(RiskItem item) {
        String text = String.join("\u0000",
                item.level().name(), item.category().name(), item.kind().name(), item.element(),
                String.valueOf(item.change()), String.valueOf(item.targetState()),
                // repo only when it is repo2B: a single repo2 is repo2A, so adding repo2B to a release must not
                // reset every review (and prints saved before repo2B existed stay valid)
                item.target() == null ? "" : item.target().path() + "|" + item.target().element()
                        + (item.target().repo() == null || item.target().repo().equals(MigrationRiskService.REPO2A)
                        ? "" : "|" + item.target().repo()),
                String.join("\u0001", item.evidence()),
                item.details().stream().map(d -> LINE_PREFIX.matcher(d).replaceFirst("$1"))
                        .collect(Collectors.joining("\u0001")));
        // appended only when present, so element-level items saved before file records keep their prints
        return item.parts().isEmpty() ? text
                : text + "\u0002" + item.parts().stream().map(ReleaseStore::fingerprintText).collect(Collectors.joining("\u0003"));
    }

    private static String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s.trim();
    }

    public static class NotFoundException extends RuntimeException {
        public NotFoundException(String message) {
            super(message);
        }
    }
}
