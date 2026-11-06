package com.creed.resource.veracode.client.confluence;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;
import java.util.Map;

/** One page of a content's attachments; {@code _links.next} is there while more follow. */
@JsonIgnoreProperties(ignoreUnknown = true)
public record ConfluenceAttachmentPage(List<Attachment> results, int start, int limit, int size,
                                       @JsonProperty("_links") Map<String, String> links) {

    public boolean hasNext() {
        return links != null && links.get("next") != null;
    }

    /**
     * @param extensions Cloud's (and Data Center's) {@code mediaType} and {@code fileSize}
     * @param metadata   Data Center 6/7 put the {@code mediaType} here
     * @param links      {@code download}: the file's path under the base URL
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Attachment(String id, String title, Extensions extensions, Metadata metadata, Version version,
                             @JsonProperty("_links") Map<String, String> links) {

        public String mediaType() {
            return extensions != null && extensions.mediaType() != null ? extensions.mediaType()
                    : metadata != null ? metadata.mediaType() : null;
        }

        public Long fileSize() {
            return extensions == null ? null : extensions.fileSize();
        }

        public String download() {
            return links == null ? null : links.get("download");
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Extensions(String mediaType, Long fileSize) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Metadata(String mediaType) {
    }

    /** @param when ISO-8601, when this version was uploaded */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Version(int number, String when, By by) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record By(String displayName) {
    }
}
