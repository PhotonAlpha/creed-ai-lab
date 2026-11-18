package com.creed.resource.lbhealth.downstream;

import io.github.resilience4j.bulkhead.BulkheadFullException;
import io.github.resilience4j.circuitbreaker.CallNotPermittedException;
import org.springframework.http.HttpStatus;
import org.springframework.web.client.HttpClientErrorException;

import java.util.function.Predicate;

/**
 * {@code resilience4j.circuitbreaker.configs.default.record-failure-predicate}: which exceptions of a downstream call
 * say "this cluster is unhealthy".
 *
 * <p><b>{@code false} does not mean "ignore" - it means SUCCESS.</b> resilience4j 2.3.0
 * ({@code CircuitBreakerStateMachine.handleThrowable}) asks {@code ignoreExceptions} first (not counted at all), then
 * this predicate: {@code true} = failure, {@code false} = recorded as a success. So everything answered {@code false}
 * here must be an outcome that really proves the cluster is up.
 *
 * <ul>
 *   <li><b>failure</b> - anything that is not on the list below, and in particular: I/O errors
 *       ({@code ResourceAccessException}: refused, reset, read timeout), every 5xx, {@code 408} and {@code 429} (the
 *       server is too busy or too slow to answer), "No instances available" ({@code IllegalStateException} from the
 *       load balancer: the cluster has no healthy instance). Unknown exceptions count, like resilience4j's own
 *       default ({@code recordExceptions} empty = every exception).</li>
 *   <li><b>success</b> - a 4xx other than 408 / 429: the cluster answered, the request was wrong. Counting it as a
 *       failure would let one caller's bad requests open the breaker for everybody.</li>
 *   <li><b>never a failure</b> - {@link CallNotPermittedException} (another breaker's rejection) and
 *       {@link BulkheadFullException} (our own concurrency cap, nothing was sent). In {@link DownstreamCaller} the
 *       bulkhead sits OUTSIDE the breaker, so a full bulkhead never reaches this predicate; the branch is the safety
 *       net for the other nesting (resilience4j's annotation order), where {@code false} would record it as a
 *       SUCCESS - there it has to be in {@code ignore-exceptions} instead, see docs §4.4.</li>
 * </ul>
 *
 * <p>Instantiated by the resilience4j binding through its no-arg constructor: no Spring injection, keep it stateless.
 */
public class DownstreamCircuitBreakerFailurePredicate implements Predicate<Throwable> {

    @Override
    public boolean test(Throwable throwable) {
        if (throwable instanceof CallNotPermittedException || throwable instanceof BulkheadFullException) {
            return false;
        }
        if (throwable instanceof HttpClientErrorException clientError) {
            HttpStatus status = HttpStatus.resolve(clientError.getStatusCode().value());
            return status == HttpStatus.REQUEST_TIMEOUT || status == HttpStatus.TOO_MANY_REQUESTS;
        }
        return true;
    }
}
