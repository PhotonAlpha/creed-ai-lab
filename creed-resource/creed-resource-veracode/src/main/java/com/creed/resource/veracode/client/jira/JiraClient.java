package com.creed.resource.veracode.client.jira;

import org.springframework.http.HttpHeaders;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.service.annotation.GetExchange;
import org.springframework.web.service.annotation.HttpExchange;
import org.springframework.web.service.annotation.PostExchange;

import java.util.List;

/**
 * Jira REST API v2 - the Data Center / Server API. Jira Cloud still serves {@code /issue} and
 * {@code /project/{key}/versions} under v2, but has retired {@code POST /search} in favour of
 * {@code /rest/api/3/search/jql} (token-based paging, no {@code total}); pointing this client at Cloud
 * needs that one method changed.
 *
 * <p>Every method takes an {@code Authorization} value: the user's token from the page
 * ({@code JiraToken.authorization()}), or {@code null} for the client's configured default header - a
 * null optional header is not sent, and a sent one replaces the default.
 */
@HttpExchange(url = "/rest/api/2", accept = "application/json")
public interface JiraClient {

    /** POST rather than GET: the JQL grows with every finding and would overflow a URL. */
    @PostExchange(url = "/search", contentType = "application/json")
    JiraSearchResponse search(@RequestHeader(name = HttpHeaders.AUTHORIZATION, required = false) String authorization,
                              @RequestBody JiraSearchRequest request);

    @PostExchange(url = "/issue", contentType = "application/json")
    JiraCreatedIssue createIssue(@RequestHeader(name = HttpHeaders.AUTHORIZATION, required = false) String authorization,
                                 @RequestBody JiraCreateIssueRequest request);

    /**
     * Asked before searching: JQL {@code affectedVersion in (...)} naming a version the project does not
     * have is not "no match" - Jira rejects the whole query with a 400.
     */
    @GetExchange("/project/{key}/versions")
    List<JiraVersion> projectVersions(@RequestHeader(name = HttpHeaders.AUTHORIZATION, required = false) String authorization,
                                      @PathVariable String key);

    /** Who the token belongs to - the page's "test connection". */
    @GetExchange("/myself")
    JiraUser myself(@RequestHeader(name = HttpHeaders.AUTHORIZATION, required = false) String authorization);
}
