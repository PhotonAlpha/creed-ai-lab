package com.creed.resource.envmatrix.service;

import com.creed.resource.envmatrix.api.dto.HealthState;
import com.creed.resource.envmatrix.domain.EnvEndpoint;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Health of the mapped endpoints.
 *
 * <p>Two modes, selected by {@code env-matrix.health.mode}:
 * <ul>
 *   <li><b>{@code mock}</b> (default) — the state is a pure function of {@code host:port} and a
 *       rotatable seed. No network traffic at all. This is the mode the requirement outline asks for
 *       ("health check can mock the state on the backend first"): the matrix in this database
 *       describes environments this process generally cannot reach, so a real probe would report a
 *       uniform wall of {@code DOWN} and tell the UI nothing.</li>
 *   <li><b>{@code real}</b> — a plain TCP connect to {@code ip:port} within
 *       {@code env-matrix.health.timeout-ms}. Reachable ⇒ {@code UP}, refused/timed out ⇒
 *       {@code DOWN}. No TLS handshake and no HTTP request, so it says nothing about whether the
 *       service behind the port is healthy — only that something is listening.</li>
 * </ul>
 *
 * <p>Mock states are <em>stable</em> across calls by design: a state that re-rolled on every render
 * would make the matrix flicker and be useless to read. {@link #rotateMockSeed()} exists so the UI's
 * "re-check" button produces a visible, deterministic change.
 */
@Service
@Slf4j
public class HealthProbeService {

    /** Mocked distribution, in percent: [0,80) UP, [80,92) DEGRADED, [92,100) DOWN. */
    private static final int MOCK_UP_THRESHOLD = 80;
    private static final int MOCK_DEGRADED_THRESHOLD = 92;

    private final String mode;
    private final int timeoutMs;
    private final AtomicLong mockSeed;

    public HealthProbeService(
            @Value("${env-matrix.health.mode:mock}") String mode,
            @Value("${env-matrix.health.timeout-ms:800}") int timeoutMs,
            @Value("${env-matrix.health.mock-seed:0}") long mockSeed) {
        this.mode = mode;
        this.timeoutMs = timeoutMs;
        this.mockSeed = new AtomicLong(mockSeed);
        log.info("health probe mode={} timeoutMs={} mockSeed={}", mode, timeoutMs, mockSeed);
    }

    public boolean isMocked() {
        return !"real".equalsIgnoreCase(mode);
    }

    public String mode() {
        return isMocked() ? "mock" : "real";
    }

    /** Re-rolls every mocked state. Returns the new seed so the client can see it changed. */
    public long rotateMockSeed() {
        long next = mockSeed.incrementAndGet();
        log.info("mock health seed rotated to {}", next);
        return next;
    }

    public long mockSeed() {
        return mockSeed.get();
    }

    public Map<Long, HealthState> probe(Collection<EnvEndpoint> endpoints) {
        Map<Long, HealthState> states = new LinkedHashMap<>();
        boolean mocked = isMocked();
        for (EnvEndpoint endpoint : endpoints) {
            states.put(endpoint.getId(), mocked ? mockState(endpoint) : realState(endpoint));
        }
        return states;
    }

    /**
     * Deterministic pseudo-state. Uses the address (not the id) so that two endpoints mapped to the
     * same {@code host:port} — i.e. a conflict — report the same state, which is what a real probe
     * would do and makes conflicts look coherent in the UI.
     */
    private HealthState mockState(EnvEndpoint endpoint) {
        int bucket = Math.floorMod(Long.hashCode(mockSeed.get() * 31L + endpoint.hostPort().hashCode()), 100);
        if (bucket < MOCK_UP_THRESHOLD) {
            return HealthState.UP;
        }
        return bucket < MOCK_DEGRADED_THRESHOLD ? HealthState.DEGRADED : HealthState.DOWN;
    }

    private HealthState realState(EnvEndpoint endpoint) {
        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress(endpoint.getIp(), endpoint.getPort()), timeoutMs);
            return HealthState.UP;
        } catch (IOException e) {
            log.debug("probe failed for {} ({}): {}", endpoint.hostPort(), endpoint.ipPort(), e.getMessage());
            return HealthState.DOWN;
        } catch (RuntimeException e) {
            // Malformed address, unresolvable placeholder host, etc. — not reachable, but also not
            // evidence that the endpoint is down.
            log.debug("probe could not run for {}: {}", endpoint.ipPort(), e.getMessage());
            return HealthState.UNKNOWN;
        }
    }
}
