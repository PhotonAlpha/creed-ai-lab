package com.creed.resource.veracode.service;

import com.creed.resource.veracode.api.dto.AnalysisResponse;
import com.creed.resource.veracode.api.dto.CreateIssuesResponse;
import com.creed.resource.veracode.api.dto.CreateIssuesResponse.Outcome;
import com.creed.resource.veracode.api.dto.CreateIssuesResponse.Result;
import com.creed.resource.veracode.api.dto.FindingStatus;
import com.creed.resource.veracode.api.dto.FindingView;
import com.creed.resource.veracode.config.JiraProperties;
import com.creed.resource.veracode.domain.Finding;
import com.creed.resource.veracode.service.JiraLookupService.IssueRef;
import com.creed.resource.veracode.service.VeracodeXmlParser.ParsedReport;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Uploaded Veracode XML -> findings -> Jira matches, and the creation of the missing issues.
 *
 * <p>Jira is never cached: an analysis is a read of Jira as it is now, which is what makes the creation
 * path safe. The report is re-read from {@link UploadedReportStore} by id, and a create request names
 * findings by id with the whole chain re-read first, so a finding someone else filed in the meantime comes
 * back {@code ALREADY_EXISTS} instead of becoming a duplicate.
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

    /** @param versions the page's Jira versions, Jira's spelling - searched, and (one of them) filed under */
    private record Snapshot(ReportView report, List<Finding> findings, List<String> warnings,
                            JiraLookupService.CreateMeta meta, List<String> versions,
                            Map<String, List<IssueRef>> matches) {
    }

    /**
     * Parses and keeps the file, then analyses it; the response's {@code report.id} is the upload id.
     *
     * @param versionOverride the version shown instead of the report's {@code version}; blank = the report's. Display
     *                        only, as the report's own: Jira is searched and filed by {@code jiraVersions}
     * @param fields          create-screen values for {@code autoCreate} (fieldId -> value or values)
     * @param jiraVersions    the Jira versions chosen on the page (see {@link #analyze})
     */
    public AnalysisResponse analyzeUpload(String filename, byte[] content, String versionOverride, boolean autoCreate,
                                          Map<String, Object> fields, List<String> jiraVersions, JiraToken token) {
        token.require();
        ParsedReport parsed = parser.parse(filename, content);
        boolean overridden = versionOverride != null && !versionOverride.isBlank();
        String version = overridden ? versionOverride.trim() : parsed.info().version();
        if (version == null) {
            throw new VeracodeXmlParser.ReportFormatException(
                    "the report has no version (detailedreport/@version): enter the affected version on the page");
        }
        UploadedReportStore.Upload upload = uploads.put(filename, parsed.withVersion(version), version, overridden);
        log.info("upload {} '{}' ({} bytes): app {}, version {}{}, {} vulnerabilities", upload.id(), filename,
                content.length, parsed.info().appName(), version, overridden ? " (overridden)" : "", parsed.rows().size());
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
            List<FindingView> views = new ArrayList<>();
            for (Finding f : s.findings()) {
                List<IssueRef> issues = s.matches().getOrDefault(f.id(), List.of());
                FindingStatus status = issues.isEmpty() ? FindingStatus.MISSING : FindingStatus.EXISTS;
                if (status == FindingStatus.MISSING && autoCreate) {
                    try {
                        issues = List.of(jira.create(f, version, chosen, s.report(), token));
                        status = FindingStatus.CREATED;
                    } catch (UpstreamException e) {
                        warnings.add(f.id() + ": not created - " + e.getMessage());
                    }
                }
                views.add(FindingView.of(f, status, issues));
            }
            return new AnalysisResponse(s.report(), jiraProps.projectKey(), List.copyOf(s.meta().versions().values()),
                    s.versions(), s.versions().size() == 1 ? s.versions().getFirst() : null, s.meta().fields(), views, warnings,
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
            List<Result> results = new ArrayList<>();
            for (String id : findingIds.stream().distinct().toList()) {
                results.add(createOne(id, byId.get(id), s, version, chosen, token));
            }
            return new CreateIssuesResponse(results);
        } finally {
            createLock.unlock();
        }
    }

    private Result createOne(String id, Finding f, Snapshot s, String version, Map<String, Object> fields,
                             JiraToken token) {
        if (f == null) {
            return new Result(id, Outcome.NOT_IN_REPORT, List.of(), "no such finding in " + s.report().fileName());
        }
        List<IssueRef> existing = s.matches().getOrDefault(id, List.of());
        if (!existing.isEmpty()) {
            return new Result(id, Outcome.ALREADY_EXISTS, existing, null);
        }
        try {
            return new Result(id, Outcome.CREATED, List.of(jira.create(f, version, fields, s.report(), token)), null);
        } catch (UpstreamException e) {
            log.warn("create failed for {}: {}", id, e.getMessage());
            return new Result(id, Outcome.FAILED, List.of(), e.getMessage());
        }
    }

    private Snapshot load(String uploadId, List<String> jiraVersions, JiraToken token) {
        if (uploadId == null || uploadId.isBlank()) {
            throw new IllegalArgumentException("uploadId is required: upload a Veracode XML report first");
        }
        UploadedReportStore.Upload upload = uploads.get(uploadId.trim());
        ParsedReport parsed = upload.report();
        VeracodeXmlParser.ReportInfo info = parsed.info();
        ReportView report = new ReportView(upload.id(), upload.filename(), info.appName(), info.appId(), info.version(),
                upload.affectedVersion(), upload.versionOverridden(), info.policyName(), info.policyCompliance(),
                info.generationDate());
        List<Finding> findings = Finding.group(parsed.rows());
        JiraLookupService.CreateMeta meta = jira.createMeta(token);
        List<String> versions = jira.selectedVersions(meta, jiraVersions);
        Map<String, List<IssueRef>> matches = jira.find(findings, versions, token);
        log.info("upload {}: {} vulnerabilities -> {} findings, {} matched in Jira, {} warnings", upload.id(),
                parsed.rows().size(), findings.size(), matches.values().stream().filter(l -> !l.isEmpty()).count(),
                parsed.warnings().size());
        return new Snapshot(report, findings, parsed.warnings(), meta, versions, matches);
    }
}
