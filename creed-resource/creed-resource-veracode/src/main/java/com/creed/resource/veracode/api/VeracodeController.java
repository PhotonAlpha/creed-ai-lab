package com.creed.resource.veracode.api;

import com.creed.resource.veracode.api.dto.AnalysisRequest;
import com.creed.resource.veracode.api.dto.AnalysisResponse;
import com.creed.resource.veracode.api.dto.CreateIssuesRequest;
import com.creed.resource.veracode.api.dto.CreateIssuesResponse;
import com.creed.resource.veracode.api.dto.VeracodeSettings;
import com.creed.resource.veracode.config.JiraProperties;
import com.creed.resource.veracode.service.ExcelExporter;
import com.creed.resource.veracode.service.JiraLookupService;
import com.creed.resource.veracode.service.JiraToken;
import com.creed.resource.veracode.service.VeracodeAnalysisService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.ClassPathResource;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;

import java.io.IOException;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;

@RestController
@RequestMapping("/api/veracode")
@RequiredArgsConstructor
public class VeracodeController {

    static final MediaType XLSX =
            MediaType.parseMediaType("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet");
    private static final DateTimeFormatter FILE_TIME =
            DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss").withZone(ZoneOffset.UTC);
    /** The bundled demo report: findings that hit every outcome against the Jira mock. */
    static final String SAMPLE = "sample/veracode-detailedreport.xml";

    private final VeracodeAnalysisService analysis;
    private final ExcelExporter excel;
    private final JiraLookupService jiraLookup;
    private final JiraProperties jira;

    @Value("${veracode.mock.enabled:false}")
    private boolean mock;

    @GetMapping("/settings")
    VeracodeSettings settings() {
        return new VeracodeSettings(jira.projectKey(), jira.issueType(), jira.baseUrl(), mock, jira.summaryTemplate(),
                jira.requireUserToken());
    }

    /**
     * Analyse an uploaded Veracode detailed report (XML). The file is parsed and kept in memory; the
     * response's {@code report.id} is the upload id that re-analyse, create and export take.
     *
     * @param affectedVersion Jira's Affects Version to use instead of the report's {@code version}
     */
    @PostMapping(value = "/analysis/upload", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    AnalysisResponse analyzeUpload(@RequestPart("file") MultipartFile file,
                                   @RequestParam(required = false) String affectedVersion,
                                   @RequestParam(defaultValue = "false") boolean autoCreate,
                                   @RequestHeader(name = JiraToken.HEADER, required = false) String token)
            throws IOException {
        String name = file.getOriginalFilename() == null || file.getOriginalFilename().isBlank()
                ? "upload.xml" : file.getOriginalFilename();
        return analysis.analyzeUpload(name, file.getBytes(), affectedVersion, autoCreate, new JiraToken(token));
    }

    /** The bundled sample report, as if uploaded - only with the mocks, where it has a Jira to match. */
    @PostMapping("/analysis/sample")
    AnalysisResponse analyzeSample(@RequestParam(required = false) String affectedVersion,
                                   @RequestParam(defaultValue = "false") boolean autoCreate,
                                   @RequestHeader(name = JiraToken.HEADER, required = false) String token)
            throws IOException {
        if (!mock) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "the sample report is only served with veracode.mock.enabled");
        }
        byte[] xml = new ClassPathResource(SAMPLE).getContentAsByteArray();
        return analysis.analyzeUpload("veracode-detailedreport-sample.xml", xml, affectedVersion, autoCreate,
                new JiraToken(token));
    }

    /** Re-analyse an upload against Jira as it is now. */
    @PostMapping("/analysis")
    AnalysisResponse analyze(@RequestBody AnalysisRequest request,
                             @RequestHeader(name = JiraToken.HEADER, required = false) String token) {
        return analysis.analyze(request.uploadId(), request.autoCreate(), new JiraToken(token));
    }

    @PostMapping("/jira/issues")
    CreateIssuesResponse createIssues(@Valid @RequestBody CreateIssuesRequest request,
                                      @RequestHeader(name = JiraToken.HEADER, required = false) String token) {
        return analysis.create(request.uploadId(), request.findingIds(), new JiraToken(token));
    }

    /**
     * The page's "test connection" for the token it holds, on Jira's create metadata: whether the token is
     * accepted and may create the issue type in the project. A rejected token is an answer ({@code ok: false},
     * Jira's status), not an error.
     */
    @PostMapping("/jira/connection")
    JiraLookupService.Connection testConnection(@RequestHeader(name = JiraToken.HEADER, required = false) String token) {
        return jiraLookup.connection(new JiraToken(token));
    }

    /**
     * A fresh analysis, not the one on screen: the file then states what Jira holds at export time,
     * including issues created since the page was loaded. GET so a plain link can download it.
     */
    @GetMapping("/analysis/export")
    ResponseEntity<byte[]> export(@RequestParam String uploadId,
                                  @RequestHeader(name = JiraToken.HEADER, required = false) String token) {
        AnalysisResponse result = analysis.analyze(uploadId, false, new JiraToken(token));
        String base = result.report().appName() == null ? "report"
                : result.report().appName().replaceAll("[^A-Za-z0-9._-]+", "_");
        String filename = "veracode-" + base + "-" + FILE_TIME.format(result.analyzedAt()) + ".xlsx";
        return ResponseEntity.ok()
                .contentType(XLSX)
                .header(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.attachment().filename(filename).build().toString())
                .body(excel.export(result));
    }
}
