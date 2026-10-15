package com.creed.simple.config;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import org.apache.hc.client5.http.HttpRoute;
import org.apache.hc.client5.http.classic.ExecChain;
import org.apache.hc.client5.http.classic.ExecRuntime;
import org.apache.hc.client5.http.impl.io.PoolingHttpClientConnectionManager;
import org.apache.hc.client5.http.protocol.HttpClientContext;
import org.apache.hc.core5.http.ClassicHttpRequest;
import org.apache.hc.core5.http.ClassicHttpResponse;
import org.apache.hc.core5.http.HttpException;
import org.apache.hc.core5.http.HttpHost;
import org.apache.hc.core5.pool.PoolStats;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.time.Duration;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.same;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link PoolingHttpClientConnectionManagerAuditExecHandler}: the handler is a thin
 * timing/logging wrapper around the exec chain, so the assertions check the two observable outcomes —
 * the response passes through unchanged on success and every failure kind is rethrown as-is — and
 * that each log line carries the resolved instance, method, URI, and pool occupancy pulled from the
 * shared {@link PoolingHttpClientConnectionManager} — and when it is: the pool suffix (two pool-lock
 * acquisitions) is sampled one-in-N, forced for slow calls and failures, while the instance line is not
 * sampled at all.
 */
@ExtendWith(MockitoExtension.class)
class PoolingHttpClientConnectionManagerAuditExecHandlerTest {

    private static final HttpHost TARGET = new HttpHost("https", "10.0.0.5", 18081);
    private static final HttpRoute ROUTE = new HttpRoute(TARGET);
    /** Never reached by a mocked call, so "slow" can only be forced explicitly. */
    private static final Duration NEVER_SLOW = Duration.ofHours(1);

    @Mock
    private PoolingHttpClientConnectionManager pool;
    @Mock
    private ClassicHttpRequest request;
    @Mock
    private ExecChain chain;
    @Mock
    private ExecRuntime execRuntime;

    private PoolingHttpClientConnectionManagerAuditExecHandler handler;
    private ExecChain.Scope scope;
    private ListAppender<ILoggingEvent> appender;

