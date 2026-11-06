package com.creed.resource.veracode.service;

import java.nio.charset.StandardCharsets;
import java.util.Base64;

/**
 * The Jira access token the user configured on the page, sent per request in {@link #HEADER} - never stored
 * here, never logged.
 *
 * <p>Every Jira call needs it - lookups, the create metadata, creating: the page is shared, and this service has
 * no Jira account of its own, so each user reads what they may see and files issues as themselves.
 *
 * <p>Two kinds, told apart by {@code user}:
 * <ul>
 *   <li>Data Center personal access token, no user: {@code Authorization: Bearer <token>};</li>
 *   <li>Jira Cloud API token with the Atlassian account e-mail ({@link #USER_HEADER}):
 *       {@code Authorization: Basic base64(email:token)}. Cloud does not take an API token as Bearer - that is a
 *       401 on every call.</li>
 * </ul>
 */
public record JiraToken(String value, String user, String issueType) {

    public static final String HEADER = "X-Jira-Token";
    /** The Atlassian account e-mail that goes with a Jira Cloud API token. */
    public static final String USER_HEADER = "X-Jira-User";
    /**
     * The issue type the user picked on the page; absent = {@code veracode.jira.issue-type}. Not a credential, but
     * it travels with the token for the same reason: it is the user's, and every endpoint (lookups need the type's
     * versions, creating its fields) has to see it.
     */
    public static final String ISSUE_TYPE_HEADER = "X-Jira-Issue-Type";

    public static final JiraToken NONE = new JiraToken(null, null, null);

    public JiraToken {
        value = blankToNull(value);
        user = blankToNull(user);
        issueType = blankToNull(issueType);
    }

    /** A Data Center personal access token. */
    public JiraToken(String value) {
        this(value, null, null);
    }

    public JiraToken(String value, String user) {
        this(value, user, null);
    }

    /** The same sign-in, filing another issue type - the connection test checks each creatable one. */
    public JiraToken withIssueType(String name) {
        return new JiraToken(value, user, name);
    }

    /** The page's issue type, else the configured one. */
    public String issueTypeOr(String configured) {
        return issueType != null ? issueType : configured;
    }

    private static String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s.trim();
    }

    /**
     * Jira Cloud: the page sent the Atlassian e-mail with the token. The page asks which kind of Jira the user
     * has, so the kind travels with the token - both use REST API v2, only the auth and user fields differ.
     */
    public boolean cloud() {
        return user != null;
    }

    public boolean present() {
        return value != null;
    }

    /** Refuses a request without a token before anything is read or called. */
    public JiraToken require() {
        if (value == null) {
            throw new RequiredException();
        }
        return this;
    }

    /** The {@code Authorization} value for Jira. */
    public String authorization() {
        require();
        if (user == null) {
            return "Bearer " + value;
        }
        return "Basic " + Base64.getEncoder().encodeToString((user + ":" + value).getBytes(StandardCharsets.UTF_8));
    }

    @Override
    public String toString() {
        return "JiraToken[" + (value == null ? "<unset>" : "****") + (user == null ? "" : ", user=" + user)
                + (issueType == null ? "" : ", issueType=" + issueType) + "]";
    }

    /** A request without the user's token (or, on Jira Cloud, without the e-mail that goes with it). */
    public static class RequiredException extends RuntimeException {
        public RequiredException() {
            super("this page works with Jira as you: configure your Jira access token on the page (header " + HEADER + ")");
        }

        public RequiredException(String message) {
            super(message);
        }
    }
}
