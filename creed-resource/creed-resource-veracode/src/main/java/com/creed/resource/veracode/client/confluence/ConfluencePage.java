package com.creed.resource.veracode.client.confluence;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

@JsonIgnoreProperties(ignoreUnknown = true)
public record ConfluencePage(String id, String type, String title, Space space) {

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Space(String key, String name) {
    }
}
