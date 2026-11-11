package com.creed.resource.veracode.api;

import com.creed.resource.veracode.api.dto.AnalysisResponse;
import com.creed.resource.veracode.service.JiraToken;
import com.creed.resource.veracode.service.VeracodeAnalysisService;
import com.creed.resource.veracode.template.ReportTemplateService;
import com.creed.resource.veracode.template.TemplateFields;
import com.creed.resource.veracode.template.TemplateMapping;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

/**
 * Report templates: a company {@code .xlsx} uploaded, its cells bound to SCA / SAST fields on the page, and an analysis
 * exported through it (JXLS). See {@link ReportTemplateService}.
 */
@RestController
@RequestMapping("/api/veracode/report-templates")
@RequiredArgsConstructor
public class ReportTemplateController {

    private final ReportTemplateService templates;
    private final VeracodeAnalysisService analysis;

    @GetMapping
    List<ReportTemplateService.Summary> list() {
        return templates.list();
    }

    /** What a cell can be bound to: {@code sca} / {@code sast} for data rows, {@code summary} for single cells. */
    @GetMapping("/fields")
    Map<String, List<TemplateFields.Field>> fields() {
        return TemplateFields.CATALOG;
    }

    /** @param name shown in the list; blank = the file name without {@code .xlsx} */
    @PostMapping(consumes = "multipart/form-data")
    ReportTemplateService.Detail upload(@RequestPart("file") MultipartFile file,
                                        @RequestParam(required = false) String name) throws IOException {
        String fileName = file.getOriginalFilename() == null || file.getOriginalFilename().isBlank()
                ? "template.xlsx" : file.getOriginalFilename();
        return templates.create(name, fileName, file.getBytes());
    }

    /** The workbook as the page's grid draws it, with its saved bindings. */
    @GetMapping("/{id}")
    ReportTemplateService.Detail get(@PathVariable String id) {
        return templates.get(id);
    }

    @PutMapping("/{id}/mapping")
    ReportTemplateService.Detail saveMapping(@PathVariable String id, @RequestBody TemplateMapping mapping) {
        return templates.saveMapping(id, mapping);
    }

    @DeleteMapping("/{id}")
    ResponseEntity<Void> delete(@PathVariable String id) {
        templates.delete(id);
        return ResponseEntity.noContent().build();
    }

    /** The JXLS template export fills - the upload with its markup (jx: comments, ${...} cells). */
    @GetMapping("/{id}/jxls")
    ResponseEntity<byte[]> jxls(@PathVariable String id) {
        return xlsx(templates.jxlsTemplate(id));
    }

    /**
     * A fresh analysis of the upload (as for the Excel export: Jira as it is now), written into the template.
     *
     * @param jiraVersions / countries the page's lookup conditions
     */
    @GetMapping("/{id}/export")
    ResponseEntity<byte[]> export(@PathVariable String id, @RequestParam String uploadId,
                                  @RequestParam(required = false) List<String> jiraVersions,
                                  @RequestParam(required = false) List<String> countries,
                                  JiraToken token) {
        AnalysisResponse result = analysis.analyze(uploadId, false, Map.of(), jiraVersions, countries, token);
        return xlsx(templates.fill(id, result));
    }

    private static ResponseEntity<byte[]> xlsx(ReportTemplateService.Filled file) {
        return ResponseEntity.ok()
                .contentType(VeracodeController.XLSX)
                // a template name may be Chinese: filename* (RFC 5987) carries it
                .header(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.attachment()
                        .filename(file.fileName(), StandardCharsets.UTF_8).build().toString())
                .body(file.content());
    }
}
