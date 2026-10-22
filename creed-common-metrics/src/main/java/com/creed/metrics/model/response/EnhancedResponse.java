package com.creed.metrics.model.response;

/**
 * Lets a client read {@link EnhancedResponseBody} back typed ({@code body(EnhancedResponse.class)}).
 * The server side can just return a {@link DefaultResponse} holding the enhanced body.
 */
public class EnhancedResponse extends GenericResponse<DefaultResponseHeader, EnhancedResponseBody> {
}
