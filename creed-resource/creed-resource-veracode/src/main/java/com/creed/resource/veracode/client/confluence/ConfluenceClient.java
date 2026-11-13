package com.creed.resource.veracode.client.confluence;

import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.util.MultiValueMap;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.service.annotation.GetExchange;
import org.springframework.web.service.annotation.HttpExchange;
import org.springframework.web.service.annotation.PostExchange;

import java.net.URI;
import java.util.Map;

/**
 * The Confluence REST API v1 calls this service makes - the same paths on Cloud ({@code /wiki/rest/api}) and Data
 * Center. Every call carries the user's own {@code Authorization}.
 */
@HttpExchange(accept = "application/json")
public interface ConfluenceClient {

    /** Who the token is; Cloud answers an unauthenticated call with {@code type: anonymous}, not 401. */
    @GetExchange("/rest/api/user/current")
    ConfluenceUser currentUser(@RequestHeader(HttpHeaders.AUTHORIZATION) String authorization);

    /** 404 both for no such page and for a page the user may not see. */
    @GetExchange("/rest/api/content/{id}")
    ConfluencePage page(@RequestHeader(HttpHeaders.AUTHORIZATION) String authorization, @PathVariable String id,
                        @RequestParam("expand") String expand);

    /**
     * A new page: {@code {"type": "page", "title", "space": {"key"}, "ancestors": [{"id": parent}], "body": {"storage":
     * {"value", "representation": "storage"}}}}. A title already in the space is Confluence's 400.
     */
    @PostExchange(url = "/rest/api/content", contentType = "application/json")
    ConfluencePage createPage(@RequestHeader(HttpHeaders.AUTHORIZATION) String authorization,
                              @RequestBody Map<String, Object> page);

    /**
     * Attaches files to a page: multipart, one {@code file} part each. Confluence refuses it (403, XSRF check) without
     * {@code X-Atlassian-Token: nocheck}. A file name already on the page becomes a new version of that attachment.
     */
    @PostExchange(url = "/rest/api/content/{id}/child/attachment", contentType = MediaType.MULTIPART_FORM_DATA_VALUE)
    ConfluenceAttachmentPage addAttachments(@RequestHeader(HttpHeaders.AUTHORIZATION) String authorization,
                                            @RequestHeader("X-Atlassian-Token") String xsrf, @PathVariable String id,
                                            @RequestBody MultiValueMap<String, HttpEntity<?>> files);

    /** A page's direct children, paged by {@code start} as the attachments are. */
    @GetExchange("/rest/api/content/{id}/child/page")
    ConfluenceChildPages childPages(@RequestHeader(HttpHeaders.AUTHORIZATION) String authorization,
                                    @PathVariable String id, @RequestParam("start") int start,
                                    @RequestParam("limit") int limit, @RequestParam("expand") String expand);

    /**
     * One attachment by its id ({@code att...}) - the same content resource as a page. {@code expand=container} names
     * the page it is on, {@code _links.download} comes with it: what a download needs, without listing the page (a page
     * with thousands of attachments is dozens of listing calls, and Confluence rate-limits them).
     */
    @GetExchange("/rest/api/content/{id}")
    ConfluenceAttachmentPage.Attachment attachment(@RequestHeader(HttpHeaders.AUTHORIZATION) String authorization,
                                                   @PathVariable String id, @RequestParam("expand") String expand);

    @GetExchange("/rest/api/content/{id}/child/attachment")
    ConfluenceAttachmentPage attachments(@RequestHeader(HttpHeaders.AUTHORIZATION) String authorization,
                                         @PathVariable String id, @RequestParam("start") int start,
                                         @RequestParam("limit") int limit, @RequestParam("expand") String expand);

    /**
     * An attachment's bytes, from its {@code _links.download} under the base URL - or wherever that redirected to
     * (Cloud hands the file to its media service, on another host, which takes no {@code Authorization}).
     * Redirects are not followed by the client: {@code ConfluenceService} follows them itself.
     */
    @GetExchange(accept = "*/*")
    ResponseEntity<byte[]> download(@RequestHeader(value = HttpHeaders.AUTHORIZATION, required = false) String authorization,
                                    URI url);
}
