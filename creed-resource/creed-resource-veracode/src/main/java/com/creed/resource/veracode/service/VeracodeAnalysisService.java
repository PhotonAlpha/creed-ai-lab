package com.creed.resource.veracode.service;

import com.creed.resource.veracode.api.dto.AnalysisResponse;
import com.creed.resource.veracode.api.dto.CreateIssuesResponse;
import com.creed.resource.veracode.api.dto.CreateIssuesResponse.Outcome;
import com.creed.resource.veracode.api.dto.CreateIssuesResponse.Result;
import com.creed.resource.veracode.api.dto.FindingStatus;
import com.creed.resource.veracode.api.dto.FindingView;
import com.creed.resource.veracode.config.JiraProperties;
import com.creed.resource.veracode.domain.Finding;
import com.creed.resource.veracode.domain.ReportRow;
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
 * <p>The create screen's other fields (Component/s, required custom fields) come with the request - the page's
 * choices - and are checked against the screen before the first issue is created: an unknown value, or a
 * required field left empty, fails the whole request instead of every finding one by one.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class VeracodeAnalysisService {

    private final VeracodeXmlParser parser;
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
                            List<String> versions, Map<String, List<IssueRef>> matches) {

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
            throw new IllegalArgumentException("no file: upload one or more Veracode detailed reports (XML)");
        }
        boolean overridden = versionOverride != null && !versionOverride.isBlank();
        List<UploadedReportStore.ReportFile> reports = new ArrayList<>();
        for (UploadedFile file : files) {
            ParsedReport parsed = parser.parse(file.filename(), file.content());
            String version = overridden ? versionOverride.trim() : parsed.info().version();
            reports.add(new UploadedReportStore.ReportFile(file.filename(), parsed.withVersion(version), version));
            log.info("'{}' ({} bytes): app {}, version {}{}, {} vulnerabilities", file.filename(),
                    file.content().length, parsed.info().appName(), version, overridden ? " (overridden)" : "",
                    parsed.rows().size());
        }
        UploadedReportStore.Upload upload = uploads.put(reports, overridden);
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
                views.add(FindingView.of(f, status, issues, needsLabel(f, issues)));
            }
            return new AnalysisResponse(s.uploadId(), s.reports(), jiraProps.projectKey(),
                    List.copyOf(s.meta().versions().values()), s.versions(),
                    s.versions().size() == 1 ? s.versions().getFirst() : null, s.meta().fields(), views, warnings,
                    AnalysisResponse.Counts.of(views), Instant.now(clock));
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

    private Snapshot load(String uploadId, List<String> jiraVersions, JiraToken token) {
        if (uploadId == null || uploadId.isBlank()) {
            throw new IllegalArgumentException("uploadId is required: upload a Veracode XML report first");
        }
        UploadedReportStore.Upload upload = uploads.get(uploadId.trim());
        List<ReportView> reports = new ArrayList<>();
        Map<String, ReportView> byApplication = new LinkedHashMap<>();
        List<ReportRow> rows = new ArrayList<>();
        List<String> warnings = new ArrayList<>();
        for (UploadedReportStore.ReportFile file : upload.reports()) {
            VeracodeXmlParser.ReportInfo info = file.report().info();
            ReportView view = new ReportView(file.filename(), info.appName(), info.appId(), info.version(),
                    file.affectedVersion(), upload.versionOverridden(), info.policyName(), info.policyCompliance(),
                    info.generationDate());
            reports.add(view);
            byApplication.putIfAbsent(info.appName() == null ? "" : info.appName(), view);
            rows.addAll(file.report().rows());
            String prefix = upload.reports().size() > 1 ? file.filename() + ": " : "";
            file.report().warnings().forEach(w -> warnings.add(prefix + w));
        }
        List<Finding> findings = Finding.group(rows);
        JiraLookupService.CreateMeta meta = jira.createMeta(token);
        List<String> versions = jira.selectedVersions(meta, jiraVersions);
        Map<String, List<IssueRef>> matches = jira.find(findings, versions, token);
        log.info("upload {}: {} report(s), {} vulnerabilities -> {} findings, {} matched in Jira, {} warnings",
                upload.id(), reports.size(), rows.size(), findings.size(),
                matches.values().stream().filter(l -> !l.isEmpty()).count(), warnings.size());
        return new Snapshot(upload.id(), List.copyOf(reports), byApplication, findings, List.copyOf(warnings), meta,
                versions, matches);
    }
}
