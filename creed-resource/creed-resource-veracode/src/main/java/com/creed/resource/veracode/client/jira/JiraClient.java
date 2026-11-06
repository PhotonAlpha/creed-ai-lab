package com.creed.resource.veracode.client.jira;

import org.springframework.http.HttpHeaders;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.service.annotation.GetExchange;
import org.springframework.web.service.annotation.HttpExchange;
import org.springframework.web.service.annotation.PostExchange;

/**
 * Jira Data Center REST API v2, <b>the issue resource only</b> ({@code /rest/api/2/issue/**}, the "Issue"
 * group of the Jira 11.0.2 reference) - asked for: no {@code /search}, {@code /myself} or
 * {@code /project/{key}/versions}. Finding is therefore picker + get, the project's versions come from the
 * create metadata, and so does the "test connection".
 *
 * <p>Every method takes the {@code Authorization} value of the user's token from the page
 * ({@code JiraToken.authorization()}). There is no service account behind it: the client has no default
 * header, so whatever Jira does, it does as the user who asked.
 */
@HttpExchange(url = "/rest/api/2/issue", accept = "application/json")
public interface JiraClient {

    @PostExchange(contentType = "application/json")
    JiraCreatedIssue createIssue(@RequestHeader(HttpHeaders.AUTHORIZATION) String authorization,
                                 @RequestBody JiraCreateIssueRequest request);

    /**
     * The issue picker: issues whose key or summary words match {@code query}, within {@code currentJQL}.
     * Jira caps the result (it is an auto-complete), so the JQL is kept narrow - one finding per call.
     */
    @GetExchange("/picker")
    JiraPickerResult picker(@RequestHeader(HttpHeaders.AUTHORIZATION) String authorization,
                            @RequestParam String query,
                            @RequestParam String currentJQL,
                            @RequestParam boolean showSubTasks);

    /** @param fields comma-separated; the default would be every field, comments and all */
    @GetExchange("/{key}")
    JiraIssue issue(@RequestHeader(HttpHeaders.AUTHORIZATION) String authorization,
                    @PathVariable String key,
                    @RequestParam String fields);

    /** The issue types the caller may create in the project - empty without the Create Issues permission. */
    @GetExchange("/createmeta/{project}/issuetypes")
    JiraPage<JiraIssueType> createIssueTypes(@RequestHeader(HttpHeaders.AUTHORIZATION) String authorization,
                                             @PathVariable String project,
                                             @RequestParam int startAt,
                                             @RequestParam int maxResults);

    /**
     * The issue type's create screen. Its {@code versions} field lists the versions an issue can be filed
     * against - which is also why JQL never names any other: {@code affectedVersion = "x"} for a version the
     * project does not have is a 400, not "no match".
     */
    @GetExchange("/createmeta/{project}/issuetypes/{issueTypeId}")
    JiraPage<JiraFieldMeta> createFields(@RequestHeader(HttpHeaders.AUTHORIZATION) String authorization,
                                         @PathVariable String project,
                                         @PathVariable String issueTypeId,
                                         @RequestParam int startAt,
                                         @RequestParam int maxResults);
}
