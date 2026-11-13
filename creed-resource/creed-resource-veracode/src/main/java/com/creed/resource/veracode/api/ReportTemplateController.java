package com.creed.resource.veracode.api;

import com.creed.resource.veracode.api.dto.AnalysisResponse;
import com.creed.resource.veracode.service.JiraToken;
import com.creed.resource.veracode.service.VeracodeAnalysisService;
import com.creed.resource.veracode.template.ReportTemplateService;
import com.creed.resource.veracode.template.TemplateFields;
import com.creed.resource.veracode.template.TemplateMapping;
import com.creed.resource.veracode.template.TemplateWorkbook;
import io.micrometer.context.ContextSnapshotFactory;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
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
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Report templates: a company {@code .xlsx} uploaded, its cells bound to SCA / SAST fields on the page, and an analysis
 * exported through it (JXLS). See {@link ReportTemplateService}.
 */
@RestController
@RequestMapping("/api/veracode/report-templates")
@RequiredArgsConstructor
@Slf4j
public class ReportTemplateController {

    private static final ContextSnapshotFactory CONTEXT = ContextSnapshotFactory.builder().build();

    private final ReportTemplateService templates;
    private final VeracodeAnalysisService analysis;

    /** How long an upload's progress stream may stay open: a big workbook takes minutes to read. */
    @Value("${veracode.templates.upload-timeout:10m}")
    private Duration uploadTimeout;

    @GetMapping
    List<ReportTemplateService.Summary> list() {
        return templates.list();
    }

    /**
     * What a cell can be bound to: {@code sca} / {@code sast} / {@code scaStats} / {@code sastStats} for data rows,
     * {@code summary} for single cells.
     */
    @GetMapping("/fields")
    Map<String, List<TemplateFields.Field>> fields() {
        return TemplateFields.CATALOG;
    }

    /** @param name shown in the list; blank = the file name without {@code .xlsx} / {@code .xlsm} */
    @PostMapping(consumes = "multipart/form-data")
    ReportTemplateService.Detail upload(@RequestPart("file") MultipartFile file,
                                        @RequestParam(required = false) String name) throws IOException {
        String fileName = file.getOriginalFilename() == null || file.getOriginalFilename().isBlank()
                ? "template.xlsx" : file.getOriginalFilename();
        return templates.create(name, fileName, file.getBytes());
    }

    /**
     * The same upload, answered as Server-Sent Events while the workbook is read (asked for, 2026-11-13: a big template
     * showed nothing for a minute): {@code progress} events ({@link TemplateWorkbook.Progress}), then {@code done} with
     * the template - or {@code error} with the usual {@code {error, message}} body. Chosen by {@code Accept:
     * text/event-stream}; anything else gets the JSON answer above.
     *
     * <p>The file is read here, on the request thread (the multipart goes with the request); the workbook on a virtual
     * thread, which takes the request's trace context along. A client gone mid-way does not stop it: the template is
     * still stored, as the JSON upload would have.
     */
    @PostMapping(consumes = "multipart/form-data", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    SseEmitter uploadWithProgress(@RequestPart("file") MultipartFile file,
                                  @RequestParam(required = false) String name) throws IOException {
        String fileName = file.getOriginalFilename() == null || file.getOriginalFilename().isBlank()
                ? "template.xlsx" : file.getOriginalFilename();
        byte[] content = file.getBytes();
        SseEmitter emitter = new SseEmitter(uploadTimeout.toMillis());
        Events events = new Events(emitter);
        Thread.ofVirtual().name("template-upload").start(CONTEXT.captureAll().wrap(() -> {
            try {
                events.send("done", templates.create(name, fileName, content, p -> events.send("progress", p)));
            } catch (IllegalArgumentException e) {
                events.send("error", error("bad_request", e.getMessage()));
            } catch (RuntimeException e) {
                log.error("report template upload '{}' failed", fileName, e);
                events.send("error", error("internal_error", "the template could not be read: " + e.getMessage()));
            } finally {
                events.complete();
            }
        }));
        return emitter;
    }

    private static Map<String, Object> error(String error, String message) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("error", error);
        body.put("message", message);
        body.put("time", Instant.now().toString());
        return body;
    }

    /** Sends while the client listens; once it is gone (or the stream timed out) the rest is dropped quietly. */
    private static final class Events {

        private final SseEmitter emitter;
        private volatile boolean open = true;

        Events(SseEmitter emitter) {
            this.emitter = emitter;
            emitter.onTimeout(() -> open = false);
            emitter.onError(e -> open = false);
            emitter.onCompletion(() -> open = false);
        }

        void send(String event, Object data) {
            if (!open) {
                return;
            }
            try {
                emitter.send(SseEmitter.event().name(event).data(data, MediaType.APPLICATION_JSON));
            } catch (IOException | IllegalStateException e) {
                open = false;
                log.debug("upload progress: client gone ({})", e.toString());
            }
        }

        void complete() {
            if (open) {
                emitter.complete();
            }
        }
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

    static final MediaType XLSM = MediaType.parseMediaType("application/vnd.ms-excel.sheet.macroEnabled.12");

    private static ResponseEntity<byte[]> xlsx(ReportTemplateService.Filled file) {
        return ResponseEntity.ok()
                .contentType(file.macroEnabled() ? XLSM : VeracodeController.XLSX)
                // a template name may be Chinese: filename* (RFC 5987) carries it
                .header(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.attachment()
                        .filename(file.fileName(), StandardCharsets.UTF_8).build().toString())
                .body(file.content());
    }
}
