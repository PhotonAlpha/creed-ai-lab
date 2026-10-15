package com.creed.resource.veracode.api;

import com.creed.resource.veracode.service.ReportParser;
import com.creed.resource.veracode.service.UploadedReportStore;
import com.creed.resource.veracode.service.UpstreamException;
import com.creed.resource.veracode.service.VeracodeAnalysisService;
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
 * <p>Scoped to {@link VeracodeController}: the mocks answer in Confluence's and Jira's own error
 * shapes, and must not be rewritten into this one.
 */
@RestControllerAdvice(assignableTypes = VeracodeController.class)
@Slf4j
public class VeracodeExceptionHandler {

    @ExceptionHandler(VeracodeAnalysisService.PageNotFoundException.class)
    ResponseEntity<Map<String, Object>> pageNotFound(VeracodeAnalysisService.PageNotFoundException e) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(body("page_not_found", e.getMessage(), null));
    }

    /** The page was read; it just holds no report this service can understand. */
    @ExceptionHandler(ReportParser.ReportFormatException.class)
    ResponseEntity<Map<String, Object>> unreadable(ReportParser.ReportFormatException e) {
        return ResponseEntity.unprocessableEntity().body(body("report_unreadable", e.getMessage(), null));
    }

    @ExceptionHandler(ReportParser.UnsupportedFileException.class)
    ResponseEntity<Map<String, Object>> unsupported(ReportParser.UnsupportedFileException e) {
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
