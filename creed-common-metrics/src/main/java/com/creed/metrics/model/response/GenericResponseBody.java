package com.creed.metrics.model.response;

import com.creed.metrics.model.GenericProfile;
import com.fasterxml.jackson.annotation.JsonAnyGetter;
import com.fasterxml.jackson.annotation.JsonAnySetter;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.Data;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * {@code profile} is the one typed field every service shares; anything else ({@code accounts}, ...) goes
 * through {@link #with} and is flattened next to it in the JSON. On the way back in, unknown fields land
 * here as plain Maps/Lists — a caller that needs them typed should subclass and declare the field.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
@JsonIgnoreProperties(ignoreUnknown = true)
@Data
public class GenericResponseBody<P extends GenericProfile<?>> {
    private P profile;

    private final Map<String, Object> additionalProperties = new LinkedHashMap<>();

    public static <P extends GenericProfile<?>> GenericResponseBody<P> of(P profile) {
        GenericResponseBody<P> body = new GenericResponseBody<>();
        body.setProfile(profile);
        return body;
    }

    @JsonAnySetter
    public GenericResponseBody<P> with(String name, Object value) {
        additionalProperties.put(name, value);
        return this;
    }

    @JsonAnyGetter
    public Map<String, Object> getAdditionalProperties() {
        return additionalProperties;
    }
}
