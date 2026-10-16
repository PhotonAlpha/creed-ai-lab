package com.creed.simple.lb;

import com.creed.metrics.ConnectionPoolMetrics;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.binder.MeterBinder;
import org.apache.hc.client5.http.ConnectionKeepAliveStrategy;
import org.apache.hc.client5.http.impl.IdleConnectionEvictor;
import org.apache.hc.client5.http.impl.io.PoolingHttpClientConnectionManager;
import org.apache.hc.core5.util.Timeout;
import org.springframework.http.client.BufferingClientHttpRequestFactory;
import org.springframework.http.client.ClientHttpRequestFactory;

import java.io.Closeable;

/**
 * One self-contained, self-managing HTTP client "stack": an mTLS {@link PoolingHttpClientConnectionManager}
 * plus the {@link BufferingClientHttpRequestFactory buffering request factory} built on it, its idle-connection
 * evictor, and its pool meter binding — the plumbing beans that every downstream client used to declare separately (with
 * hand-written {@code @Qualifier} cross-wiring, the main copy-paste hazard).
 *
 * <p>Registered as a single {@code @Bean(destroyMethod = "close")}:
 * <ul>
 *   <li>implementing {@link Closeable} lets Spring stop the evictor and close the underlying pool on
 *       shutdown;</li>
 *   <li>implementing {@link MeterBinder} lets Spring Boot's metrics auto-configuration bind the pool
 *       gauges automatically — no separate {@code MeterBinder} bean, no qualifier to mis-type.</li>
 * </ul>
 *
 * <p>Build one via {@link RestClientSuppliers#pool}.
 */
public final class ManagedHttpClientPool implements MeterBinder, Closeable {

    private final PoolingHttpClientConnectionManager connectionManager;
    private final ClientHttpRequestFactory requestFactory;
    private final ConnectionKeepAliveStrategy keepAliveStrategy;
    private final IdleConnectionEvictor evictor;
    private final String metricsName;

    ManagedHttpClientPool(PoolingHttpClientConnectionManager connectionManager,
                          ClientHttpRequestFactory requestFactory,
                          ConnectionKeepAliveStrategy keepAliveStrategy,
                          IdleConnectionEvictor evictor,
                          String metricsName) {
        this.connectionManager = connectionManager;
        this.requestFactory = requestFactory;
        this.keepAliveStrategy = keepAliveStrategy;
        this.evictor = evictor;
        this.metricsName = metricsName;
    }

    /** The shared, mTLS-aware pool. Callers that need the raw manager (e.g. a bespoke HttpClient). */
    public PoolingHttpClientConnectionManager connectionManager() {
        return connectionManager;
    }

    /**
     * The pool's keep-alive cap. A bespoke {@code HttpClientBuilder} on {@link #connectionManager()} must
     * set it too — keep-alive is a client setting, so a client without it reverts to the 3-minute default.
     */
    public ConnectionKeepAliveStrategy keepAliveStrategy() {
        return keepAliveStrategy;
    }

    /** The buffering {@link ClientHttpRequestFactory} to hand to a {@code RestClient}/{@code RestTemplate}. */
    public ClientHttpRequestFactory requestFactory() {
        return requestFactory;
    }

    /** Package-private for tests: whether the idle-connection sweep is still running. */
    boolean evictorRunning() {
        return evictor.isRunning();
    }

    @Override
    public void bindTo(MeterRegistry registry) {
        new ConnectionPoolMetrics(connectionManager, metricsName).bindTo(registry);
    }

    @Override
    public void close() {
        evictor.shutdown();
        try {
            evictor.awaitTermination(Timeout.ofSeconds(1));
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
        }
        connectionManager.close();
    }
}
