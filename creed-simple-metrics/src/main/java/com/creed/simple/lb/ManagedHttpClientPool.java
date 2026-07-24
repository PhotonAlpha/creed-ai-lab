package com.creed.simple.lb;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.binder.MeterBinder;
import io.micrometer.core.instrument.binder.httpcomponents.hc5.PoolingHttpClientConnectionManagerMetricsBinder;
import org.apache.hc.client5.http.impl.io.PoolingHttpClientConnectionManager;
import org.springframework.http.client.BufferingClientHttpRequestFactory;
import org.springframework.http.client.ClientHttpRequestFactory;

import java.io.Closeable;

/**
 * One self-contained, self-managing HTTP client "stack": an mTLS {@link PoolingHttpClientConnectionManager}
 * plus the {@link BufferingClientHttpRequestFactory buffering request factory} built on it, and its pool
 * meter binding — the three plumbing beans that every downstream client used to declare separately (with
 * hand-written {@code @Qualifier} cross-wiring, the main copy-paste hazard).
 *
 * <p>Registered as a single {@code @Bean(destroyMethod = "close")}:
 * <ul>
 *   <li>implementing {@link Closeable} lets Spring close the underlying pool on shutdown;</li>
 *   <li>implementing {@link MeterBinder} lets Spring Boot's metrics auto-configuration bind the pool
 *       gauges automatically — no separate {@code MeterBinder} bean, no qualifier to mis-type.</li>
 * </ul>
 *
 * <p>Build one via {@link RestClientSuppliers#pool}.
 */
public final class ManagedHttpClientPool implements MeterBinder, Closeable {

    private final PoolingHttpClientConnectionManager connectionManager;
    private final ClientHttpRequestFactory requestFactory;
    private final String metricsName;

    ManagedHttpClientPool(PoolingHttpClientConnectionManager connectionManager,
                          ClientHttpRequestFactory requestFactory,
                          String metricsName) {
        this.connectionManager = connectionManager;
        this.requestFactory = requestFactory;
        this.metricsName = metricsName;
    }

    /** The shared, mTLS-aware pool. Callers that need the raw manager (e.g. a bespoke HttpClient). */
    public PoolingHttpClientConnectionManager connectionManager() {
        return connectionManager;
    }

    /** The buffering {@link ClientHttpRequestFactory} to hand to a {@code RestClient}/{@code RestTemplate}. */
    public ClientHttpRequestFactory requestFactory() {
        return requestFactory;
    }

    @Override
    public void bindTo(MeterRegistry registry) {
        new PoolingHttpClientConnectionManagerMetricsBinder(connectionManager, metricsName).bindTo(registry);
    }

    @Override
    public void close() {
        connectionManager.close();
    }
}
