package com.creed.resource.veracode.client.confluence;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;
import java.util.Map;

/** One page of a page's child pages; {@code _links.next} is there while more follow. */
@JsonIgnoreProperties(ignoreUnknown = true)
public record ConfluenceChildPages(List<Child> results, @JsonProperty("_links") Map<String, String> links) {

    public boolean hasNext() {
        return links != null && links.get("next") != null;
    }

    /** @param version with {@code expand=version}: when the page was last edited */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Child(String id, String title, ConfluenceAttachmentPage.Version version) {
    }
}
