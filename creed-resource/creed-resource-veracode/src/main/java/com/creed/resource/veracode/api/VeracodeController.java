package com.creed.resource.veracode.api;

import com.creed.resource.veracode.api.dto.AnalysisRequest;
import com.creed.resource.veracode.api.dto.AnalysisResponse;
import com.creed.resource.veracode.api.dto.CreateIssuesRequest;
import com.creed.resource.veracode.api.dto.CreateIssuesResponse;
import com.creed.resource.veracode.api.dto.VeracodeSettings;
import com.creed.resource.veracode.config.ConfluenceProperties;
import com.creed.resource.veracode.config.JiraProperties;
import com.creed.resource.veracode.service.ExcelExporter;
import com.creed.resource.veracode.service.ReportSourceRef;
import com.creed.resource.veracode.service.VeracodeAnalysisService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
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

    private final VeracodeAnalysisService analysis;
    private final ExcelExporter excel;
    private final ConfluenceProperties confluence;
    private final JiraProperties jira;

    @Value("${veracode.mock.enabled:false}")
    private boolean mock;

    @GetMapping("/settings")
    VeracodeSettings settings() {
        return new VeracodeSettings(confluence.pageId(), jira.projectKey(), jira.issueType(),
                confluence.baseUrl(), jira.baseUrl(), mock);
    }

    @PostMapping("/analysis")
    AnalysisResponse analyze(@RequestBody(required = false) AnalysisRequest request) {
        AnalysisRequest r = request == null ? new AnalysisRequest(null, null, false) : request;
        return analysis.analyze(r.source(), r.autoCreate());
    }

    /**
     * Analyse an uploaded report (.html/.htm, .csv, .xlsx/.xls). The file is parsed and kept in memory;
     * the response's {@code page.id} is the upload id that create and export take as {@code uploadId}.
     */
    @PostMapping(value = "/analysis/upload", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    AnalysisResponse analyzeUpload(@RequestPart("file") MultipartFile file,
                                   @RequestParam(defaultValue = "false") boolean autoCreate) throws IOException {
        String name = file.getOriginalFilename() == null || file.getOriginalFilename().isBlank()
                ? "upload" : file.getOriginalFilename();
        return analysis.analyzeUpload(name, file.getBytes(), autoCreate);
    }

    @PostMapping("/jira/issues")
    CreateIssuesResponse createIssues(@Valid @RequestBody CreateIssuesRequest request) {
        return analysis.create(request.source(), request.findingIds());
    }

    /**
     * A fresh analysis, not the one on screen: the file then states what Jira holds at export time,
     * including issues created since the page was loaded. GET so a plain link can download it.
     */
    @GetMapping("/analysis/export")
    ResponseEntity<byte[]> export(@RequestParam(required = false) String pageId,
                                  @RequestParam(required = false) String uploadId) {
        AnalysisResponse result = analysis.analyze(new ReportSourceRef(pageId, uploadId), false);
        String base = result.page().uploaded() ? "upload" : result.page().id();
        String filename = "veracode-" + base + "-" + FILE_TIME.format(result.analyzedAt()) + ".xlsx";
        return ResponseEntity.ok()
                .contentType(XLSX)
                .header(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.attachment().filename(filename).build().toString())
                .body(excel.export(result));
    }
}
