import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Simulates creed-common-metrics ThreadPoolConfig#taskExecutor (40/160/100, CallerRunsPolicy) behind a
 * 300-thread "Tomcat" pool. Each request submits `array` CompletableFuture.runAsync tasks in a loop and
 * then joins them; each task sleeps 500~1000ms (scaled by `scale`) to stand in for the remote call.
 *
 * Usage: java -cp <spring jars> CallerRunsSim.java <requests> <array> <scale> [runs]
 */
public class CallerRunsSim {

    static final int TOMCAT_MAX = 300;

    public static void main(String[] args) throws Exception {
        int requests = Integer.parseInt(args[0]);
        int array = Integer.parseInt(args[1]);
        double scale = Double.parseDouble(args[2]);
        int runs = args.length > 3 ? Integer.parseInt(args[3]) : 1;
        for (int r = 1; r <= runs; r++) {
            run(requests, array, scale, r);
        }
    }

    static void run(int requests, int array, double scale, int runNo) throws Exception {
        ThreadPoolTaskExecutor taskExecutor = new ThreadPoolTaskExecutor();
        taskExecutor.setCorePoolSize(40);
        taskExecutor.setMaxPoolSize(160);
        taskExecutor.setQueueCapacity(100);
        taskExecutor.setKeepAliveSeconds(180);
        taskExecutor.setThreadNamePrefix("Asy-");
        taskExecutor.setRejectedExecutionHandler(new ThreadPoolExecutor.CallerRunsPolicy());
        taskExecutor.initialize();

        AtomicInteger tomcatSeq = new AtomicInteger();
        ExecutorService tomcat = Executors.newFixedThreadPool(TOMCAT_MAX,
                r -> new Thread(r, "http-nio-8096-exec-" + tomcatSeq.incrementAndGet()));

        AtomicInteger callerRuns = new AtomicInteger();
        AtomicInteger inCallerRun = new AtomicInteger();
        AtomicInteger peakInCallerRun = new AtomicInteger();
        Set<String> callerThreads = ConcurrentHashMap.newKeySet();

        CountDownLatch start = new CountDownLatch(1);
        List<Future<long[]>> results = new ArrayList<>();
        for (int q = 0; q < requests; q++) {
            results.add(tomcat.submit(() -> {
                start.await();
                long t0 = System.nanoTime();
                AtomicInteger mine = new AtomicInteger();
                List<CompletableFuture<Void>> futures = new ArrayList<>(array);
                for (int i = 0; i < array; i++) {
                    futures.add(CompletableFuture.runAsync(() -> {
                        boolean onCaller = Thread.currentThread().getName().startsWith("http-nio");
                        if (onCaller) {
                            callerRuns.incrementAndGet();
                            mine.incrementAndGet();
                            callerThreads.add(Thread.currentThread().getName());
                            peakInCallerRun.accumulateAndGet(inCallerRun.incrementAndGet(), Math::max);
                        }
                        try {
                            Thread.sleep((long) (ThreadLocalRandom.current().nextLong(500, 1001) * scale));
                        } catch (InterruptedException e) {
                            Thread.currentThread().interrupt();
                        } finally {
                            if (onCaller) inCallerRun.decrementAndGet();
                        }
                    }, taskExecutor));
                }
                CompletableFuture.allOf(futures.toArray(CompletableFuture[]::new)).join();
                return new long[]{mine.get(), (System.nanoTime() - t0) / 1_000_000};
            }));
        }
        start.countDown();

        int minPer = Integer.MAX_VALUE, maxPer = 0;
        long maxMs = 0, sumMs = 0;
        for (Future<long[]> f : results) {
            long[] res = f.get();
            minPer = (int) Math.min(minPer, res[0]);
            maxPer = (int) Math.max(maxPer, res[0]);
            maxMs = Math.max(maxMs, res[1]);
            sumMs += res[1];
        }
        System.out.printf(
                "run=%d requests=%d array=%d scale=%.2f | caller-runs=%d (per request %d~%d) | "
                        + "distinct tomcat threads in caller-run=%d | peak concurrent=%d | "
                        + "pool largest=%d | request ms avg=%d max=%d%n",
                runNo, requests, array, scale, callerRuns.get(), minPer, maxPer,
                callerThreads.size(), peakInCallerRun.get(),
                taskExecutor.getThreadPoolExecutor().getLargestPoolSize(), sumMs / requests, maxMs);
        tomcat.shutdown();
        taskExecutor.shutdown();
    }
}
