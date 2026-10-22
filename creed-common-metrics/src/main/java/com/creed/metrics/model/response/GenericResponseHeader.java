package com.creed.metrics.model.response;

import com.creed.metrics.model.request.GenericRequestContext;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.Data;

@JsonInclude(JsonInclude.Include.NON_NULL)
@JsonIgnoreProperties(ignoreUnknown = true)
@Data
public class GenericResponseHeader <ReqC extends GenericRequestContext, RespC extends GenericResponseContext> {
    private ReqC requestContext;
    private RespC responseContext;

    public static <ReqC extends GenericRequestContext, RespC extends GenericResponseContext> GenericResponseHeader<ReqC, RespC> of(
            ReqC requestContext, RespC responseContext) {
        GenericResponseHeader<ReqC, RespC> header = new GenericResponseHeader<>();
        header.setRequestContext(requestContext);
        header.setResponseContext(responseContext);
        return header;
    }
}
