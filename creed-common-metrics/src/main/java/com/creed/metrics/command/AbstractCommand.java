package com.creed.metrics.command;

import com.creed.metrics.model.request.GenericRequest;
import com.creed.metrics.model.response.GenericResponse;

public abstract class AbstractCommand<T extends GenericRequest<?,?>, R extends GenericResponse<?,?>> implements Command<T, R> {
}
