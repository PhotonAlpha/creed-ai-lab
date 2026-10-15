package com.creed.resource.veracode.client.confluence;

import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.service.annotation.GetExchange;
import org.springframework.web.service.annotation.HttpExchange;

/**
 * Confluence REST API v1 ({@code /rest/api/content}). Same path on Data Center and Cloud; on Cloud the
 * base URL ends in {@code /wiki}.
 */
@HttpExchange(url = "/rest/api", accept = "application/json")
public interface ConfluenceClient {

    /**
     * {@code expand=body.storage,version}: without {@code body.storage} the page comes back with no
     * body at all, and {@code body.view} would be rendered HTML with macros expanded - noisier to parse
     * than the storage format the editor writes.
     */
    @GetExchange("/content/{id}")
    ConfluencePage getPage(@PathVariable String id, @RequestParam("expand") String expand);
}
