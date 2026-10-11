package com.creed.report.markdown;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ResponseStatus;

/** The document has no summary table, or the table has no row asked for — the caller's input, so 400. */
@ResponseStatus(HttpStatus.BAD_REQUEST)
public class ReviewSummaryNotFoundException extends RuntimeException {

    public ReviewSummaryNotFoundException(String message) {
        super(message);
    }
}
