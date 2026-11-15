package com.creed.gatewayproxy.service.splunk;

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.time.Clock;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Function;

import lombok.extern.slf4j.Slf4j;

import com.creed.gatewayproxy.config.SplunkProperties;

/**
 * The host name of a caller's IP, for the page and the audit.
 *
 * <p>{@link InetAddress#getCanonicalHostName()} does the PTR lookup <i>and</i> resolves the name it gets
 * back, returning the IP literal unless that name leads to the same address — so a PTR record somebody
 * points at a name they do not own is not shown. It has no timeout of its own: the request waits at most
 * {@code timeout}, while the lookup finishes on its own virtual thread and fills the cache. Nothing here
 * decides anything; a name that is missing or late is shown as missing.
 */
@Slf4j
public class ClientHostResolver implements AutoCloseable {

    static final int MAX_ENTRIES = 1000;

    private record Entry(CompletableFuture<String> host, Instant at) {
    }

    private final SplunkProperties.ClientHost config;
    private final Function<String, String> lookup;
    private final Clock clock;
    private final ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();
    private final Map<String, Entry> cache = new ConcurrentHashMap<>();

    public ClientHostResolver(SplunkProperties.ClientHost config, Clock clock) {
        this(config, ClientHostResolver::reverse, clock);
    }

    /** {@code lookup}: IP → host name or null; the tests' stand-in for DNS. */
    ClientHostResolver(SplunkProperties.ClientHost config, Function<String, String> lookup, Clock clock) {
        this.config = config;
        this.lookup = lookup;
        this.clock = clock;
    }

    /** The forward-confirmed name of {@code ip}, or null: disabled, no name, not confirmed, or not answered in time. */
    public String resolve(String ip) {
        if (!config.enabled() || ip == null || ip.isBlank()) return null;
        Instant now = clock.instant();
        Entry entry = cache.compute(ip, (k, old) -> old != null && !old.at().plus(config.cacheTtl()).isBefore(now)
                ? old : new Entry(CompletableFuture.supplyAsync(() -> lookup.apply(k), executor), now));
        if (cache.size() > MAX_ENTRIES) evict(now);
        try {
            return entry.host().get(config.timeout().toMillis(), TimeUnit.MILLISECONDS);
        } catch (TimeoutException e) {
            log.debug("reverse lookup of {} not answered within {}", ip, config.timeout());
            return null;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return null;
        } catch (ExecutionException e) {
            log.debug("reverse lookup of {} failed: {}", ip, e.getCause().toString());
            return null;
        }
    }

    private void evict(Instant now) {
        cache.values().removeIf(e -> e.at().plus(config.cacheTtl()).isBefore(now));
        // Still full: a flood of distinct IPs. Forgetting them all costs one lookup each, nothing more.
        if (cache.size() > MAX_ENTRIES) cache.clear();
    }

    static String reverse(String ip) {
        try {
            InetAddress address = InetAddress.getByName(ip); // a literal: no lookup here
            String name = address.getCanonicalHostName();
            return name == null || name.equals(address.getHostAddress()) ? null : name;
        } catch (UnknownHostException e) {
            return null;
        }
    }

    @Override
    public void close() {
        executor.shutdownNow();
    }
}
