package com.creed.resource.veracode.service;

import com.creed.resource.veracode.api.dto.AnalysisResponse;
import com.creed.resource.veracode.api.dto.CreateIssuesResponse;
import com.creed.resource.veracode.api.dto.CreateIssuesResponse.Outcome;
import com.creed.resource.veracode.api.dto.CreateIssuesResponse.Result;
import com.creed.resource.veracode.api.dto.FindingStatus;
import com.creed.resource.veracode.api.dto.FindingView;
import com.creed.resource.veracode.client.confluence.ConfluenceClient;
import com.creed.resource.veracode.client.confluence.ConfluencePage;
import com.creed.resource.veracode.config.ConfluenceProperties;
import com.creed.resource.veracode.config.JiraProperties;
import com.creed.resource.veracode.domain.Finding;
import com.creed.resource.veracode.service.ReportParser.ParsedReport;
import com.creed.resource.veracode.service.JiraLookupService.IssueRef;
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
 * Report (a Confluence page or an uploaded file) -> findings -> Jira matches, and the creation of the
 * missing issues.
 *
 * <p>Jira is never cached: an analysis is a read of Jira as it is now, which is what makes the creation
 * path safe. A page is re-read from Confluence on every request too; an upload is re-read from
 * {@link UploadedReportStore}. A create request names findings by id and the whole
 * chain is re-read first, so a finding someone else filed in the meantime comes back
 * {@code ALREADY_EXISTS} instead of becoming a duplicate.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class VeracodeAnalysisService {

    static final String CONFLUENCE = "Confluence";

    private final ConfluenceClient confluence;
    private final ReportParser parser;
    private final UploadedReportStore uploads;
    private final JiraLookupService jira;
    private final ConfluenceProperties confluenceProps;
    private final JiraProperties jiraProps;
    private final Clock clock;

    /**
     * Serialises check-then-create within this process, so a double click cannot pass the existence
     * check twice. Two instances of this service would still race; Jira has no uniqueness constraint
     * to fall back on.
     */
    private final ReentrantLock createLock = new ReentrantLock();

    private record Snapshot(ReportPage page, List<Finding> findings, List<String> warnings,
                            Map<String, String> jiraVersions, Map<String, List<IssueRef>> matches) {
    }

    /** Parses and keeps the file, then analyses it; the response's {@code page.id} is the upload id. */
    public AnalysisResponse analyzeUpload(String filename, byte[] content, boolean autoCreate) {
        ParsedReport parsed = parser.parseFile(filename, content);
        UploadedReportStore.Upload upload = uploads.put(filename, parsed);
        log.info("upload {} '{}' ({} bytes): {} rows", upload.id(), filename, content.length, parsed.rows().size());
        return analyze(ReportSourceRef.upload(upload.id()), autoCreate);
    }

    public AnalysisResponse analyze(ReportSourceRef source, boolean autoCreate) {
        if (autoCreate) {
            createLock.lock();
        }
        try {
            Snapshot s = load(source);
            List<String> warnings = new ArrayList<>(s.warnings());
            List<FindingView> views = new ArrayList<>();
            for (Finding f : s.findings()) {
                String version = JiraLookupService.jiraVersion(s.jiraVersions(), f);
                List<IssueRef> issues = s.matches().getOrDefault(f.id(), List.of());
                FindingStatus status = issues.isEmpty() ? FindingStatus.MISSING : FindingStatus.EXISTS;
                if (status == FindingStatus.MISSING && autoCreate && version != null) {
                    try {
                        issues = List.of(jira.create(f, version, s.page()));
                        status = FindingStatus.CREATED;
                    } catch (UpstreamException e) {
                        warnings.add(f.id() + ": not created - " + e.getMessage());
                    }
                }
                views.add(FindingView.of(f, status, version, issues));
            }
            return new AnalysisResponse(s.page(), jiraProps.projectKey(), List.copyOf(s.jiraVersions().values()),
                    views, warnings, AnalysisResponse.Counts.of(views), Instant.now(clock));
        } finally {
            if (autoCreate) {
                createLock.unlock();
            }
        }
    }

    public CreateIssuesResponse create(ReportSourceRef source, List<String> findingIds) {
        createLock.lock();
        try {
            Snapshot s = load(source);
            Map<String, Finding> byId = s.findings().stream()
                    .collect(Collectors.toMap(Finding::id, Function.identity(), (a, b) -> a, LinkedHashMap::new));
            List<Result> results = new ArrayList<>();
            for (String id : findingIds.stream().distinct().toList()) {
                results.add(createOne(id, byId.get(id), s));
            }
            return new CreateIssuesResponse(results);
        } finally {
            createLock.unlock();
        }
    }

    private Result createOne(String id, Finding f, Snapshot s) {
        if (f == null) {
            return new Result(id, Outcome.NOT_IN_REPORT, List.of(), "no such finding in " + s.page().title());
        }
        String version = JiraLookupService.jiraVersion(s.jiraVersions(), f);
        if (version == null) {
            return new Result(id, Outcome.VERSION_NOT_IN_JIRA, List.of(),
                    "project " + jiraProps.projectKey() + " has no version '" + f.affectedVersion() + "'");
        }
        List<IssueRef> existing = s.matches().getOrDefault(id, List.of());
        if (!existing.isEmpty()) {
            return new Result(id, Outcome.ALREADY_EXISTS, existing, null);
        }
        try {
            return new Result(id, Outcome.CREATED, List.of(jira.create(f, version, s.page())), null);
        } catch (UpstreamException e) {
            log.warn("create failed for {}: {}", id, e.getMessage());
            return new Result(id, Outcome.FAILED, List.of(), e.getMessage());
        }
    }

    private Snapshot load(ReportSourceRef source) {
        ReportPage reportPage;
        ParsedReport parsed;
        if (source.isUpload()) {
            UploadedReportStore.Upload upload = uploads.get(source.uploadId());
            reportPage = new ReportPage(ReportPage.UPLOAD, upload.id(), upload.filename(), null, null);
            parsed = upload.report();
        } else {
            ConfluencePage page = fetch(resolvePageId(source.pageId()));
            reportPage = new ReportPage(ReportPage.CONFLUENCE, page.id(), page.title(),
                    page.version() == null ? null : page.version().number(), pageUrl(page));
            parsed = parser.parseStorage(page.storageValue());
        }
        List<Finding> findings = Finding.group(parsed.rows());
        Map<String, String> versions = jira.projectVersions();
        Map<String, List<IssueRef>> matches = jira.find(findings, versions);
        log.info("{} {}: {} rows -> {} findings, {} matched in Jira, {} warnings", reportPage.source(),
                reportPage.id(), parsed.rows().size(), findings.size(),
                matches.values().stream().filter(l -> !l.isEmpty()).count(), parsed.warnings().size());
        return new Snapshot(reportPage, findings, parsed.warnings(), versions, matches);
    }

    private ConfluencePage fetch(String pageId) {
        try {
            return UpstreamException.call(CONFLUENCE, () -> confluence.getPage(pageId, "body.storage,version"));
        } catch (UpstreamException e) {
            if (e.status() == 404) {
                throw new PageNotFoundException(pageId);
            }
            throw e;
        }
    }

    String resolvePageId(String requested) {
        String pageId = requested == null || requested.isBlank() ? confluenceProps.pageId() : requested.trim();
        if (pageId == null || pageId.isBlank()) {
            throw new IllegalArgumentException("pageId is required (no veracode.confluence.page-id configured)");
        }
        if (!pageId.matches("\\d{1,20}")) {
            // It goes into a URL path; Confluence page ids are numeric.
            throw new IllegalArgumentException("pageId must be a numeric Confluence page id");
        }
        return pageId;
    }

    private String pageUrl(ConfluencePage page) {
        if (page.links() != null && page.links().base() != null && page.links().webui() != null) {
            return page.links().base() + page.links().webui();
        }
        String template = confluenceProps.browseUrl();
        return template == null || template.isBlank() ? null : template.replace("{id}", page.id());
    }

    public static class PageNotFoundException extends RuntimeException {
        public PageNotFoundException(String pageId) {
            super("Confluence page " + pageId + " does not exist or is not visible to this service's account");
        }
    }
}
