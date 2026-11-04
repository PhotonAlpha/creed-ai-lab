package com.creed.resource.veracode.service;

/**
 * The Jira access token the user configured on the page, sent per request in {@link #HEADER} - never stored
 * here, never logged. Data Center personal access token, so it goes to Jira as {@code Bearer}.
 *
 * <p>Creating issues requires it ({@code veracode.jira.require-user-token}): the issue is then filed as the
 * person who asked, not as this service's account. Searching uses it when present and falls back to the
 * configured {@code veracode.jira.auth} otherwise.
 */
public record JiraToken(String value) {

    public static final String HEADER = "X-Jira-Token";

    public static final JiraToken NONE = new JiraToken(null);

    public JiraToken {
        value = value == null || value.isBlank() ? null : value.trim();
    }

    public boolean present() {
        return value != null;
    }

    /** The {@code Authorization} value for Jira; {@code null} = the client's configured default. */
    public String authorization() {
        return value == null ? null : "Bearer " + value;
    }

    @Override
    public String toString() {
        return "JiraToken[" + (value == null ? "<unset>" : "****") + "]";
    }

    /** Creating without the user's token, while one is required. */
    public static class RequiredException extends RuntimeException {
        public RequiredException() {
            super("creating Jira issues needs your Jira access token: configure it on the page (header " + HEADER + ")");
        }
    }
}
