package com.creed.resource.veracode.service;

import com.creed.resource.veracode.client.jira.JiraErrorBody;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClientResponseException;

import java.util.function.Supplier;

/**
 * Confluence or Jira failed - answered as 502, because this service did nothing wrong.
 *
 * @param status the upstream's HTTP status, {@code 0} when it could not be reached at all
 */
public class UpstreamException extends RuntimeException {

    private final String system;
    private final int status;

    public UpstreamException(String system, int status, String message) {
        super(system + (status > 0 ? " answered " + status : " unreachable") + ": " + message);
        this.system = system;
        this.status = status;
    }

    public String system() {
        return system;
    }

    public int status() {
        return status;
    }

    static <T> T call(String system, Supplier<T> call) {
        try {
            return call.get();
        } catch (RestClientResponseException e) {
            throw new UpstreamException(system, e.getStatusCode().value(), detail(e));
        } catch (ResourceAccessException e) {
            throw new UpstreamException(system, 0, e.getMostSpecificCause().toString());
        }
    }

    /** Jira's own explanation when the body has one ("versions: Version name '2.5.0' is not valid"). */
    static String detail(RestClientResponseException e) {
        try {
            JiraErrorBody body = e.getResponseBodyAs(JiraErrorBody.class);
            if (body != null && !body.describe().isEmpty()) {
                return body.describe();
            }
        } catch (RuntimeException ignored) {
            // Not Jira's envelope (an HTML error page, Confluence's own shape) - fall back to the status.
        }
        return e.getStatusText().isEmpty() ? e.getStatusCode().toString() : e.getStatusText();
    }
}
