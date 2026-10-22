package com.creed.metrics.model.response;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.Data;

@JsonInclude(JsonInclude.Include.NON_NULL)
@JsonIgnoreProperties(ignoreUnknown = true)
@Data
public class GenericResponseContext {
    private String statusCode;
    private String statusDescription;
    private String traceId;
    private String responseDateTime;
}
