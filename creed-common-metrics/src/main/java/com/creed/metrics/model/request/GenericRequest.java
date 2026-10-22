package com.creed.metrics.model.request;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.Data;

@JsonInclude(JsonInclude.Include.NON_NULL)
@JsonIgnoreProperties(ignoreUnknown = true)
@Data
public class GenericRequest <H extends GenericRequestHeader<?>, B extends GenericRequestBody<?>>{
    private H serviceRequestHeader;
    private B serviceRequestBody;
}
