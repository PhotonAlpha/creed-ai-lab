package com.creed.resource.veracode.service;

import com.creed.resource.veracode.api.dto.AnalysisResponse;
import com.creed.resource.veracode.api.dto.CreateIssuesResponse;
import com.creed.resource.veracode.api.dto.CreateIssuesResponse.Outcome;
import com.creed.resource.veracode.api.dto.CreateIssuesResponse.Result;
import com.creed.resource.veracode.api.dto.FindingStatus;
import com.creed.resource.veracode.api.dto.FindingView;
import com.creed.resource.veracode.api.dto.SastIssueView;
import com.creed.resource.veracode.config.JiraProperties;
import com.creed.resource.veracode.domain.Finding;
import com.creed.resource.veracode.domain.ReportRow;
import com.creed.resource.veracode.domain.SastFlaw;
import com.creed.resource.veracode.domain.SastIssue;
import com.creed.resource.veracode.service.JiraLookupService.IssueRef;
import com.creed.resource.veracode.service.VeracodeXmlParser.ParsedReport;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Uploaded Veracode XML reports -> findings -> Jira matches, and the creation of the missing issues.
 *
 * <p>Jira is never cached: an analysis is a read of Jira as it is now, which is what makes the creation
 * path safe. The reports are re-read from {@link UploadedReportStore} by id, and a create request names
 * findings by id with the whole chain re-read first, so a finding someone else filed in the meantime comes
 * back {@code ALREADY_EXISTS} instead of becoming a duplicate.
 *
 * <p>An upload may hold several reports - several Veracode applications. A finding is per application, its Jira
 * issue is not ({@link Finding#issueKey}): when another application already has the issue, or a finding of another
 * application in the same request just created it, the finding's application label is added to that issue instead
 * of filing a second one ({@code LABELED}).
 *
 * <p>Every request needs the user's {@link JiraToken}: it goes to every Jira call, so a shared page reads and
 * files as whoever is using it. Without one nothing is parsed or called ({@code jira_token_required}).
 *
 * <p>SAST flaws are filed too, as {@link SastIssue}s (one per CWE + category, open flaws only) in their own project
 * and issue type, found by summary alone. Reading them is part of every analysis but never fails it: a SAST project
 * Jira refuses is reported in {@code sast.error} and the SCA half still answers.
 *
 * <p>The create screen's other fields (Component/s, required custom fields) come with the request - the page's
 * choices - and are checked against the screen before the first issue is created: an unknown value, or a
 * required field left empty, fails the whole request instead of every finding one by one.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class VeracodeAnalysisService {

    private final VeracodeXmlParser parser;
    private final ReportArchive archive;
    private final UploadedReportStore uploads;
    private final JiraLookupService jira;
    private final JiraProperties jiraProps;
    private final Clock clock;

    /**
     * Serialises check-then-create within this process, so a double click cannot pass the existence
     * check twice. Two instances of this service would still race; Jira has no uniqueness constraint
     * to fall back on.
     */
    private final ReentrantLock createLock = new ReentrantLock();

    /** One uploaded file, as the controller hands it over. */
    public record UploadedFile(String filename, byte[] content) {
    }

    /**
     * @param byApplication each finding's report, for its issue's description
     * @param versions      the page's Jira versions, Jira's spelling - searched, and (one of them) filed under
     */
    private record Snapshot(String uploadId, List<ReportView> reports, Map<String, ReportView> byApplication,
                            List<Finding> findings, List<String> warnings, JiraLookupService.CreateMeta meta,
                            List<String> versions, Map<String, List<IssueRef>> matches, List<SastFlaw> sastFlaws) {

        ReportView reportOf(Finding f) {
            return byApplication.getOrDefault(f.application() == null ? "" : f.application(), reports.getFirst());
        }
    }

    /**
     * Parses and keeps the files, then analyses them together; the response's {@code uploadId} is what re-analyse,
     * create and export take.
     *
     * @param versionOverride the version shown instead of each report's {@code version}; blank = the report's.
     *                        Display only: Jira is searched and filed by {@code jiraVersions}
     * @param fields          create-screen values for {@code autoCreate} (fieldId -> value or values)
     * @param jiraVersions    the Jira versions chosen on the page (see {@link #analyze})
     */
    public AnalysisResponse analyzeUpload(List<UploadedFile> files, String versionOverride, boolean autoCreate,
                                          Map<String, Object> fields, List<String> jiraVersions, JiraToken token) {
        token.require();
        if (files.isEmpty()) {
            throw new IllegalArgumentException("no file: upload one or more Veracode detailed reports (XML, or a zip of them)");
        }
        boolean overridden = versionOverride != null && !versionOverride.isBlank();
        List<UploadedReportStore.ReportFile> reports = new ArrayList<>();
        // a zip counts as the reports in it
        List<UploadedFile> xml = files.stream().flatMap(f -> archive.expand(f).stream()).toList();
        for (UploadedFile file : xml) {
            ParsedReport parsed = parser.parse(file.filename(), file.content());
            String version = overridden ? versionOverride.trim() : parsed.info().version();
            reports.add(new UploadedReportStore.ReportFile(file.filename(), parsed.withVersion(version), version));
            log.info("'{}' ({} bytes): app {}, version {}{}, {} vulnerabilities", file.filename(),
                    file.content().length, parsed.info().appName(), version, overridden ? " (overridden)" : "",
                    parsed.rows().size());
        }
        UploadedReportStore.Upload upload = uploads.put(reports,
                files.stream().map(f -> Map.entry(f.filename(), f.content())).toList(), overridden);
        return analyze(upload.id(), autoCreate, fields, jiraVersions, token);
    }

    /**
     * @param jiraVersions issues are looked up among these Jira versions (empty = any version); creating files under
     *                     the one chosen, so {@code autoCreate} needs exactly one ({@code jira_version_required})
     */
    public AnalysisResponse analyze(String uploadId, boolean autoCreate, Map<String, Object> fields,
                                    List<String> jiraVersions, JiraToken token) {
        token.require();
        if (autoCreate) {
            createLock.lock();
        }
        try {
            Snapshot s = load(uploadId, jiraVersions, token);
            Map<String, Object> chosen = autoCreate ? jira.resolveFields(s.meta(), fields) : Map.of();
            String version = autoCreate ? jira.createVersion(s.versions()) : null;
            List<String> warnings = new ArrayList<>(s.warnings());
            Map<String, IssueRef> createdNow = new HashMap<>();
            List<FindingView> views = new ArrayList<>();
            for (Finding f : s.findings()) {
                List<IssueRef> issues = s.matches().getOrDefault(f.id(), List.of());
                FindingStatus status = issues.isEmpty() ? FindingStatus.MISSING : FindingStatus.EXISTS;
                if (autoCreate) {
                    try {
                        Result r = file(f, s, version, chosen, createdNow, token);
                        if (r.outcome() == Outcome.CREATED) {
                            status = FindingStatus.CREATED;
                        } else if (r.outcome() == Outcome.LABELED) {
                            status = FindingStatus.EXISTS;
                        }
                        issues = r.issues();
                    } catch (UpstreamException e) {
                        warnings.add(f.id() + ": not created - " + e.getMessage());
                    }
                }
                views.add(FindingView.of(f, jira.summary(f), status, issues, needsLabel(f, issues)));
            }
            return new AnalysisResponse(s.uploadId(), s.reports(), jiraProps.projectKey(),
                    List.copyOf(s.meta().versions().values()), s.versions(),
                    s.versions().size() == 1 ? s.versions().getFirst() : null, s.meta().fields(), views, warnings,
                    AnalysisResponse.Counts.of(views), s.sastFlaws(), sast(s.sastFlaws(), token),
                    Instant.now(clock));
        } finally {
            if (autoCreate) {
                createLock.unlock();
            }
        }
    }

    /** @param jiraVersions exactly one: the version the issues are filed under (and looked up in, first) */
    public CreateIssuesResponse create(String uploadId, List<String> findingIds, Map<String, Object> fields,
                                       List<String> jiraVersions, JiraToken token) {
        token.require();
        createLock.lock();
        try {
            Snapshot s = load(uploadId, jiraVersions, token);
            Map<String, Object> chosen = jira.resolveFields(s.meta(), fields);
            String version = jira.createVersion(s.versions());
            Map<String, Finding> byId = s.findings().stream()
                    .collect(Collectors.toMap(Finding::id, Function.identity(), (a, b) -> a, LinkedHashMap::new));
            Map<String, IssueRef> createdNow = new HashMap<>();
            List<Result> results = new ArrayList<>();
            for (String id : findingIds.stream().distinct().toList()) {
                Finding f = byId.get(id);
                if (f == null) {
                    results.add(new Result(id, Outcome.NOT_IN_REPORT, List.of(), "no such finding in this upload"));
                    continue;
                }
                try {
                    results.add(file(f, s, version, chosen, createdNow, token));
                } catch (UpstreamException e) {
                    log.warn("create failed for {}: {}", id, e.getMessage());
                    results.add(new Result(id, Outcome.FAILED, List.of(), e.getMessage()));
                }
            }
            return new CreateIssuesResponse(results);
        } finally {
            createLock.unlock();
        }
    }

    /**
     * One finding filed: its issue exists (found in Jira, or created by this request for another application's
     * finding of the same {@link Finding#issueKey}) -> labelled with the finding's application if it is not yet;
     * none -> created.
     */
    private Result file(Finding f, Snapshot s, String version, Map<String, Object> fields,
                        Map<String, IssueRef> createdNow, JiraToken token) {
        List<IssueRef> existing = s.matches().getOrDefault(f.id(), List.of());
        if (existing.isEmpty() && createdNow.containsKey(f.issueKey())) {
            existing = List.of(createdNow.get(f.issueKey()));
        }
        if (!existing.isEmpty()) {
            if (!needsLabel(f, existing)) {
                return new Result(f.id(), Outcome.ALREADY_EXISTS, existing, null);
            }
            IssueRef labelled = jira.addApplicationLabel(existing.getFirst(), f, token);
            List<IssueRef> issues = new ArrayList<>(existing);
            issues.set(0, labelled);
            createdNow.putIfAbsent(f.issueKey(), labelled);
            return new Result(f.id(), Outcome.LABELED, List.copyOf(issues),
                    "added label " + jira.applicationLabel(f) + " to " + labelled.key());
        }
        IssueRef created = jira.create(f, version, fields, s.reportOf(f), token);
        createdNow.put(f.issueKey(), created);
        return new Result(f.id(), Outcome.CREATED, List.of(created), null);
    }

    /** Issues exist, but none carries the finding's application label yet. */
    private boolean needsLabel(Finding f, List<IssueRef> issues) {
        String label = jira.applicationLabel(f);
        return !issues.isEmpty() && label != null && issues.stream().noneMatch(i -> i.hasLabel(label));
    }

    /**
     * The SAST half of an analysis: the open flaws' issues looked up in the SAST project. A Jira failure (no such
     * project or issue type, no permission) is the answer's {@code error}, not the analysis' - SCA is still shown.
     * Nothing is asked of Jira when no flaw is open.
     */
    private AnalysisResponse.SastJira sast(List<SastFlaw> flaws, JiraToken token) {
        List<SastIssue> issues = SastIssue.group(flaws);
        String project = jiraProps.sastProjectKey();
        String type = jiraProps.sastIssueType();
        if (issues.isEmpty()) {
            return new AnalysisResponse.SastJira(project, type, List.of(), false, false, List.of(), List.of(), List.of(),
                    new AnalysisResponse.Counts(0, 0, 0, 0, 0), null);
        }
        JiraLookupService.CreateMeta meta;
        Map<String, List<IssueRef>> matches;
        try {
            meta = jira.sastCreateMeta(token);
            matches = jira.findSast(issues, token);
        } catch (UpstreamException e) {
            log.warn("SAST issues not read from Jira ({} / {}): {}", project, type, e.getMessage());
            List<SastIssueView> views = issues.stream()
                    .map(i -> SastIssueView.of(i, jira.sastSummary(i), null, List.of())).toList();
            return new AnalysisResponse.SastJira(project, type, List.of(), false, false, List.of(), List.of(), views,
                    new AnalysisResponse.Counts(views.size(), 0, 0, 0, 0), e.getMessage());
        }
        List<SastIssueView> views = issues.stream().map(i -> {
            List<IssueRef> found = matches.getOrDefault(i.id(), List.of());
            return SastIssueView.of(i, jira.sastSummary(i), found.isEmpty() ? FindingStatus.MISSING : FindingStatus.EXISTS,
                    found);
        }).toList();
        int exists = (int) views.stream().filter(v -> v.status() == FindingStatus.EXISTS).count();
        log.info("SAST: {} open flaws -> {} issues, {} in Jira {}", flaws.stream().filter(SastFlaw::open).count(),
                issues.size(), exists, project);
        return new AnalysisResponse.SastJira(meta.projectKey(), meta.issueType(), List.copyOf(meta.versions().values()),
                meta.affectsVersion(), meta.versionRequired(), jira.sastDefaultVersions(meta), meta.fields(), views,
                new AnalysisResponse.Counts(views.size(), exists, views.size() - exists, 0, 0), null);
    }

    /**
     * Files SAST issues named by id, under the same lock as SCA: the upload and Jira are re-read first, so a second
     * click (or someone else's issue) answers {@code ALREADY_EXISTS}. Fixed and mitigated flaws make no issue, so
     * an id of theirs is {@code NOT_IN_REPORT}.
     *
     * @param jiraVersions the version chosen for SAST: exactly one when the issue type requires Affects Version/s,
     *                     at most one otherwise (none = the field left out)
     */
    public CreateIssuesResponse createSast(String uploadId, List<String> issueIds, Map<String, Object> fields,
                                           List<String> jiraVersions, JiraToken token) {
        token.require();
        createLock.lock();
        try {
            List<SastIssue> issues = SastIssue.group(read(uploadId).sastFlaws());
            JiraLookupService.CreateMeta meta = jira.sastCreateMeta(token);
            Map<String, Object> chosen = jira.resolveFields(meta, fields);
            List<String> selected = meta.affectsVersion() ? jira.selectedVersions(meta, jiraVersions) : List.of();
            String version = meta.versionRequired() || selected.size() > 1 ? jira.createVersion(selected)
                    : selected.isEmpty() ? null : selected.getFirst();
            Map<String, SastIssue> byId = issues.stream()
                    .collect(Collectors.toMap(SastIssue::id, Function.identity(), (a, b) -> a, LinkedHashMap::new));
            List<SastIssue> wanted = issueIds.stream().distinct().map(byId::get).filter(java.util.Objects::nonNull).toList();
            Map<String, List<IssueRef>> matches = jira.findSast(wanted, token);
            List<Result> results = new ArrayList<>();
            for (String id : issueIds.stream().distinct().toList()) {
                SastIssue issue = byId.get(id);
                if (issue == null) {
                    results.add(new Result(id, Outcome.NOT_IN_REPORT, List.of(),
                            "no open SAST flaw of this CWE and category in this upload"));
                    continue;
                }
                List<IssueRef> existing = matches.getOrDefault(id, List.of());
                if (!existing.isEmpty()) {
                    results.add(new Result(id, Outcome.ALREADY_EXISTS, existing, null));
                    continue;
                }
                try {
                    results.add(new Result(id, Outcome.CREATED, List.of(jira.createSast(issue, version, chosen, token)), null));
                } catch (UpstreamException e) {
                    log.warn("SAST create failed for {}: {}", id, e.getMessage());
                    results.add(new Result(id, Outcome.FAILED, List.of(), e.getMessage()));
                }
            }
            return new CreateIssuesResponse(results);
        } finally {
            createLock.unlock();
        }
    }

    /** The upload's files as received - what the Confluence export attaches. */
    public List<UploadedReportStore.SourceFile> sourceFiles(String uploadId) {
        return read(uploadId).upload().files();
    }

    /** The upload's reports, rows and flaws - no Jira. */
    private record Read(UploadedReportStore.Upload upload, List<ReportView> reports, Map<String, ReportView> byApplication,
                        List<ReportRow> rows, List<SastFlaw> sastFlaws, List<String> warnings) {
    }

    private Read read(String uploadId) {
        if (uploadId == null || uploadId.isBlank()) {
            throw new IllegalArgumentException("uploadId is required: upload a Veracode XML report first");
        }
        UploadedReportStore.Upload upload = uploads.get(uploadId.trim());
        List<ReportView> reports = new ArrayList<>();
        Map<String, ReportView> byApplication = new LinkedHashMap<>();
        List<ReportRow> rows = new ArrayList<>();
        List<SastFlaw> sastFlaws = new ArrayList<>();
        List<String> warnings = new ArrayList<>();
        for (UploadedReportStore.ReportFile file : upload.reports()) {
            VeracodeXmlParser.ReportInfo info = file.report().info();
            ReportView view = new ReportView(file.filename(), info.appName(), info.appId(), info.version(),
                    file.affectedVersion(), upload.versionOverridden(), info.policyName(), info.policyCompliance(),
                    info.generationDate(), file.report().sca(), file.report().sast());
            reports.add(view);
            byApplication.putIfAbsent(info.appName() == null ? "" : info.appName(), view);
            rows.addAll(file.report().rows());
            sastFlaws.addAll(file.report().sastFlaws());
            String prefix = upload.reports().size() > 1 ? file.filename() + ": " : "";
            file.report().warnings().forEach(w -> warnings.add(prefix + w));
        }
        return new Read(upload, List.copyOf(reports), byApplication, List.copyOf(rows), List.copyOf(sastFlaws),
                List.copyOf(warnings));
    }

    private Snapshot load(String uploadId, List<String> jiraVersions, JiraToken token) {
        Read read = read(uploadId);
        UploadedReportStore.Upload upload = read.upload();
        List<ReportView> reports = read.reports();
        List<ReportRow> rows = read.rows();
        List<SastFlaw> sastFlaws = read.sastFlaws();
        List<String> warnings = read.warnings();
        List<Finding> findings = Finding.group(rows);
        JiraLookupService.CreateMeta meta = jira.createMeta(token);
        List<String> versions = jira.selectedVersions(meta, jiraVersions);
        Map<String, List<IssueRef>> matches = jira.find(findings, versions, token);
        log.info("upload {}: {} report(s), {} vulnerabilities -> {} findings, {} matched in Jira, {} SAST flaws, {} warnings",
                upload.id(), reports.size(), rows.size(), findings.size(),
                matches.values().stream().filter(l -> !l.isEmpty()).count(), sastFlaws.size(), warnings.size());
        return new Snapshot(upload.id(), reports, read.byApplication(), findings, warnings, meta, versions, matches,
                sastFlaws);
    }
}
