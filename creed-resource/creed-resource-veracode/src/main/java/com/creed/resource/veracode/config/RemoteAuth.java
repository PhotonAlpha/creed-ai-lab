package com.creed.resource.veracode.config;

import org.springframework.http.HttpHeaders;

import java.nio.charset.StandardCharsets;

/**
 * How this service authenticates to Confluence / Jira.
 *
 * <ul>
 *   <li>{@code BEARER} - a Data Center personal access token ({@code Authorization: Bearer <token>});</li>
 *   <li>{@code BASIC} - Atlassian Cloud: the account e-mail as {@code username}, an API token as {@code token};</li>
 *   <li>{@code NONE} - the local mocks.</li>
 * </ul>
 * The token is never logged; {@link #toString()} masks it.
 */
public record RemoteAuth(Type type, String username, String token) {

    public enum Type { NONE, BEARER, BASIC }

    public RemoteAuth {
        type = type == null ? Type.NONE : type;
    }

    /** The {@code Authorization} header value, or {@code null} for {@link Type#NONE}. */
    public String headerValue() {
        return switch (type) {
            case NONE -> null;
            case BEARER -> "Bearer " + require(token, "token");
            case BASIC -> "Basic " + HttpHeaders.encodeBasicAuth(
                    require(username, "username"), require(token, "token"), StandardCharsets.UTF_8);
        };
    }

    private String require(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalStateException("auth type " + type + " needs a " + name);
        }
        return value;
    }

    @Override
    public String toString() {
        return "RemoteAuth[type=" + type + ", username=" + username
                + ", token=" + (token == null || token.isBlank() ? "<unset>" : "****") + "]";
    }
}