    @BeforeEach
    void setUp() {
        // every=1 → every call carries the pool suffix (the pre-sampling behaviour the first tests pin)
        handler = new PoolingHttpClientConnectionManagerAuditExecHandler(pool, 1, NEVER_SLOW);
        // The handler only reads `scope.route`; the other Scope fields are unused by the code
        // under test but the constructor rejects nulls for originalRequest/execRuntime/context.
        scope = new ExecChain.Scope("ex-1", ROUTE, request, execRuntime, HttpClientContext.create());

        Logger logger = (Logger) LoggerFactory.getLogger(PoolingHttpClientConnectionManagerAuditExecHandler.class);
        appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);
    }

    private void stubPoolStats() {
        when(pool.getStats(ROUTE)).thenReturn(new PoolStats(2, 1, 3, 10));
        when(pool.getTotalStats()).thenReturn(new PoolStats(5, 4, 6, 20));
    }

    private void stubSuccess() throws IOException, HttpException {
        ClassicHttpResponse response = mock(ClassicHttpResponse.class);
        when(response.getCode()).thenReturn(200);
        when(chain.proceed(same(request), same(scope))).thenReturn(response);
    }

    private List<String> messages() {
        return appender.list.stream().map(ILoggingEvent::getFormattedMessage).toList();
    }

    private void stubRequest() {
        when(request.getMethod()).thenReturn("GET");
        when(request.getRequestUri()).thenReturn("/api/catalog/items");
    }

    private ILoggingEvent singleEvent() {
        assertThat(appender.list).hasSize(1);
        return appender.list.get(0);
    }

    @Test
    void returnsResponseAndLogsInstanceUriStatusAndPoolStatsOnSuccess() throws IOException, HttpException {
        stubRequest();
        stubPoolStats();
        ClassicHttpResponse response = mock(ClassicHttpResponse.class);
        when(response.getCode()).thenReturn(200);
        when(chain.proceed(same(request), same(scope))).thenReturn(response);

        ClassicHttpResponse result = handler.execute(request, scope, chain);

        assertThat(result).isSameAs(response);
        verify(chain).proceed(same(request), same(scope));
        verify(pool).getStats(ROUTE);
        verify(pool).getTotalStats();

        ILoggingEvent event = singleEvent();
        assertThat(event.getLevel()).isEqualTo(Level.INFO);
        assertThat(event.getFormattedMessage())
                .contains("PoolingHttpClientConnection resolved")
                .contains("instance=10.0.0.5:18081")
                .contains("GET")
                .contains("/api/catalog/items")
                .contains("status=200")
                .contains("pool[route 2/10 avail=3 pending=1")
                .contains("total 5/20 avail=6 pending=4");
    }

    @Test
    void rethrowsIoExceptionUnchangedAndLogsFailureAtWarn() throws IOException, HttpException {
        stubRequest();
        stubPoolStats();
        IOException boom = new IOException("connection reset");
        when(chain.proceed(same(request), same(scope))).thenThrow(boom);

        assertThatThrownBy(() -> handler.execute(request, scope, chain)).isSameAs(boom);

        ILoggingEvent event = singleEvent();
        assertThat(event.getLevel()).isEqualTo(Level.WARN);
        assertThat(event.getFormattedMessage())
                .contains("PoolingHttpClientConnection resolved")
                .contains("instance=10.0.0.5:18081")
                .contains("GET")
                .contains("/api/catalog/items")
                .contains("FAILED")
                .contains("connection reset")
                .contains("pool[route 2/10");
    }

    @Test
    void rethrowsHttpExceptionUnchanged() throws IOException, HttpException {
        stubRequest();
        stubPoolStats();
        HttpException boom = new HttpException("protocol violation");
        when(chain.proceed(same(request), same(scope))).thenThrow(boom);

        assertThatThrownBy(() -> handler.execute(request, scope, chain)).isSameAs(boom);

        ILoggingEvent event = singleEvent();
        assertThat(event.getLevel()).isEqualTo(Level.WARN);
        assertThat(event.getFormattedMessage())
                .contains("FAILED")
                .contains("protocol violation");
    }

    @Test
    void rethrowsRuntimeExceptionUnchanged() throws IOException, HttpException {
        stubRequest();
        stubPoolStats();
        RuntimeException boom = new IllegalStateException("pool exhausted");
        when(chain.proceed(same(request), same(scope))).thenThrow(boom);

        assertThatThrownBy(() -> handler.execute(request, scope, chain)).isSameAs(boom);

        ILoggingEvent event = singleEvent();
        assertThat(event.getLevel()).isEqualTo(Level.WARN);
        assertThat(event.getFormattedMessage())
                .contains("FAILED")
                .contains("pool exhausted");
    }

    @Test
    void samplesPoolStatsOneInNWhileTheInstanceLineIsLoggedEveryTime() throws IOException, HttpException {
        handler = new PoolingHttpClientConnectionManagerAuditExecHandler(pool, 3, NEVER_SLOW);
        stubRequest();
        stubPoolStats();
        stubSuccess();

        for (int i = 0; i < 4; i++) {
            handler.execute(request, scope, chain);
        }

        // calls 1 and 4 sampled (counter 0 and 3); 2 and 3 skip the lock entirely
        List<String> lines = messages();
        assertThat(lines).hasSize(4).allMatch(m -> m.contains("instance=10.0.0.5:18081") && m.contains("status=200"));
        assertThat(lines.get(0)).contains("pool[route 2/10");
        assertThat(lines.get(1)).doesNotContain("pool[");
        assertThat(lines.get(2)).doesNotContain("pool[");
        assertThat(lines.get(3)).contains("pool[route 2/10");
        verify(pool, times(2)).getStats(ROUTE);
        verify(pool, times(2)).getTotalStats();
    }

    @Test
    void zeroDisablesSamplingAndNeverTouchesThePoolForFastSuccesses() throws IOException, HttpException {
        handler = new PoolingHttpClientConnectionManagerAuditExecHandler(pool, 0, NEVER_SLOW);
        stubRequest();
        stubSuccess();

        handler.execute(request, scope, chain);

        assertThat(singleEvent().getFormattedMessage())
                .contains("instance=10.0.0.5:18081")
                .endsWith("ms");
        verifyNoInteractions(pool);
    }

    @Test
    void slowCallsAlwaysCarryPoolStats() throws IOException, HttpException {
        // threshold 0 makes every call "slow" without sleeping in the test
        handler = new PoolingHttpClientConnectionManagerAuditExecHandler(pool, 0, Duration.ZERO);
        stubRequest();
        stubPoolStats();
        stubSuccess();

        handler.execute(request, scope, chain);

        assertThat(singleEvent().getFormattedMessage()).contains("pool[route 2/10 avail=3 pending=1");
    }

    @Test
    void failuresAlwaysCarryPoolStatsEvenWithSamplingOff() throws IOException, HttpException {
        handler = new PoolingHttpClientConnectionManagerAuditExecHandler(pool, 0, NEVER_SLOW);
        stubRequest();
        stubPoolStats();
        when(chain.proceed(same(request), same(scope))).thenThrow(new IOException("connection reset"));

        assertThatThrownBy(() -> handler.execute(request, scope, chain)).isInstanceOf(IOException.class);

        assertThat(singleEvent().getFormattedMessage())
                .contains("FAILED")
                .contains("pool[route 2/10");
    }

    @Test
    void rejectsANegativeSampleInterval() {
        assertThatIllegalArgumentException()
                .isThrownBy(() -> new PoolingHttpClientConnectionManagerAuditExecHandler(pool, -1, NEVER_SLOW));
    }
}
