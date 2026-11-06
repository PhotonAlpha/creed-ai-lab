package com.creed.resource.veracode.service;

import java.nio.charset.StandardCharsets;
import java.util.Base64;

/**
 * The user's Confluence access token, sent per request in {@link #HEADER} - never stored, never logged. The same
 * two kinds as {@link JiraToken}: a Data Center personal access token ({@code Bearer}), or a Cloud API token with
 * the Atlassian account e-mail in {@link #USER_HEADER} ({@code Basic base64(email:token)}).
 */
public record ConfluenceToken(String value, String user) {

    public static final String HEADER = "X-Confluence-Token";
    public static final String USER_HEADER = "X-Confluence-User";

    public ConfluenceToken {
        value = value == null || value.isBlank() ? null : value.trim();
        user = user == null || user.isBlank() ? null : user.trim();
    }

    public boolean cloud() {
        return user != null;
    }

    public ConfluenceToken require() {
        if (value == null) {
            throw new RequiredException();
        }
        return this;
    }

    public String authorization() {
        require();
        return user == null ? "Bearer " + value
                : "Basic " + Base64.getEncoder().encodeToString((user + ":" + value).getBytes(StandardCharsets.UTF_8));
    }

    @Override
    public String toString() {
        return "ConfluenceToken[" + (value == null ? "<unset>" : "****") + (user == null ? "" : ", user=" + user) + "]";
    }

    /** A Confluence request without the user's token. */
    public static class RequiredException extends RuntimeException {
        public RequiredException() {
            super("this page reads Confluence as you: configure your Confluence access token on the page (header "
                    + HEADER + ")");
        }
    }
}
