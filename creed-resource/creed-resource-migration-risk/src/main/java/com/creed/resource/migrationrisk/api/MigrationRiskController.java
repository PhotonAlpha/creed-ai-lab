package com.creed.resource.migrationrisk.api;

import com.creed.resource.migrationrisk.api.dto.AnalysisRequest;
import com.creed.resource.migrationrisk.api.dto.AnalysisResponse;
import com.creed.resource.migrationrisk.api.dto.DirectoryAnalysisRequest;
import com.creed.resource.migrationrisk.api.dto.MigrationRiskSettings;
import com.creed.resource.migrationrisk.api.dto.RefsView;
import com.creed.resource.migrationrisk.api.dto.ReleaseViews.AnalysisSummary;
import com.creed.resource.migrationrisk.api.dto.ReleaseViews.BatchReviewRequest;
import com.creed.resource.migrationrisk.api.dto.ReleaseViews.ReleaseView;
import com.creed.resource.migrationrisk.api.dto.ReleaseViews.ReviewRequest;
import com.creed.resource.migrationrisk.domain.RiskItem;
import com.creed.resource.migrationrisk.service.AnalysisJobs;
import com.creed.resource.migrationrisk.service.Progress;
import com.creed.resource.migrationrisk.service.ReleaseStore;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import org.springframework.web.bind.annotation.DeleteMapping;
import com.creed.resource.migrationrisk.service.BaselineAdvisor;
import com.creed.resource.migrationrisk.config.MigrationRiskProperties;
import com.creed.resource.migrationrisk.demo.DemoRepositories;
import com.creed.resource.migrationrisk.service.MigrationRiskService;
import com.creed.resource.migrationrisk.service.RepositoryGuard;
import jakarta.validation.Valid;

import java.util.List;
import java.util.Map;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/migration-risk")
public class MigrationRiskController {

    private final MigrationRiskService service;
    private final MigrationRiskProperties properties;
    private final RepositoryGuard guard;
    private final ObjectProvider<DemoRepositories> demo;
    private final BaselineAdvisor baselines;
    private final ReleaseStore store;
    private final AnalysisJobs jobs;

    public MigrationRiskController(MigrationRiskService service, MigrationRiskProperties properties,
                                   RepositoryGuard guard, ObjectProvider<DemoRepositories> demo,
                                   BaselineAdvisor baselines, ReleaseStore store, AnalysisJobs jobs) {
        this.store = store;
        this.jobs = jobs;
        this.service = service;
        this.properties = properties;
        this.guard = guard;
        this.demo = demo;
        this.baselines = baselines;
    }

    /** One repository's current branch, branches, tags and the recent commits of {@code head}. */
    @GetMapping("/refs")
    public RefsView refs(@RequestParam String path, @RequestParam(defaultValue = "HEAD") String head) {
        return baselines.refs(path, head);
    }

    /** The merge-base of the two selected heads when the repositories share history. */
    @GetMapping("/baseline-suggestion")
    public RefsView.Suggestion baselineSuggestion(@RequestParam String repo1Path,
                                                  @RequestParam(defaultValue = "HEAD") String repo1Head,
                                                  @RequestParam String repo2Path,
                                                  @RequestParam(defaultValue = "HEAD") String repo2Head) {
        return baselines.suggest(repo1Path, repo1Head, repo2Path, repo2Head);
    }

    @GetMapping("/settings")
    public MigrationRiskSettings settings() {
        DemoRepositories repos = demo.getIfAvailable();
        return new MigrationRiskSettings(
                repos == null ? null : repos.view(),
                guard.roots(),
                properties.apiRules().stream()
                        .map(r -> new MigrationRiskSettings.Rule(r.id(), r.pattern(), r.hint())).toList(),
                properties.maxChangedFiles());
    }

    /** Folder mode: baseline / repo1 / repo2 as three plain directories — no git history involved. */
    @PostMapping("/analyses/directories")
    public AnalysisResponse analyzeDirectories(@Valid @RequestBody DirectoryAnalysisRequest request) {
        AnalysisResponse result = service.analyzeDirectories(request);
        return request.release() == null ? result : store.save(request.release(), request.baselinePath(), result);
    }

