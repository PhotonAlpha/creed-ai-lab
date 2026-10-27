package com.creed.report.mail;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ResponseStatus;

/** Bad probe parameters are the caller's mistake: 400, never a 500. */
@ResponseStatus(HttpStatus.BAD_REQUEST)
public class InvalidMailProbeException extends RuntimeException {

    public InvalidMailProbeException(String message) {
        super(message);
    }
}
