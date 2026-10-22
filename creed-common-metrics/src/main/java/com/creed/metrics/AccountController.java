package com.creed.metrics;

import com.creed.metrics.model.response.GenericResponse;
import com.creed.metrics.model.response.GenericResponseBody;
import com.creed.metrics.model.response.GenericResponseHeader;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class AccountController {

    @GetMapping("/account/list")
    public GenericResponse<GenericResponseHeader<?,?>, GenericResponseBody<?>> accountList() {
        return GenericResponse.of(null, null);
    }


}
