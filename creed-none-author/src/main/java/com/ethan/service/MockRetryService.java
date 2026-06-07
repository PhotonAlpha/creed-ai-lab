package com.ethan.service;

import io.github.resilience4j.retry.annotation.Retry;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.Optional;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Resilience4j equivalent of the former Spring Retry usage.
 *
 * <p>Spring Retry version this replaces:
 * <pre>{@code
 * @Retryable(value = {IllegalStateException.class},
 *            maxAttemptsExpression = "${app.retry.max-attempts}",
 *            backoff = @Backoff(delayExpression = "${app.retry.delay}",
 *                               maxDelayExpression = "${app.retry.max-delay}"))
 * public Optional<Object> unstableCall() { ... }
 *
 * @Recover
 * public Optional<Object> onExceptionRecovery(IllegalStateException e) { ... }
 * }</pre>
 *
 * <p>The annotation only configures <b>which</b> retry instance to use; the actual
 * {@code max-attempts}, {@code wait-duration}, exponential backoff and
 * {@code retry-exceptions} live in {@code application.yml} under
 * {@code resilience4j.retry.instances.mockRetry} — the equivalent of the
 * {@code maxAttemptsExpression} / {@code delayExpression} / {@code maxDelayExpression}
 * SpEL values above.
 *
 * @author EthanCao
 * @description creed-ai-lab
 * @date 2026-06-07
 */
@Service
@Slf4j
public class MockRetryService {

    /** Name of the retry instance configured in application.yml. */
    public static final String RETRY_INSTANCE = "mockRetry";

    /**
     * Flaky operation that fails ~70% of the time with {@link IllegalStateException}.
     * Resilience4j retries it per the {@code mockRetry} config; once attempts are
     * exhausted the {@code fallbackMethod} is invoked instead of propagating the error.
     */
    @Retry(name = RETRY_INSTANCE, fallbackMethod = "onExceptionRecovery")
    public Optional<Object> unstableCall() {
        if (ThreadLocalRandom.current().nextInt(10) < 7) {
            log.warn("unstableCall failed, will be retried by resilience4j");
            throw new IllegalStateException("transient failure");
        }
        log.info("unstableCall succeeded");
        return Optional.of("success");
    }

    /**
     * Fallback invoked after all retry attempts are exhausted — the resilience4j
     * counterpart of Spring Retry's {@code @Recover}. The signature must match the
     * retried method's return type, with the triggering exception as the last argument.
     */
    public Optional<Object> onExceptionRecovery(IllegalStateException e) {
        log.error("all retry attempts exhausted, recovering: {}", e.getMessage());
        return Optional.empty();
    }
}