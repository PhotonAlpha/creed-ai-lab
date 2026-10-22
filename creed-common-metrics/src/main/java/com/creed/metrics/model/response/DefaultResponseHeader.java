package com.creed.metrics.model.response;

import com.creed.metrics.model.request.GenericRequestContext;

/**
 * {@link GenericResponseHeader} pinned to the default contexts. Constructor rather than a static {@code of}:
 * one here would have the same erasure as {@link GenericResponseHeader#of} and clash with it.
 */
public class DefaultResponseHeader extends GenericResponseHeader<GenericRequestContext, GenericResponseContext> {

    public DefaultResponseHeader() {
    }

    public DefaultResponseHeader(GenericRequestContext requestContext, GenericResponseContext responseContext) {
        setRequestContext(requestContext);
        setResponseContext(responseContext);
    }
}
