package com.creed.resource.migrationrisk.service;

import com.creed.resource.migrationrisk.api.ApiErrors;
import com.creed.resource.migrationrisk.api.dto.AnalysisResponse;
import com.creed.resource.migrationrisk.config.MigrationRiskProperties;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;

/**
 * Analyses as background jobs whose progress is pushed over Server-Sent Events.
 *
 * <p>A big change set keeps RefactoringMiner busy for minutes, far past what a synchronous request survives
 * behind a proxy. A job runs on its own bounded pool ({@code migration-risk.jobs.concurrency}, which also caps
 * how many of these memory-hungry runs share the JVM) and records its events; any number of SSE subscribers —
 * including a browser reconnecting after a dropped connection — get the stage history replayed, then live events.
 *
 * <p>Events: {@code stage} (a new stage began), {@code progress} (a step inside a looping stage, throttled),
 * {@code done} (fetch the result with {@code GET /analyses/jobs/{id}}) or {@code failed} (the same
 * {@code {error, message, fields?}} body the API answers with). The result itself is not pushed: for a large
 * project it is megabytes, which belongs in one ordinary response, not in an event frame.
 */
@Service
@Slf4j
public class AnalysisJobs {

    public enum Status { QUEUED, RUNNING, DONE, FAILED, CANCELLED }

    /** One event as sent; {@code data} is serialized as JSON. */
    public record Event(String name, Object data) {
    }

    /** Payload of {@code stage} and {@code progress}. */
    public record ProgressEvent(String stage, int percent, Integer done, Integer total, String detail, long elapsedMillis) {
    }

    /** {@code GET /analyses/jobs/{id}}: everything a page needs after a reload. */
    public record JobView(String id, Status status, ProgressEvent progress, Instant startedAt, Instant finishedAt,
                          AnalysisResponse result, Map<String, Object> error) {
    }

    /** Fewer progress frames than steps: a 2000-file change set must not mean 2000 events. */
    private static final long PROGRESS_THROTTLE_MILLIS = 200;
    private static final long HEARTBEAT_SECONDS = 15;
    private static final long EMITTER_TIMEOUT_MILLIS = Duration.ofHours(2).toMillis();

    private final ThreadPoolExecutor pool;
    private final ScheduledExecutorService heartbeat;
    private final Map<String, Job> jobs = new ConcurrentHashMap<>();
    private final Duration retention;
    private final Clock clock;

