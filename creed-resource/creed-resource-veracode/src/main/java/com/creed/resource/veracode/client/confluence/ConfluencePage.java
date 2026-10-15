package com.creed.resource.veracode.client.confluence;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

/** The part of Confluence's content JSON this service reads; everything else is ignored. */
@JsonIgnoreProperties(ignoreUnknown = true)
public record ConfluencePage(String id, String title, Version version, Body body,
                             @JsonProperty("_links") Links links) {

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Version(int number, String when) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Body(Storage storage) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Storage(String value, String representation) {
    }

    /** {@code base + webui} is the page's browser URL. */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Links(String base, String webui) {
    }

    public String storageValue() {
        return body == null || body.storage() == null ? null : body.storage().value();
    }
}
