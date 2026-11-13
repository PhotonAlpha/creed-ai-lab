package com.creed.resource.veracode.service;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.FutureTask;

/**
 * Jira calls that do not depend on each other, side by side on virtual threads: an analysis' wall time is then its
 * slowest search, not the sum of them. Each caller bounds its own fan-out; the Jira pool ({@code max-connections})
 * bounds them all - a call past it waits for a connection, up to the read timeout.
 *
 * <p>A failure is thrown as the call threw it (an {@link UpstreamException} stays one), so callers handle it as they
 * did when the calls ran in a row. MDC is not carried over: the calls themselves do not log.
 */
final class Concurrently {

    private Concurrently() {
    }

    /** {@code call} started on its own virtual thread; {@link #join} it. */
    static <T> Future<T> start(String name, Callable<T> call) {
        FutureTask<T> task = new FutureTask<>(call);
        Thread.ofVirtual().name(name).start(task);
        return task;
    }

    static <T> T join(Future<T> future) {
        try {
            return future.get();
        } catch (ExecutionException e) {
            throw rethrow(e.getCause());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("interrupted waiting for Jira", e);
        }
    }

    /**
     * Every call, at most {@code parallelism} at a time; the results in the calls' order. One call runs on the
     * caller's thread. The first failure (in the calls' order) is thrown and the calls not yet finished are cancelled.
     */
    static <T> List<T> all(String name, int parallelism, List<Callable<T>> calls) {
        if (calls.size() <= 1) {
            List<T> one = new ArrayList<>();
            for (Callable<T> call : calls) {
                one.add(callDirectly(call));
            }
            return one;
        }
        ExecutorService executor = Executors.newFixedThreadPool(Math.min(parallelism, calls.size()),
                Thread.ofVirtual().name(name + "-", 0).factory());
        try {
            List<Future<T>> futures = calls.stream().map(executor::submit).toList();
            List<T> results = new ArrayList<>(futures.size());
            for (Future<T> future : futures) {
                results.add(join(future));
            }
            return results;
        } finally {
            executor.shutdownNow();
        }
    }

    private static <T> T callDirectly(Callable<T> call) {
        try {
            return call.call();
        } catch (Exception e) {
            throw rethrow(e);
        }
    }

    private static RuntimeException rethrow(Throwable cause) {
        if (cause instanceof RuntimeException e) {
            return e;
        }
        if (cause instanceof Error e) {
            throw e;
        }
        return new IllegalStateException(cause);
    }
}
