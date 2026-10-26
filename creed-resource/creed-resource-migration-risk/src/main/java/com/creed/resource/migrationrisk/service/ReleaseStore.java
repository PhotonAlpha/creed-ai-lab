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
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * Saves analyses under a release and keeps each item's review.
 *
 * <p>A release is re-analysed as repo1 keeps moving, and nobody should review the same item twice: a new
 * run copies the verdict of the release's previous run for every item whose {@link #fingerprint} did not
 * change. Anything that did change comes back {@code PENDING}.
 */
@Service
@Slf4j
public class ReleaseStore {

    /**
     * "L12: " prefixes on matched API lines ("+ L12: " / "- L12: " on template diffs) move with unrelated edits;
     * they must not reset a review.
     */
    private static final Pattern LINE_PREFIX = Pattern.compile("^([+-] )?L\\d+: ");

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
        Map<String, MrItem> previous = analyses.findFirstByReleaseIdOrderByAnalyzedAtDescIdDesc(release.getId())
                .map(a -> items.findByAnalysisIdOrderByIdAsc(a.getId()).stream()
                        .collect(Collectors.toMap(MrItem::getItemKey, Function.identity(), (a1, a2) -> a1)))
                .orElse(Map.of());

        MrAnalysis run = new MrAnalysis();
        run.setReleaseId(release.getId());
        run.setMode(result.source().mode());
        run.setRepo1Path(result.source().repo1Path());
        run.setRepo2Path(result.source().repo2Path());
        run.setRepo2bPath(result.source().repo2bPath());
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
            if (before != null && before.getReviewStatus() != ReviewStatus.PENDING
                    && before.getFingerprint().equals(row.getFingerprint())) {
                row.setReviewStatus(before.getReviewStatus());
                row.setReviewNote(before.getReviewNote());
                row.setReviewedBy(before.getReviewedBy());
                row.setReviewedAt(before.getReviewedAt());
                row.setCarriedOverFrom(before.getId());
                carried++;
            }
            row = items.save(row);
            saved.add(item.withReview(row.review()));
        }
        log.info("saved analysis #{} under release '{}': {} items, {} reviews carried over from the previous run",
                run.getId(), releaseName, saved.size(), carried);
        return result.withSaved(run.getId(), releaseName, saved);
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
        for (Object[] row : items.countByStatus(a.getId())) {
            int n = ((Number) row[1]).intValue();
            switch ((ReviewStatus) row[0]) {
                case PENDING -> pending = n;
                case SAFE -> safe = n;
                case ATTENTION -> attention = n;
            }
        }
        return new AnalysisSummary(a.getId(), release, a.getMode(), a.getRepo1Path(), a.getRepo2Path(), a.getRepo2bPath(),
                a.getBaselineRef(),
                new AnalysisResponse.Counts(a.getTotalItems(), a.getHighItems(), a.getMediumItems(), a.getLowItems()),
                new ReviewProgress(pending, safe, attention), a.getDurationMs(), a.getAnalyzedAt());
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
    private static String fingerprintText(RiskItem item) {
        String text = String.join("\u0000",
                item.level().name(), item.category().name(), item.kind().name(), item.element(),
                String.valueOf(item.change()), String.valueOf(item.targetState()),
                // repo appended only when set, so items saved before repo2 could be two repositories keep their prints
                item.target() == null ? "" : item.target().path() + "|" + item.target().element()
                        + (item.target().repo() == null ? "" : "|" + item.target().repo()),
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
