package com.creed.metrics.command;

import com.creed.metrics.model.request.GenericRequest;

public interface Command <T extends GenericRequest<?,?>, R> {
    R execute(T request);
}
