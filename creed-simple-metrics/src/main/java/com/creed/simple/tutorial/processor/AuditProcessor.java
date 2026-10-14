package com.creed.simple.tutorial.processor;

import org.apache.camel.Exchange;
import org.apache.camel.Processor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.concurrent.atomic.AtomicLong;

/**
 * Used by API-5 and API-6: the audit processor reached through {@code <wireTap>}.
 *
 * <p>wireTap is fire-and-forget: it drops a <b>copy</b> of the Exchange onto another route,
 * and the main flow neither waits for it nor sees its result. That is why sleeping here
 * does not slow the main chain down.
 */
@Component("auditProcessor")
public class AuditProcessor implements Processor {

    private static final Logger log = LoggerFactory.getLogger(AuditProcessor.class);
    private static final AtomicLong COUNTER = new AtomicLong();

    @Override
    public void process(Exchange exchange) throws InterruptedException {
        long seq = COUNTER.incrementAndGet();
        // Deliberately slow, so the console shows the main flow replying while the audit is still running
        Thread.sleep(300L);
        log.info("[AUDIT #{}] thread={} persisted tapped copy: body={}",
                seq, Thread.currentThread().getName(), exchange.getIn().getBody());
    }
}
