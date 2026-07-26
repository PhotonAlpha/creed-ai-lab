package com.creed.resource.envmatrix.api;

import com.creed.resource.envmatrix.service.EnvMatrixService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Turns the service's domain failures into the small, predictable error envelope the UI expects:
 * {@code {error, message, fields?, time}}. Without this, a duplicate dimension tuple would surface as
 * a raw 500 with a Postgres constraint name in it — unreadable in a toast.
 */
@RestControllerAdvice
@Slf4j
public class EnvMatrixExceptionHandler {

    @ExceptionHandler(EnvMatrixService.EndpointNotFoundException.class)
    ResponseEntity<Map<String, Object>> notFound(EnvMatrixService.EndpointNotFoundException e) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(body("not_found", e.getMessage(), null));
    }

    @ExceptionHandler(EnvMatrixService.DuplicateEndpointException.class)
    ResponseEntity<Map<String, Object>> duplicate(EnvMatrixService.DuplicateEndpointException e) {
        return ResponseEntity.status(HttpStatus.CONFLICT).body(body("duplicate_endpoint", e.getMessage(), null));
    }

    /** Someone else saved the same row first; the config page should reload and retry. */
    @ExceptionHandler(OptimisticLockingFailureException.class)
    ResponseEntity<Map<String, Object>> stale(OptimisticLockingFailureException e) {
        log.warn("optimistic lock failure: {}", e.getMessage());
        return ResponseEntity.status(HttpStatus.CONFLICT).body(body("stale_write",
                "this endpoint was modified by someone else — reload before saving again", null));
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    ResponseEntity<Map<String, Object>> invalid(MethodArgumentNotValidException e) {
        List<Map<String, String>> fields = e.getBindingResult().getFieldErrors().stream()
                .map(EnvMatrixExceptionHandler::fieldError)
                .toList();
        return ResponseEntity.badRequest().body(body("validation_failed", "request payload is invalid", fields));
    }

    private static Map<String, String> fieldError(FieldError error) {
        Map<String, String> entry = new LinkedHashMap<>();
        entry.put("field", error.getField());
        entry.put("message", error.getDefaultMessage() == null ? "invalid" : error.getDefaultMessage());
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