    /**
     * Synchronous: RefactoringMiner over a change set of a few hundred files takes seconds, not minutes.
     * With {@code release} the result is saved (and earlier verdicts carried over) before it is returned.
     */
    @PostMapping("/analyses")
    public AnalysisResponse analyze(@Valid @RequestBody AnalysisRequest request) {
        AnalysisResponse result = service.analyze(request);
        return request.release() == null ? result
                : store.save(request.release(), result.source().baseline().id(), result);
    }

    // ---------------------------------------------------------------- background analyses + SSE progress

    /**
     * Starts a git-mode analysis in the background: 202 + {@code jobId}, then follow
     * {@code GET /analyses/jobs/{jobId}/events}. Paths and revisions are checked first, so a wrong baseline is
     * still an immediate 400 on its field rather than a failed job.
     */
    @PostMapping("/analyses/jobs")
    public ResponseEntity<Map<String, String>> startAnalysis(@Valid @RequestBody AnalysisRequest request) {
        MigrationRiskService.Inputs in = MigrationRiskService.inputs(request);
        service.validate(in);
        String id = jobs.start("git " + request.repo1Path() + " @ " + request.baseline() + " vs " + request.repo2Path(),
                progress -> save(request.release(), request.baseline(), service.analyze(in, progress), progress));
        return ResponseEntity.accepted().body(Map.of("jobId", id));
    }

    @PostMapping("/analyses/directories/jobs")
    public ResponseEntity<Map<String, String>> startDirectoryAnalysis(@Valid @RequestBody DirectoryAnalysisRequest request) {
        MigrationRiskService.Inputs in = MigrationRiskService.inputs(request);
        service.validate(in);
        String id = jobs.start("folders " + request.baselinePath() + " | " + request.repo1Path() + " | " + request.repo2Path(),
                progress -> save(request.release(), request.baselinePath(), service.analyze(in, progress), progress));
        return ResponseEntity.accepted().body(Map.of("jobId", id));
    }

    private AnalysisResponse save(String release, String baselineRef, AnalysisResponse result, Progress progress) {
        if (release == null) {
            return result;
        }
        progress.stage(Progress.Stage.SAVE, "saving under release " + release);
        String ref = result.source().baseline() != null ? result.source().baseline().id() : baselineRef;
        return store.save(release, ref, result);
    }

    /**
     * {@code text/event-stream}: {@code stage} / {@code progress} / {@code done} / {@code failed}. Earlier events are
     * replayed on (re)connect, so an EventSource that reconnects loses nothing.
     */
    @GetMapping(path = "/analyses/jobs/{id}/events", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public ResponseEntity<SseEmitter> jobEvents(@PathVariable String id) {
        return ResponseEntity.ok()
                .header("Cache-Control", "no-cache")
                // nginx / OpenShift router would otherwise buffer the stream until it ends.
                .header("X-Accel-Buffering", "no")
                .body(jobs.subscribe(id));
    }

    /** Status, last progress, and — once done — the full result (or the error). */
    @GetMapping("/analyses/jobs/{id}")
    public AnalysisJobs.JobView job(@PathVariable String id) {
        return jobs.view(id);
    }

    @DeleteMapping("/analyses/jobs/{id}")
    public ResponseEntity<Void> cancelJob(@PathVariable String id) {
        jobs.cancel(id);
        return ResponseEntity.accepted().build();
    }

    @GetMapping("/analyses/{id}")
    public AnalysisResponse savedAnalysis(@PathVariable long id) {
        return store.load(id);
    }

    @GetMapping("/releases")
    public List<ReleaseView> releases() {
        return store.releases();
    }

    @GetMapping("/releases/{id}/analyses")
    public List<AnalysisSummary> releaseAnalyses(@PathVariable long id) {
        return store.analyses(id);
    }

    /** SAFE = expected; ATTENTION = needs special care when syncing; PENDING = undo. */
    @PutMapping("/items/{id}/review")
    public RiskItem review(@PathVariable long id, @Valid @RequestBody ReviewRequest request) {
        return store.review(List.of(id), request.status(), request.note(), request.reviewer()).getFirst();
    }

    @PutMapping("/items/review")
    public List<RiskItem> reviewAll(@Valid @RequestBody BatchReviewRequest request) {
        return store.review(request.itemIds(), request.status(), request.note(), request.reviewer());
    }
}