    public AnalysisJobs(MigrationRiskProperties properties, Clock clock) {
        MigrationRiskProperties.Jobs config = properties.jobs();
        AtomicInteger n = new AtomicInteger();
        this.pool = new ThreadPoolExecutor(config.concurrency(), config.concurrency(), 0, TimeUnit.MILLISECONDS,
                new ArrayBlockingQueue<>(Math.max(1, config.queue())), r -> {
                    Thread t = new Thread(r, "migration-risk-job-" + n.incrementAndGet());
                    t.setDaemon(true);
                    return t;
                });
        this.heartbeat = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "migration-risk-sse-heartbeat");
            t.setDaemon(true);
            return t;
        });
        // Proxies (and Vite's) drop a silent connection; RefactoringMiner can be silent for minutes.
        heartbeat.scheduleAtFixedRate(this::ping, HEARTBEAT_SECONDS, HEARTBEAT_SECONDS, TimeUnit.SECONDS);
        this.retention = config.retention();
        this.clock = clock;
    }

    @PreDestroy
    void shutdown() {
        heartbeat.shutdownNow();
        pool.shutdownNow();
    }

    /** Queues {@code work}; it reports through the {@link Progress} it is given. */
    public String start(String label, Function<Progress, AnalysisResponse> work) {
        evictExpired();
        Job job = new Job(UUID.randomUUID().toString(), label, Instant.now(clock));
        jobs.put(job.id, job);
        try {
            job.future = pool.submit(() -> run(job, work));
        } catch (RejectedExecutionException e) {
            jobs.remove(job.id);
            throw new BusyException(pool.getActiveCount(), pool.getQueue().size());
        }
        log.info("job {} queued: {}", job.id, label);
        return job.id;
    }

    private void run(Job job, Function<Progress, AnalysisResponse> work) {
        job.status = Status.RUNNING;
        job.runningSince = System.nanoTime();
        try {
            AnalysisResponse result = work.apply(job);
            job.result = result;
            job.stage(Progress.Stage.DONE, result.counts().total() + " items");
            finish(job, Status.DONE, new Event("done", Map.of("id", job.id, "analysisId",
                    result.analysisId() == null ? "" : result.analysisId())));
        } catch (Progress.CancelledException e) {
            job.error = ApiErrors.of(e).body();
            finish(job, Status.CANCELLED, new Event("failed", job.error));
        } catch (RuntimeException | Error e) {
            job.error = ApiErrors.of(e).body();
            log.warn("job {} failed: {}", job.id, e.toString(), e instanceof RuntimeException ? null : e);
            finish(job, Status.FAILED, new Event("failed", job.error));
        }
    }

    private void finish(Job job, Status status, Event last) {
        job.finishedAt = Instant.now(clock);
        job.status = status;
        job.emit(last, true);
        log.info("job {} {} in {} ms", job.id, status, job.elapsedMillis());
    }

    /** Replays what happened so far, then streams; completes at once for a finished job. */
    public SseEmitter subscribe(String id) {
        Job job = find(id);
        SseEmitter emitter = new SseEmitter(EMITTER_TIMEOUT_MILLIS);
        job.attach(emitter);
        return emitter;
    }

    public JobView view(String id) {
        Job job = find(id);
        return new JobView(job.id, job.status, job.lastProgress, job.startedAt, job.finishedAt, job.result, job.error);
    }

    /** Stops the job at its next progress report (or before it starts); a RefactoringMiner run in flight is not interrupted. */
    public void cancel(String id) {
        Job job = find(id);
        job.cancelled = true;
        if (job.status == Status.QUEUED && job.future != null && job.future.cancel(false)) {
            job.error = ApiErrors.of(new Progress.CancelledException()).body();
            finish(job, Status.CANCELLED, new Event("failed", job.error));
        }
    }

    private Job find(String id) {
        Job job = jobs.get(id);
        if (job == null) {
            throw new ReleaseStore.NotFoundException("no analysis job " + id + " (finished jobs are kept " + retention + ")");
        }
        return job;
    }

    private void ping() {
        jobs.values().stream().filter(j -> j.finishedAt == null).forEach(Job::ping);
    }

    private void evictExpired() {
        Instant cutoff = Instant.now(clock).minus(retention);
        jobs.values().removeIf(j -> j.finishedAt != null && j.finishedAt.isBefore(cutoff));
    }

    /** One job: its state, its stage history (for replay) and its live subscribers. */
    private final class Job implements Progress {
        final String id;
        final String label;
        final Instant startedAt;
        volatile Status status = Status.QUEUED;
        volatile boolean cancelled;
        volatile Future<?> future;
        volatile long runningSince;
        volatile Instant finishedAt;
        volatile AnalysisResponse result;
        volatile Map<String, Object> error;
        volatile ProgressEvent lastProgress;
        private final List<Event> history = new ArrayList<>();
        private final List<SseEmitter> emitters = new CopyOnWriteArrayList<>();
        private long lastStepSent;

        Job(String id, String label, Instant startedAt) {
            this.id = id;
            this.label = label;
            this.startedAt = startedAt;
        }

        long elapsedMillis() {
            return runningSince == 0 ? 0 : (System.nanoTime() - runningSince) / 1_000_000;
        }

        @Override
        public void stage(Stage stage, String detail) {
            checkCancelled();
            ProgressEvent p = new ProgressEvent(stage.name(), stage.start(), null, null, detail, elapsedMillis());
            lastProgress = p;
            emit(new Event("stage", p), false);
        }

        @Override
        public void step(Stage stage, int done, int total, String detail) {
            checkCancelled();
            ProgressEvent p = new ProgressEvent(stage.name(), stage.percent(done, total), done, total, detail, elapsedMillis());
            lastProgress = p;
            long now = System.currentTimeMillis();
            if (now - lastStepSent >= PROGRESS_THROTTLE_MILLIS || done + 1 >= total) {
                lastStepSent = now;
                emit(new Event("progress", p), false);
            }
        }

        private void checkCancelled() {
            if (cancelled) {
                throw new CancelledException();
            }
        }

        /**
         * Stage and terminal events are kept for replay; progress steps are not (the last one is kept as
         * {@link #lastProgress} and replayed instead). Synchronized with {@link #attach} so a subscriber never
         * misses or doubles an event between its replay and the live stream.
         */
        synchronized void emit(Event event, boolean terminal) {
            if (!event.name().equals("progress")) {
                history.add(event);
            }
            for (SseEmitter emitter : emitters) {
                if (!send(emitter, event)) {
                    emitters.remove(emitter);
                }
            }
            if (terminal) {
                emitters.forEach(SseEmitter::complete);
                emitters.clear();
            }
        }

        synchronized void attach(SseEmitter emitter) {
            for (Event event : history) {
                if (!send(emitter, event)) {
                    return;
                }
            }
            boolean finished = finishedAt != null;
            if (!finished && lastProgress != null && history.stream().noneMatch(e -> e.data() == lastProgress)) {
                send(emitter, new Event("progress", lastProgress));
            }
            if (finished) {
                emitter.complete();
                return;
            }
            emitters.add(emitter);
            emitter.onCompletion(() -> emitters.remove(emitter));
            emitter.onTimeout(() -> emitters.remove(emitter));
            emitter.onError(e -> emitters.remove(emitter));
        }

        // Same lock as emit(): two threads writing one SSE connection would interleave frames.
        synchronized void ping() {
            for (SseEmitter emitter : emitters) {
                try {
                    emitter.send(SseEmitter.event().comment("running " + elapsedMillis() + " ms"));
                } catch (IOException | IllegalStateException e) {
                    emitters.remove(emitter);
                }
            }
        }

        private boolean send(SseEmitter emitter, Event event) {
            try {
                emitter.send(SseEmitter.event().id(id + ":" + history.size()).name(event.name())
                        .data(event.data(), MediaType.APPLICATION_JSON));
                return true;
            } catch (IOException | IllegalStateException e) {
                // The browser went away; the job keeps running and can be re-subscribed to.
                return false;
            }
        }
    }

    public static class BusyException extends RuntimeException {
        public BusyException(int running, int queued) {
            super(running + " analyses running and " + queued + " queued — try again later"
                    + " (migration-risk.jobs.concurrency / queue)");
        }
    }
}
