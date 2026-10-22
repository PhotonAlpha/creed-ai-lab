package com.creed.metrics.model.request;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.Data;

@JsonInclude(JsonInclude.Include.NON_NULL)
@JsonIgnoreProperties(ignoreUnknown = true)
@Data
public class GenericRequestContext {
    private String channelId;
    private String countryCode;
    private String requesterReferenceNumber;
    private String requestDateTime;
    private String requestUserId;
    private String requestGroupId;
}
