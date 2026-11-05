package com.creed.simple.route;

import static com.creed.simple.config.OriginalRequestRoutePolicyFactory.ORIGINAL_REQUEST;

import lombok.extern.slf4j.Slf4j;
import org.apache.camel.Exchange;
import org.apache.camel.Processor;
import org.springframework.stereotype.Component;

@Component
@Slf4j
public class PlainProcessor implements Processor {
    @Override
    public void process(Exchange exchange) throws Exception {
        log.info("PlainProcessor process:{}", exchange.getProperty(ORIGINAL_REQUEST, String.class));
    }
}
