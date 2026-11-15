package com.creed.gatewayproxy.api;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.server.ServerWebInputException;

import com.creed.gatewayproxy.service.splunk.BrokerException;

/** The Splunk API's error shape ({@code error}, {@code message}, {@code fields?}, {@code time}), for both controllers. */
@Slf4j
@RestControllerAdvice(assignableTypes = {SplunkController.class, SplunkAdminController.class})
public class SplunkApiErrors {

    @ExceptionHandler(BrokerException.class)
    ResponseEntity<Map<String, Object>> broker(BrokerException e) {
        ResponseEntity.BodyBuilder response = ResponseEntity.status(e.status());
        e.headers().forEach(response::header);
        return response.body(body(e.error(), e.getMessage(), null));
    }

    @ExceptionHandler(SplunkController.FieldException.class)
    ResponseEntity<Map<String, Object>> field(SplunkController.FieldException e) {
        return ResponseEntity.badRequest().body(body("validation_failed", "request payload is invalid",
                List.of(Map.of("field", e.field, "message", e.getMessage()))));
    }

    @ExceptionHandler(ServerWebInputException.class)
    ResponseEntity<Map<String, Object>> unreadable(ServerWebInputException e) {
        return ResponseEntity.badRequest().body(body("validation_failed", "request body is not valid JSON", null));
    }

    /** Most likely the audit store — the broker fails closed rather than issue an unrecorded session. */
    @ExceptionHandler(RuntimeException.class)
    ResponseEntity<Map<String, Object>> internal(RuntimeException e) {
        log.error("Splunk broker request failed", e);
        return ResponseEntity.internalServerError().body(body("internal_error", "the Splunk broker failed — see the server log", null));
    }

    private static Map<String, Object> body(String error, String message, List<Map<String, String>> fields) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("error", error);
        body.put("message", message);
        if (fields != null) body.put("fields", fields);
        body.put("time", Instant.now().toString());
        return body;
    }
}
