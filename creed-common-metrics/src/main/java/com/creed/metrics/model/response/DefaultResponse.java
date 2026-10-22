package com.creed.metrics.model.response;

/**
 * {@link GenericResponse} pinned to {@link DefaultResponseHeader} / {@link DefaultResponseBody}, so controllers
 * and clients can name it without generics. Any {@code DefaultResponseBody} subclass can be passed in; it
 * serializes with its own fields. Because the type arguments are fixed in the superclass signature, Jackson
 * reads {@code DefaultResponse.class} fully typed.
 */
public class DefaultResponse extends GenericResponse<DefaultResponseHeader, DefaultResponseBody> {

    public DefaultResponse() {
    }

    public DefaultResponse(DefaultResponseHeader header, DefaultResponseBody body) {
        setServiceResponseHeader(header);
        setServiceResponseBody(body);
    }
}
