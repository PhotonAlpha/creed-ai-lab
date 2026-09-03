package com.creed.simple.config;

import com.creed.simple.lb.LoadBalancerAuditInterceptor;
import lombok.extern.slf4j.Slf4j;
import org.apache.hc.client5.http.classic.ExecChain;
import org.apache.hc.client5.http.classic.ExecChainHandler;
import org.apache.hc.client5.http.impl.io.PoolingHttpClientConnectionManager;
import org.apache.hc.core5.http.ClassicHttpRequest;
import org.apache.hc.core5.http.ClassicHttpResponse;
import org.apache.hc.core5.http.HttpException;
import org.apache.hc.core5.http.HttpHost;
import org.apache.hc.core5.pool.PoolStats;

import java.io.IOException;

/**
 * hc5 counterpart of {@link LoadBalancerAuditInterceptor}: one line per network
 * attempt with the <em>concrete instance</em> the {@link com.creed.simple.lb.LoadBalancerRoutePlanner}
 * (or the {@code @LoadBalanced} interceptor) resolved, plus latency and status.
 *
 * <p>HttpClient consults the route planner <em>before</em> the exec chain runs, so
 * {@code scope.route.getTargetHost()} is already the chosen instance's {@code host:port} — the
 * request's own authority may still carry the logical service-id. Installed via
 * {@code addExecInterceptorLast} (innermost, inside the retry exec), so it times each actual attempt.
 *
 * <p>The FULL request/response audit (headers, cookies, bodies, obfuscation) that used to live here
 * moved to Zalando Logbook ({@code LogbookHttpExecHandler} on the same builders; config under the
 * {@code logbook.*} keys) — this handler only keeps the piece Logbook cannot see: the resolved route.
 *
 * <p>Each line also carries the pool occupancy for {@code scope.route} and the whole pool
 * ({@link PoolingHttpClientConnectionManager#getStats(org.apache.hc.client5.http.HttpRoute)} /
 * {@link PoolingHttpClientConnectionManager#getTotalStats()}) — the same numbers the manager's own
 * DEBUG lease/release lines show, but on one INFO line with method/URI/status/latency and the MDC
 * trace id, so the manager's DEBUG logging can stay off outside leak hunts. {@code pending} is the
 * queue of callers waiting for a connection — the saturation signal the DEBUG lines don't print.
 */
@Slf4j
public class PoolingHttpClientConnectionManagerAuditExecHandler implements ExecChainHandler {
    private final PoolingHttpClientConnectionManager pool;

    public PoolingHttpClientConnectionManagerAuditExecHandler(PoolingHttpClientConnectionManager pool) {
        this.pool = pool;
    }

    @Override
    public ClassicHttpResponse execute(ClassicHttpRequest request, ExecChain.Scope scope, ExecChain chain)
            throws IOException, HttpException {
        HttpHost target = scope.route.getTargetHost();
        long startNanos = System.nanoTime();
        try {
            ClassicHttpResponse response = chain.proceed(request, scope);
            long ms = (System.nanoTime() - startNanos) / 1_000_000;
            log.info("PoolingHttpClientConnection resolved -> instance={}:{} {} {} status={} in {}ms {}",
                    target.getHostName(), target.getPort(), request.getMethod(),
                    request.getRequestUri(), response.getCode(), ms, poolSummary(scope));
            return response;
        } catch (IOException | HttpException | RuntimeException ex) {
            long ms = (System.nanoTime() - startNanos) / 1_000_000;
            log.warn("PoolingHttpClientConnection resolved -> instance={}:{} {} {} FAILED in {}ms: {} {}",
                    target.getHostName(), target.getPort(), request.getMethod(),
                    request.getRequestUri(), ms, ex.toString(), poolSummary(scope));
            throw ex;
        }
    }

    private String poolSummary(ExecChain.Scope scope) {
        PoolStats route = pool.getStats(scope.route);
        PoolStats total = pool.getTotalStats();
        return "pool[route " + route.getLeased() + "/" + route.getMax()
                + " avail=" + route.getAvailable() + " pending=" + route.getPending()
                + "; total " + total.getLeased() + "/" + total.getMax()
                + " avail=" + total.getAvailable() + " pending=" + total.getPending() + "]";
    }
}
