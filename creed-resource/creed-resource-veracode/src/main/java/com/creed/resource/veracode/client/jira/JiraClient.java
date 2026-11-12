package com.creed.resource.veracode.client.jira;

import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.util.MultiValueMap;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.service.annotation.GetExchange;
import org.springframework.web.service.annotation.HttpExchange;
import org.springframework.web.service.annotation.PostExchange;
import org.springframework.web.service.annotation.PutExchange;

/**
 * Jira REST API v2 (Data Center 11.0.2 and Cloud): the issue resource ({@code /rest/api/2/issue/**}) to create
 * and for the create metadata, and the JQL search to find existing issues - the search was added (2026-11-06,
 * asked for) because the picker meant one call per finding plus a read per candidate. The search is the one
 * place the two deployments differ: Cloud retired {@code /rest/api/2/search}, its replacement is
 * {@code /rest/api/3/search/jql} (the fields read here have the same shape in v3; the description, ADF in v3, is
 * not read).
 *
 * <p>Every method takes the {@code Authorization} value of the user's token from the page
 * ({@code JiraToken.authorization()}). There is no service account behind it: the client has no default
 * header, so whatever Jira does, it does as the user who asked.
 */
@HttpExchange(accept = "application/json")
public interface JiraClient {

    @PostExchange(url = "/rest/api/2/issue", contentType = "application/json")
    JiraCreatedIssue createIssue(@RequestHeader(HttpHeaders.AUTHORIZATION) String authorization,
                                 @RequestBody JiraCreateIssueRequest request);

    /**
     * One issue's fields - read before labelling an existing issue for another application (2026-11-13): the new
     * description keeps the tables of applications the analysis does not hold. @param fields comma-separated
     */
    @GetExchange("/rest/api/2/issue/{key}")
    JiraIssue issue(@RequestHeader(HttpHeaders.AUTHORIZATION) String authorization,
                    @PathVariable String key,
                    @RequestParam String fields);

    /**
     * Jira answers 204: the body is {@code {"update": {"labels": [{"add": "x"}]}}} - only the named labels change - and
     * with a labelling, {@code "fields": {"description": "..."}}.
     */
    @PutExchange(url = "/rest/api/2/issue/{key}", contentType = "application/json")
    void updateIssue(@RequestHeader(HttpHeaders.AUTHORIZATION) String authorization,
                     @PathVariable String key,
                     @RequestBody java.util.Map<String, Object> update);

    /**
     * Attaches files to an issue: multipart, one {@code file} part each. Jira refuses it (403, XSRF check) without
     * {@code X-Atlassian-Token: no-check}. Answers the attachments created; nothing here reads them.
     */
    @PostExchange(url = "/rest/api/2/issue/{key}/attachments", contentType = MediaType.MULTIPART_FORM_DATA_VALUE)
    void addAttachments(@RequestHeader(HttpHeaders.AUTHORIZATION) String authorization,
                        @RequestHeader("X-Atlassian-Token") String xsrf,
                        @PathVariable String key,
                        @RequestBody MultiValueMap<String, HttpEntity<?>> files);

    /** Data Center: paged by {@code startAt}. @param fields comma-separated */
    @GetExchange("/rest/api/2/search")
    JiraSearchResult search(@RequestHeader(HttpHeaders.AUTHORIZATION) String authorization,
                            @RequestParam String jql,
                            @RequestParam String fields,
                            @RequestParam int startAt,
                            @RequestParam int maxResults);

    /** Jira Cloud: paged by {@code nextPageToken} ({@code null} for the first page). */
    @GetExchange("/rest/api/3/search/jql")
    JiraSearchResult searchJql(@RequestHeader(HttpHeaders.AUTHORIZATION) String authorization,
                               @RequestParam String jql,
                               @RequestParam String fields,
                               @RequestParam(required = false) String nextPageToken,
                               @RequestParam int maxResults);

    /** The issue types the caller may create in the project - empty without the Create Issues permission. */
    @GetExchange("/rest/api/2/issue/createmeta/{project}/issuetypes")
    JiraPage<JiraIssueType> createIssueTypes(@RequestHeader(HttpHeaders.AUTHORIZATION) String authorization,
                                             @PathVariable String project,
                                             @RequestParam int startAt,
                                             @RequestParam int maxResults);

    /**
     * The issue type's create screen. Its {@code versions} field lists the versions an issue can be filed
     * against - which is also why JQL never names any other: {@code affectedVersion = "x"} for a version the
     * project does not have is a 400, not "no match".
     */
    @GetExchange("/rest/api/2/issue/createmeta/{project}/issuetypes/{issueTypeId}")
    JiraPage<JiraFieldMeta> createFields(@RequestHeader(HttpHeaders.AUTHORIZATION) String authorization,
                                         @PathVariable String project,
                                         @PathVariable String issueTypeId,
                                         @RequestParam int startAt,
                                         @RequestParam int maxResults);
}
