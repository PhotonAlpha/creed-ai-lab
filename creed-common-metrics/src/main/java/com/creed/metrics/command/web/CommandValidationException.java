package com.creed.metrics.command.web;

import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

/** A {@link com.creed.metrics.command.Validator} rejected the request: answered as 400. */
public class CommandValidationException extends ResponseStatusException {

    public CommandValidationException(String validatorName) {
        super(HttpStatus.BAD_REQUEST, "Request rejected by validator '" + validatorName + "'");
    }
}
