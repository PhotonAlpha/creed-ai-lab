package com.creed.metrics.model.response;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.Data;

/**
 * Bounds use {@code <?>} rather than raw types: callers keep their own header/body subclasses, and
 * Jackson still resolves the bound ({@link GenericResponseHeader} / {@link GenericResponseBody}) when
 * the type is read without a {@code TypeReference}.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
@JsonIgnoreProperties(ignoreUnknown = true)
@Data
public class GenericResponse<H extends GenericResponseHeader<?, ?>, B extends GenericResponseBody<?>> {
    private H serviceResponseHeader;
    private B serviceResponseBody;

    public static <H extends GenericResponseHeader<?, ?>, B extends GenericResponseBody<?>> GenericResponse<H, B> of(
            H header, B body) {
        GenericResponse<H, B> response = new GenericResponse<>();
        response.setServiceResponseHeader(header);
        response.setServiceResponseBody(body);
        return response;
    }
}
