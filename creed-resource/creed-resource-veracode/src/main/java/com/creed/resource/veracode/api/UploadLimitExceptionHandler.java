package com.creed.resource.veracode.api;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.multipart.MaxUploadSizeExceededException;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Separate from {@link VeracodeExceptionHandler} because that one is scoped to the controller, and an
 * over-size upload fails while the multipart request is resolved - before any handler is chosen - so a
 * scoped advice never sees it and the client gets Boot's whitelabel 500 instead.
 */
@RestControllerAdvice
public class UploadLimitExceptionHandler {

    @ExceptionHandler(MaxUploadSizeExceededException.class)
    ResponseEntity<Map<String, Object>> tooLarge(MaxUploadSizeExceededException e) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("error", "file_too_large");
        body.put("message", "the file exceeds the upload limit (spring.servlet.multipart.max-file-size)");
        body.put("time", Instant.now().toString());
        return ResponseEntity.status(HttpStatus.PAYLOAD_TOO_LARGE).body(body);
    }
}
