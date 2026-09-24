package com.creed.report.service;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ResponseStatus;

/**
 * Raised when a merge is asked for in a way that cannot be served — today only a copy count outside
 * the allowed range. Annotated so Spring MVC answers <b>400</b> rather than the 500 a bare
 * {@link IllegalArgumentException} would produce, which is this module's rule for every other piece
 * of caller input (see {@code UnknownReportTypeException}, {@code InvalidTableDefinitionException}).
 *
 * <p>Refused rather than clamped: a silently capped count answers a document nobody asked for, and
 * the caller has no way to tell that is what happened.
 */
@ResponseStatus(HttpStatus.BAD_REQUEST)
public class InvalidMergeRequestException extends IllegalArgumentException {

    public InvalidMergeRequestException(String message) {
        super(message);
    }
}
