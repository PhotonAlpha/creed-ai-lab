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
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;

import tools.jackson.core.JacksonException;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Map;

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
    private final JsonMapper jsonMapper;

    @Value("${veracode.mock.enabled:false}")
    private boolean mock;

    @GetMapping("/settings")
    VeracodeSettings settings() {
        return new VeracodeSettings(jira.projectKey(), jira.issueType(), jira.baseUrl(), mock, jira.summaryTemplate(),
                jira.looksLikeCloud());
    }

    /**
     * Analyse an uploaded Veracode detailed report (XML). The file is parsed and kept in memory; the
     * response's {@code report.id} is the upload id that re-analyse, create and export take.
     *
     * @param affectedVersion the version to show instead of the report's {@code version} (display only)
     * @param fields          create-screen values for {@code autoCreate}, as JSON ({@code {"components":["Security"]}})
     * @param jiraVersions    the Jira versions to look up in, repeated ({@code ?jiraVersions=2.3.0&jiraVersions=2.4.0});
     *                        none = any. {@code autoCreate} files under the one chosen
     */
    @PostMapping(value = "/analysis/upload", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    AnalysisResponse analyzeUpload(@RequestPart("file") MultipartFile file,
                                   @RequestParam(required = false) String affectedVersion,
                                   @RequestParam(defaultValue = "false") boolean autoCreate,
                                   @RequestParam(required = false) String fields,
                                   @RequestParam(required = false) List<String> jiraVersions,
                                   JiraToken token)
            throws IOException {
        String name = file.getOriginalFilename() == null || file.getOriginalFilename().isBlank()
                ? "upload.xml" : file.getOriginalFilename();
        return analysis.analyzeUpload(name, file.getBytes(), affectedVersion, autoCreate, fields(fields), jiraVersions,
                token);
    }

    /** The bundled sample report, as if uploaded - only with the mocks, where it has a Jira to match. */
    @PostMapping("/analysis/sample")
    AnalysisResponse analyzeSample(@RequestParam(required = false) String affectedVersion,
                                   @RequestParam(defaultValue = "false") boolean autoCreate,
                                   @RequestParam(required = false) String fields,
                                   @RequestParam(required = false) List<String> jiraVersions,
                                   JiraToken token)
            throws IOException {
        if (!mock) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "the sample report is only served with veracode.mock.enabled");
        }
        byte[] xml = new ClassPathResource(SAMPLE).getContentAsByteArray();
        return analysis.analyzeUpload("veracode-detailedreport-sample.xml", xml, affectedVersion, autoCreate,
                fields(fields), jiraVersions, token);
    }

    /** Re-analyse an upload against Jira as it is now. */
    @PostMapping("/analysis")
    AnalysisResponse analyze(@RequestBody AnalysisRequest request,
                             JiraToken token) {
        return analysis.analyze(request.uploadId(), request.autoCreate(), request.fields(), request.jiraVersions(), token);
    }

    @PostMapping("/jira/issues")
    CreateIssuesResponse createIssues(@Valid @RequestBody CreateIssuesRequest request,
                                      JiraToken token) {
        return analysis.create(request.uploadId(), request.findingIds(), request.fields(), request.jiraVersions(), token);
    }

    /**
     * The page's "test connection" for the token it holds, on Jira's create metadata: whether the token is
     * accepted and may create the issue type in the project. A rejected token is an answer ({@code ok: false},
     * Jira's status), not an error.
     */
    @PostMapping("/jira/connection")
    JiraLookupService.Connection testConnection(JiraToken token) {
        return jiraLookup.connection(token);
    }

    /**
     * A fresh analysis, not the one on screen: the file then states what Jira holds at export time,
     * including issues created since the page was loaded. GET so a plain link can download it.
     */
    @GetMapping("/analysis/export")
    ResponseEntity<byte[]> export(@RequestParam String uploadId,
                                  @RequestParam(required = false) List<String> jiraVersions,
                                  JiraToken token) {
        AnalysisResponse result = analysis.analyze(uploadId, false, Map.of(), jiraVersions, token);
        String base = result.report().appName() == null ? "report"
                : result.report().appName().replaceAll("[^A-Za-z0-9._-]+", "_");
        String filename = "veracode-" + base + "-" + FILE_TIME.format(result.analyzedAt()) + ".xlsx";
        return ResponseEntity.ok()
                .contentType(XLSX)
                .header(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.attachment().filename(filename).build().toString())
                .body(excel.export(result));
    }

    /** The multipart upload carries the create-screen values as one JSON form value. */
    private Map<String, Object> fields(String json) {
        if (json == null || json.isBlank()) {
            return Map.of();
        }
        try {
            return jsonMapper.readValue(json, new TypeReference<Map<String, Object>>() { });
        } catch (JacksonException e) {
            throw new IllegalArgumentException("fields is not a JSON object: " + e.getOriginalMessage());
        }
    }
}
