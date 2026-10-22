package com.creed.metrics;

import com.creed.metrics.annotation.CommandMapping;
import com.creed.metrics.model.request.GenericRequest;
import com.creed.metrics.model.response.DefaultResponse;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class AccountCommandController {

    @PostMapping("/account/list")
    @CommandMapping(command = "accountCommand", validator = "accountValidator")
    DefaultResponse accountList(@RequestBody GenericRequest<?, ?> request) {
        return null;
    }
}
