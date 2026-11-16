package com.creed.resource.lbhealth.upstream;

import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;

/** What each embedded target instance (one per local port) answers on its health endpoint. */
@Component
public class TargetInstanceStates {

    /**
     * @param up    {@code false} → the health endpoint answers 503
     * @param delay how long the health endpoint sleeps before answering (to push a probe past its read timeout)
     */
    public record State(boolean up, Duration delay) {
        static final State HEALTHY = new State(true, Duration.ZERO);
    }

    private final Map<Integer, State> states = new ConcurrentHashMap<>();

    public State get(int port) {
        return states.getOrDefault(port, State.HEALTHY);
    }

    public void set(int port, State state) {
        states.put(port, state);
    }

    public void reset() {
        states.clear();
    }

    public Map<Integer, State> overrides() {
        return new TreeMap<>(states);
    }
}
