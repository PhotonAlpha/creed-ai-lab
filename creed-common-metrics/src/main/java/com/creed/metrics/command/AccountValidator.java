package com.creed.metrics.command;

import com.creed.metrics.model.request.GenericRequest;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

/** Sample rule: the caller must identify its channel. */
@Component
public class AccountValidator implements Validator<GenericRequest<?, ?>> {
    @Override
    public boolean validate(GenericRequest<?, ?> request) {
        var header = request.getServiceRequestHeader();
        return header != null && header.getRequesterContext() != null
                && StringUtils.hasText(header.getRequesterContext().getChannelId());
    }
}
