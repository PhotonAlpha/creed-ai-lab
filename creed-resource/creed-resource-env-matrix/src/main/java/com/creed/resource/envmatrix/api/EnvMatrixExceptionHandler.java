package com.creed.resource.envmatrix.api;

import com.creed.resource.envmatrix.service.AesCryptoService;
import com.creed.resource.envmatrix.service.AesRecordService;
import com.creed.resource.envmatrix.service.EnvMatrixService;
import com.creed.resource.envmatrix.service.ReleaseService;
import com.creed.resource.envmatrix.service.splunk.SplunkLoginClient;
import com.creed.resource.envmatrix.service.splunk.SplunkSessionService;
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

    @ExceptionHandler(ReleaseService.ReleaseNotFoundException.class)
    ResponseEntity<Map<String, Object>> releaseNotFound(ReleaseService.ReleaseNotFoundException e) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(body("not_found", e.getMessage(), null));
    }

    @ExceptionHandler(ReleaseService.DuplicateReleaseException.class)
    ResponseEntity<Map<String, Object>> duplicateRelease(ReleaseService.DuplicateReleaseException e) {
        return ResponseEntity.status(HttpStatus.CONFLICT).body(body("duplicate_release", e.getMessage(), null));
    }

    @ExceptionHandler(SplunkSessionService.InvalidOtpException.class)
    ResponseEntity<Map<String, Object>> invalidOtp(SplunkSessionService.InvalidOtpException e) {
        return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(body(
                "replayed".equals(e.reason()) ? "otp_replayed" : "otp_invalid", e.getMessage(), null));
    }

    @ExceptionHandler(SplunkSessionService.TooManyAttemptsException.class)
    ResponseEntity<Map<String, Object>> tooManyAttempts(SplunkSessionService.TooManyAttemptsException e) {
        return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS)
                .header("Retry-After", Long.toString(e.retryAfterSeconds()))
                .body(body("too_many_attempts", e.getMessage(), null));
    }

    @ExceptionHandler(SplunkSessionService.NotConfiguredException.class)
    ResponseEntity<Map<String, Object>> notConfigured(SplunkSessionService.NotConfiguredException e) {
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).body(body("not_configured", e.getMessage(), null));
    }

    @ExceptionHandler(SplunkSessionService.CodeHiddenException.class)
    ResponseEntity<Map<String, Object>> codeHidden(SplunkSessionService.CodeHiddenException e) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(body("code_hidden", e.getMessage(), null));
    }

    /** Splunk, not this service, is what failed — hence 502, with the audit reason as the error code. */
    @ExceptionHandler(SplunkLoginClient.SplunkLoginException.class)
    ResponseEntity<Map<String, Object>> splunkLogin(SplunkLoginClient.SplunkLoginException e) {
        return ResponseEntity.status(HttpStatus.BAD_GATEWAY).body(body("splunk_" + e.reason(), e.getMessage(), null));
    }

    @ExceptionHandler(AesRecordService.AesRecordNotFoundException.class)
    ResponseEntity<Map<String, Object>> aesNotFound(AesRecordService.AesRecordNotFoundException e) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(body("not_found", e.getMessage(), null));
    }

    @ExceptionHandler(AesRecordService.DuplicateAesRecordException.class)
    ResponseEntity<Map<String, Object>> aesDuplicate(AesRecordService.DuplicateAesRecordException e) {
        return ResponseEntity.status(HttpStatus.CONFLICT).body(body("duplicate_aes_record", e.getMessage(), null));
    }

    /** A key or IV that cannot be used at all: a 400 shaped like a bean-validation failure, naming the field. */
    @ExceptionHandler(AesCryptoService.InvalidKeyMaterialException.class)
    ResponseEntity<Map<String, Object>> aesKeys(AesCryptoService.InvalidKeyMaterialException e) {
        Map<String, String> field = new LinkedHashMap<>();
        field.put("field", e.field());
        field.put("message", e.getMessage());
        return ResponseEntity.badRequest().body(body("validation_failed", "request payload is invalid", List.of(field)));
    }

    @ExceptionHandler(AesRecordService.DuplicatePropertyKeyException.class)
    ResponseEntity<Map<String, Object>> aesDuplicateKey(AesRecordService.DuplicatePropertyKeyException e) {
        Map<String, String> field = new LinkedHashMap<>();
        field.put("field", e.field());
        field.put("message", e.getMessage());
        return ResponseEntity.badRequest().body(body("validation_failed", "request payload is invalid", List.of(field)));
    }

    /** The request was well-formed; the value just does not decrypt with these keys. */
    @ExceptionHandler(AesCryptoService.DecryptException.class)
    ResponseEntity<Map<String, Object>> aesDecrypt(AesCryptoService.DecryptException e) {
        return ResponseEntity.unprocessableEntity().body(body("decrypt_failed", e.getMessage(), null));
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
