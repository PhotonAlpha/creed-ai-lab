package com.creed.metrics.command;

import com.creed.metrics.model.DefaultProfile;
import com.creed.metrics.model.GenericAccount;
import com.creed.metrics.model.GenericCustomInformation;
import com.creed.metrics.model.request.GenericRequest;
import com.creed.metrics.model.request.GenericRequestContext;
import com.creed.metrics.model.response.DefaultResponse;
import com.creed.metrics.model.response.DefaultResponseHeader;
import com.creed.metrics.model.response.EnhancedResponseBody;
import com.creed.metrics.model.response.GenericResponseContext;
import org.springframework.stereotype.Component;

import java.time.OffsetDateTime;
import java.util.List;

/** Sample command: echoes the requester context and returns a fixed account list. */
@Component
public class AccountCommand extends AbstractCommand<GenericRequest<?, ?>, DefaultResponse> {
    @Override
    public DefaultResponse execute(GenericRequest<?, ?> request) {
        GenericRequestContext requestContext = request.getServiceRequestHeader() == null
                ? null : request.getServiceRequestHeader().getRequesterContext();

        var responseContext = new GenericResponseContext();
        responseContext.setStatusCode("0000");
        responseContext.setStatusDescription("SUCCESS");
        responseContext.setResponseDateTime(OffsetDateTime.now().toString());

        var customerInfo = new GenericCustomInformation();
        customerInfo.setUserId(requestContext == null ? null : requestContext.getRequestUserId());
        var account = new GenericAccount();
        account.setAccountId("hsadkjjhdsa");

        var body = new EnhancedResponseBody();
        body.setProfile(new DefaultProfile(customerInfo));
        body.setAccounts(List.of(account));
        return new DefaultResponse(new DefaultResponseHeader(requestContext, responseContext), body);
    }
}
