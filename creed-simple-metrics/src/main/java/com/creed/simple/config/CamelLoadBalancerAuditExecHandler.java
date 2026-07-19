package com.creed.simple.config;

import org.apache.hc.client5.http.classic.ExecChain;
import org.apache.hc.client5.http.classic.ExecChainHandler;
import org.apache.hc.core5.http.ClassicHttpRequest;
import org.apache.hc.core5.http.ClassicHttpResponse;
import org.apache.hc.core5.http.HttpException;
import org.apache.hc.core5.http.HttpHost;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;

/**
 * hc5 counterpart of {@link com.creed.simple.web.LoadBalancerAuditInterceptor}: one line per network
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
 */
public class CamelLoadBalancerAuditExecHandler implements ExecChainHandler {

    private static final Logger log = LoggerFactory.getLogger(CamelLoadBalancerAuditExecHandler.class);

    @Override
    public ClassicHttpResponse execute(ClassicHttpRequest request, ExecChain.Scope scope, ExecChain chain)
            throws IOException, HttpException {
        HttpHost target = scope.route.getTargetHost();
        long startNanos = System.nanoTime();
        try {
            ClassicHttpResponse response = chain.proceed(request, scope);
            long ms = (System.nanoTime() - startNanos) / 1_000_000;
            log.info("LB resolved -> instance={}:{} {} {} status={} in {}ms",
                    target.getHostName(), target.getPort(), request.getMethod(),
                    request.getRequestUri(), response.getCode(), ms);
            return response;
        } catch (IOException | HttpException | RuntimeException ex) {
            long ms = (System.nanoTime() - startNanos) / 1_000_000;
            log.warn("LB resolved -> instance={}:{} {} {} FAILED in {}ms: {}",
                    target.getHostName(), target.getPort(), request.getMethod(),
                    request.getRequestUri(), ms, ex.toString());
            throw ex;
        }
    }
}
