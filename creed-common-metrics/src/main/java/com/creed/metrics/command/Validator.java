package com.creed.metrics.command;

import com.creed.metrics.model.request.GenericRequest;

public interface Validator<T extends GenericRequest<?, ?>> {
    boolean validate(T request);
}
