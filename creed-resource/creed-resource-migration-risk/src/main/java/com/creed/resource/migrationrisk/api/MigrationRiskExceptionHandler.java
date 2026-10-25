package com.creed.resource.migrationrisk.api;

import com.creed.resource.migrationrisk.git.GitRepositories;
import com.creed.resource.migrationrisk.service.MigrationRiskService;
import com.creed.resource.migrationrisk.service.AnalysisJobs;
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

    /** Domain failures — the mapping is shared with background jobs ({@link ApiErrors}). */
    @ExceptionHandler({RepositoryGuard.PathNotAllowedException.class, GitRepositories.InvalidRepositoryException.class,
            MigrationRiskService.TooManyChangesException.class, ReleaseStore.NotFoundException.class,
            ObjectOptimisticLockingFailureException.class})
    ResponseEntity<Map<String, Object>> domain(RuntimeException e) {
        ApiErrors.ApiError error = ApiErrors.of(e);
        log.warn("rejected: {} — {}", error.body().get("error"), e.getMessage());
        return ResponseEntity.status(error.status()).body(error.body());
    }

    @ExceptionHandler(AnalysisJobs.BusyException.class)
    ResponseEntity<Map<String, Object>> busy(AnalysisJobs.BusyException e) {
        log.warn("rejected: busy — {}", e.getMessage());
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                .body(ApiErrors.error(HttpStatus.SERVICE_UNAVAILABLE, "busy", e.getMessage(), null).body());
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

    private static Map<String, String> fieldError(FieldError error) {
        Map<String, String> entry = new LinkedHashMap<>();
        entry.put("field", error.getField());
        entry.put("message", error.getDefaultMessage());
        return entry;
    }

    private static Map<String, Object> body(String error, String message, List<Map<String, String>> fields) {
        log.warn("rejected: {} — {}", error, message);
        return ApiErrors.error(HttpStatus.BAD_REQUEST, error, message, fields).body();
    }
}
