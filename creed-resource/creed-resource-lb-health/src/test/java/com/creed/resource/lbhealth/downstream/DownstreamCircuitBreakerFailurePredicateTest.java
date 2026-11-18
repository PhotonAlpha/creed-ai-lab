package com.creed.resource.lbhealth.downstream;

import io.github.resilience4j.bulkhead.Bulkhead;
import io.github.resilience4j.bulkhead.BulkheadConfig;
import io.github.resilience4j.bulkhead.BulkheadFullException;
import io.github.resilience4j.circuitbreaker.CallNotPermittedException;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.HttpServerErrorException;
import org.springframework.web.client.ResourceAccessException;

import java.net.ConnectException;
import java.net.SocketTimeoutException;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

class DownstreamCircuitBreakerFailurePredicateTest {

    private final DownstreamCircuitBreakerFailurePredicate predicate = new DownstreamCircuitBreakerFailurePredicate();

    private static HttpClientErrorException client(HttpStatus status) {
        return HttpClientErrorException.create(status, status.getReasonPhrase(), HttpHeaders.EMPTY, new byte[0], null);
    }

    @Test
    void theClusterBeingUnreachableOrBrokenIsAFailure() {
        assertThat(predicate.test(new ResourceAccessException("refused", new ConnectException("Connection refused"))))
                .isTrue();
        assertThat(predicate.test(new ResourceAccessException("timeout", new SocketTimeoutException("Read timed out"))))
                .isTrue();
        assertThat(predicate.test(HttpServerErrorException.create(HttpStatus.SERVICE_UNAVAILABLE, "", HttpHeaders.EMPTY,
                new byte[0], null))).isTrue();
        assertThat(predicate.test(new IllegalStateException("No instances available for lb-health-target"))).isTrue();
        assertThat(predicate.test(new RuntimeException("unknown"))).as("unknown: counted, like resilience4j's default")
                .isTrue();
    }

    @ParameterizedTest
    @EnumSource(value = HttpStatus.class, names = {"BAD_REQUEST", "UNAUTHORIZED", "FORBIDDEN", "NOT_FOUND", "CONFLICT"})
    void aClientErrorIsTheCallersFault(HttpStatus status) {
        assertThat(predicate.test(client(status))).isFalse();
    }

    @ParameterizedTest
    @EnumSource(value = HttpStatus.class, names = {"REQUEST_TIMEOUT", "TOO_MANY_REQUESTS"})
    void tooBusyOrTooSlowIsAFailureEvenAs4xx(HttpStatus status) {
        assertThat(predicate.test(client(status))).isTrue();
    }

    @Test
    void ourOwnRejectionsAreNeverAFailure() {
        assertThat(predicate.test(BulkheadFullException.createBulkheadFullException(
                Bulkhead.of("b", BulkheadConfig.ofDefaults())))).isFalse();
        assertThat(predicate.test(CallNotPermittedException.createCallNotPermittedException(
                CircuitBreaker.ofDefaults("c")))).isFalse();
    }

    /**
     * The trap the predicate's javadoc warns about, run on a real breaker: {@code false} is not "ignored", it is a
     * SUCCESS - it lands in the window and dilutes the failure rate. Only {@code ignoreExceptions} keeps a call out.
     */
    @Test
    void falseIsRecordedAsASuccessOnlyIgnoreKeepsACallOutOfTheWindow() {
        CircuitBreaker recorded = CircuitBreaker.of("recorded", CircuitBreakerConfig.custom()
                .recordException(predicate).build());
        recorded.onError(0, TimeUnit.NANOSECONDS, client(HttpStatus.NOT_FOUND));
        assertThat(recorded.getMetrics().getNumberOfBufferedCalls()).isEqualTo(1);
        assertThat(recorded.getMetrics().getNumberOfSuccessfulCalls()).isEqualTo(1);

        CircuitBreaker ignoring = CircuitBreaker.of("ignoring", CircuitBreakerConfig.custom()
                .recordException(predicate).ignoreExceptions(HttpClientErrorException.class).build());
        ignoring.onError(0, TimeUnit.NANOSECONDS, client(HttpStatus.NOT_FOUND));
        assertThat(ignoring.getMetrics().getNumberOfBufferedCalls()).isZero();
    }
}
