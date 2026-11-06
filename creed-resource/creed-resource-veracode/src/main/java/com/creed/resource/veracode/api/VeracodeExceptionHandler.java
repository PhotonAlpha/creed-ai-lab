package com.creed.resource.veracode.api;

import com.creed.resource.veracode.service.ConfluenceService;
import com.creed.resource.veracode.service.ConfluenceToken;
import com.creed.resource.veracode.service.JiraLookupService;
import com.creed.resource.veracode.service.JiraToken;
import com.creed.resource.veracode.service.UploadedReportStore;
import com.creed.resource.veracode.service.UpstreamException;
import com.creed.resource.veracode.service.VeracodeXmlParser;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.multipart.support.MissingServletRequestPartException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The same {@code {error, message, fields?, time}} envelope the env-matrix module answers with, so the
 * frontend's one fetch wrapper reads both.
 *
 * <p>Scoped to {@link VeracodeController}: the Jira mock answers in Jira's own error shape, and must not
 * be rewritten into this one.
 */
@RestControllerAdvice(assignableTypes = VeracodeController.class)
@Slf4j
public class VeracodeExceptionHandler {

    /** XML, but not a Veracode detailed report this service can read. */
    @ExceptionHandler(VeracodeXmlParser.ReportFormatException.class)
    ResponseEntity<Map<String, Object>> unreadable(VeracodeXmlParser.ReportFormatException e) {
        return ResponseEntity.unprocessableEntity().body(body("report_unreadable", e.getMessage(), null));
    }

    @ExceptionHandler(VeracodeXmlParser.UnsupportedFileException.class)
    ResponseEntity<Map<String, Object>> unsupported(VeracodeXmlParser.UnsupportedFileException e) {
        return ResponseEntity.badRequest().body(body("unsupported_file", e.getMessage(), null));
    }

    /** The page should offer to upload again - the id is gone, not wrong. */
    @ExceptionHandler(UploadedReportStore.UploadNotFoundException.class)
    ResponseEntity<Map<String, Object>> uploadGone(UploadedReportStore.UploadNotFoundException e) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(body("upload_expired", e.getMessage(), null));
    }

    @ExceptionHandler(MissingServletRequestPartException.class)
    ResponseEntity<Map<String, Object>> noFile(MissingServletRequestPartException e) {
        return ResponseEntity.badRequest().body(body("bad_request", "multipart part 'file' is required", null));
    }

    /** The page opens its token dialog on this. */
    @ExceptionHandler(JiraToken.RequiredException.class)
    ResponseEntity<Map<String, Object>> tokenRequired(JiraToken.RequiredException e) {
        return ResponseEntity.badRequest().body(body("jira_token_required", e.getMessage(), null));
    }

    /** The page opens its Confluence token dialog on this. */
    @ExceptionHandler(ConfluenceToken.RequiredException.class)
    ResponseEntity<Map<String, Object>> confluenceTokenRequired(ConfluenceToken.RequiredException e) {
        return ResponseEntity.badRequest().body(body("confluence_token_required", e.getMessage(), null));
    }

    @ExceptionHandler(ConfluenceService.PageNotFoundException.class)
    ResponseEntity<Map<String, Object>> pageNotFound(ConfluenceService.PageNotFoundException e) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(body("confluence_page_not_found", e.getMessage(), null));
    }

    @ExceptionHandler(ConfluenceService.NotConfiguredException.class)
    ResponseEntity<Map<String, Object>> confluenceOff(ConfluenceService.NotConfiguredException e) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(body("confluence_not_configured", e.getMessage(), null));
    }

    /** The page points at its Jira field choices on this. */
    @ExceptionHandler(JiraLookupService.VersionRequiredException.class)
    ResponseEntity<Map<String, Object>> versionRequired(JiraLookupService.VersionRequiredException e) {
        return ResponseEntity.badRequest().body(body("jira_version_required", e.getMessage(), null));
    }

    @ExceptionHandler(JiraLookupService.FieldsRequiredException.class)
    ResponseEntity<Map<String, Object>> fieldsRequired(JiraLookupService.FieldsRequiredException e) {
        return ResponseEntity.badRequest().body(body("jira_fields_required", e.getMessage(), null));
    }

    @ExceptionHandler(UpstreamException.class)
    ResponseEntity<Map<String, Object>> upstream(UpstreamException e) {
        log.warn("{}", e.getMessage());
        return ResponseEntity.status(HttpStatus.BAD_GATEWAY)
                .body(body(e.system().toLowerCase() + "_error", e.getMessage(), null));
    }

    @ExceptionHandler(IllegalArgumentException.class)
    ResponseEntity<Map<String, Object>> badRequest(IllegalArgumentException e) {
        return ResponseEntity.badRequest().body(body("bad_request", e.getMessage(), null));
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    ResponseEntity<Map<String, Object>> invalid(MethodArgumentNotValidException e) {
        List<Map<String, String>> fields = e.getBindingResult().getFieldErrors().stream()
                .map(VeracodeExceptionHandler::fieldError)
                .toList();
        return ResponseEntity.badRequest().body(body("validation_failed", "request payload is invalid", fields));
    }

    private static Map<String, String> fieldError(FieldError error) {
        Map<String, String> entry = new LinkedHashMap<>();
        entry.put("field", error.getField());
        entry.put("message", error.getDefaultMessage());
        return entry;
    }

    private static Map<String, Object> body(String error, String message, List<Map<String, String>> fields) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("error", error);
        body.put("message", message);
        if (fields != null) {
            body.put("fields", fields);
        }
        body.put("time", Instant.now().toString());
        return body;
    }
}
