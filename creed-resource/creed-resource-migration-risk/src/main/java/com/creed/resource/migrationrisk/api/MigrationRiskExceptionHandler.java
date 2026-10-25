package com.creed.resource.migrationrisk.api;

import com.creed.resource.migrationrisk.git.GitRepositories;
import com.creed.resource.migrationrisk.service.MigrationRiskService;
import com.creed.resource.migrationrisk.service.ReleaseStore;
import com.creed.resource.migrationrisk.service.RepositoryGuard;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import lombok.extern.slf4j.Slf4j;
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

/** The {@code {error, message, fields?, time}} envelope every module answers with; the frontend reads one shape. */
@RestControllerAdvice(assignableTypes = MigrationRiskController.class)
@Slf4j
public class MigrationRiskExceptionHandler {

    @ExceptionHandler(RepositoryGuard.PathNotAllowedException.class)
    ResponseEntity<Map<String, Object>> notAllowed(RepositoryGuard.PathNotAllowedException e) {
        return ResponseEntity.status(HttpStatus.FORBIDDEN)
                .body(body("path_not_allowed", e.getMessage(), field(e.field(), e.getMessage())));
    }

    @ExceptionHandler(GitRepositories.InvalidRepositoryException.class)
    ResponseEntity<Map<String, Object>> invalidRepository(GitRepositories.InvalidRepositoryException e) {
        return ResponseEntity.badRequest()
                .body(body("invalid_repository", e.getMessage(), field(e.field(), e.getMessage())));
    }

    @ExceptionHandler(MigrationRiskService.TooManyChangesException.class)
    ResponseEntity<Map<String, Object>> tooMany(MigrationRiskService.TooManyChangesException e) {
        return ResponseEntity.unprocessableEntity().body(body("too_many_changes", e.getMessage(), null));
    }

    @ExceptionHandler(ReleaseStore.NotFoundException.class)
    ResponseEntity<Map<String, Object>> notFound(ReleaseStore.NotFoundException e) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(body("not_found", e.getMessage(), null));
    }

    /** Someone else reviewed the item in between: reload and decide again rather than overwrite. */
    @ExceptionHandler(ObjectOptimisticLockingFailureException.class)
    ResponseEntity<Map<String, Object>> conflict(ObjectOptimisticLockingFailureException e) {
        return ResponseEntity.status(HttpStatus.CONFLICT)
                .body(body("review_conflict", "the item was reviewed by someone else meanwhile — reload", null));
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    ResponseEntity<Map<String, Object>> invalid(MethodArgumentNotValidException e) {
        List<Map<String, String>> fields = e.getBindingResult().getFieldErrors().stream()
                .map(MigrationRiskExceptionHandler::fieldError)
                .toList();
        return ResponseEntity.badRequest().body(body("validation_failed", "request payload is invalid", fields));
    }

    /** RefactoringMiner or JDT choking on a file: worth a stack trace in the log, not in the response. */
    @ExceptionHandler(IllegalStateException.class)
    ResponseEntity<Map<String, Object>> failed(IllegalStateException e) {
        log.error("analysis failed", e);
        return ResponseEntity.internalServerError().body(body("analysis_failed", e.getMessage(), null));
    }

    /** The page marks the input the failure belongs to — a wrong baseline is not a wrong repository. */
    private static List<Map<String, String>> field(String field, String message) {
        return field == null ? null : List.of(Map.of("field", field, "message", message));
    }

    private static Map<String, String> fieldError(FieldError error) {
        Map<String, String> entry = new LinkedHashMap<>();
        entry.put("field", error.getField());
        entry.put("message", error.getDefaultMessage());
        return entry;
    }

    // Every rejection is logged: a wrong baseline or path otherwise leaves nothing on the server side to go by.
    private static Map<String, Object> body(String error, String message, List<Map<String, String>> fields) {
        log.warn("rejected: {} — {}", error, message);
        return envelope(error, message, fields);
    }

    private static Map<String, Object> envelope(String error, String message, List<Map<String, String>> fields) {
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
