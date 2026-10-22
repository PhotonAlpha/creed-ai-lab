package com.creed.metrics.model.request;

import com.creed.metrics.model.GenericProfile;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.Data;

@JsonInclude(JsonInclude.Include.NON_NULL)
@JsonIgnoreProperties(ignoreUnknown = true)
@Data
public class GenericRequestBody<P extends GenericProfile<?>>{
    private P profile;
}
