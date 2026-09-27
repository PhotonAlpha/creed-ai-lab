package com.creed.resource.envmatrix.service.splunk;

/**
 * Logs the shared account into Splunk Web and hands back the session cookie's value.
 * {@link RestClientSplunkLoginClient} is the implementation; with {@code env-matrix.splunk.enabled}
 * off it fabricates the value instead of calling Splunk.
 */
public interface SplunkLoginClient {

    /**
     * @throws SplunkLoginException when Splunk answers without the session cookie, or cannot be reached
     */
    Result login();

    /** Which implementation is live — shown on the page so a fabricated cookie is never mistaken for one. */
    String mode();

    /**
     * @param cookieValue the session cookie's value — a credential: never log it, never audit it
     * @param httpStatus  the login POST's status (0 in mock mode)
     */
    record Result(String cookieValue, int httpStatus) {
        @Override
        public String toString() {
            return "Result[cookieValue=***, httpStatus=" + httpStatus + "]";
        }
    }

    /**
     * @param reason     short code for the audit trail — {@code no_session_cookie}, {@code io_error}, …
     * @param httpStatus the status Splunk answered with, or 0 when it answered nothing
     */
    class SplunkLoginException extends RuntimeException {
        private final String reason;
        private final int httpStatus;

        public SplunkLoginException(String reason, int httpStatus, String message, Throwable cause) {
            super(message, cause);
            this.reason = reason;
            this.httpStatus = httpStatus;
        }

        public String reason() {
            return reason;
        }

        public int httpStatus() {
            return httpStatus;
        }
    }
}
