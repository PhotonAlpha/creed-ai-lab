package com.creed.report.export;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ResponseStatus;

import java.util.Arrays;
import java.util.stream.Collectors;

/**
 * Raised when a request names a report type that no strategy handles. Annotated so Spring MVC
 * answers 400 instead of the 500 a bare {@link IllegalArgumentException} would produce.
 */
@ResponseStatus(HttpStatus.BAD_REQUEST)
public class UnknownReportTypeException extends IllegalArgumentException {

    public UnknownReportTypeException(String code) {
        super("Unknown report type '" + code + "'; supported types: "
              + Arrays.stream(ReportType.values()).map(ReportType::code).collect(Collectors.joining(", ")));
    }
}
