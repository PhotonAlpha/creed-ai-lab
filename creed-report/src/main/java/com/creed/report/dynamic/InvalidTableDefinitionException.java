package com.creed.report.dynamic;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ResponseStatus;

/**
 * Raised when the caller's {@code headers} / {@code data} cannot make a table — no headers,
 * unparseable JSON, a JSON shape that is not a list of rows, or a payload over the configured
 * limits. Annotated so Spring MVC answers 400: it is bad input, not a server fault, exactly like
 * {@link com.creed.report.export.UnknownReportTypeException}.
 */
@ResponseStatus(HttpStatus.BAD_REQUEST)
public class InvalidTableDefinitionException extends IllegalArgumentException {

    public InvalidTableDefinitionException(String message) {
        super(message);
    }

    public InvalidTableDefinitionException(String message, Throwable cause) {
        super(message, cause);
    }
}
