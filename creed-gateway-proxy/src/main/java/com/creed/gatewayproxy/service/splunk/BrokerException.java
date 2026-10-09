package com.creed.gatewayproxy.service.splunk;

import java.util.Map;

/** A refusal with the status and {@code {error, message}} the page expects. */
public class BrokerException extends RuntimeException {

    private final int status;
    private final String error;
    private final Map<String, String> headers;

    public BrokerException(int status, String error, String message) {
        this(status, error, message, Map.of());
    }

    public BrokerException(int status, String error, String message, Map<String, String> headers) {
        super(message);
        this.status = status;
        this.error = error;
        this.headers = headers;
    }

    public int status() {
        return status;
    }

    public String error() {
        return error;
    }

    public Map<String, String> headers() {
        return headers;
    }
}
