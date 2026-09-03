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

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.same;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link PoolingHttpClientConnectionManagerAuditExecHandler}: the handler is a thin
 * timing/logging wrapper around the exec chain, so the assertions check the two observable outcomes —
 * the response passes through unchanged on success and every failure kind is rethrown as-is — and
 * that each log line carries the resolved instance, method, URI, and pool occupancy pulled from the
 * shared {@link PoolingHttpClientConnectionManager}.
 */
@ExtendWith(MockitoExtension.class)
class PoolingHttpClientConnectionManagerAuditExecHandlerTest {

    private static final HttpHost TARGET = new HttpHost("https", "10.0.0.5", 18081);
    private static final HttpRoute ROUTE = new HttpRoute(TARGET);

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
        handler = new PoolingHttpClientConnectionManagerAuditExecHandler(pool);
        // The handler only reads `scope.route`; the other Scope fields are unused by the code
        // under test but the constructor rejects nulls for originalRequest/execRuntime/context.
        scope = new ExecChain.Scope("ex-1", ROUTE, request, execRuntime, HttpClientContext.create());

        when(pool.getStats(ROUTE)).thenReturn(new PoolStats(2, 1, 3, 10));
        when(pool.getTotalStats()).thenReturn(new PoolStats(5, 4, 6, 20));

        Logger logger = (Logger) LoggerFactory.getLogger(PoolingHttpClientConnectionManagerAuditExecHandler.class);
        appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);
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
        ClassicHttpResponse response = org.mockito.Mockito.mock(ClassicHttpResponse.class);
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
        RuntimeException boom = new IllegalStateException("pool exhausted");
        when(chain.proceed(same(request), same(scope))).thenThrow(boom);

        assertThatThrownBy(() -> handler.execute(request, scope, chain)).isSameAs(boom);

        ILoggingEvent event = singleEvent();
        assertThat(event.getLevel()).isEqualTo(Level.WARN);
        assertThat(event.getFormattedMessage())
                .contains("FAILED")
                .contains("pool exhausted");
    }
}
