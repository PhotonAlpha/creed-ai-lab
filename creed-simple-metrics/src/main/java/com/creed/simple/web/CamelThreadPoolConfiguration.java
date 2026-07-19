package com.creed.simple.web;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.binder.jvm.ExecutorServiceMetrics;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.task.TaskDecorator;
import org.springframework.scheduling.concurrent.CustomizableThreadFactory;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.ThreadPoolExecutor;

/**
 * Makes the Camel route thread pools observable.
 *
 * <p>The {@code <threadPool>} elements that used to live in {@code camel-context.xml} created plain
 * {@link ThreadPoolExecutor}s inside Camel's own {@code ExecutorServiceManager} — they were never Spring
 * beans, so Spring Boot's actuator (which auto-binds {@code ThreadPoolTaskExecutor} beans) had nothing to
 * instrument and no {@code executor_*} gauges appeared for them.
 *
 * <p>Here each pool is instead a Spring-managed {@link ExecutorService} wrapped with
 * {@link ExecutorServiceMetrics#monitor}, which registers the {@code executor.active/pool.size/pool.core/
 * pool.max/queued/queue.remaining/completed} meters (and an {@code executor.seconds} timer) tagged
 * {@code name=<bean id>}. The Camel routes keep referencing them unchanged via
 * {@code executorService="aggregatePoolA"} / {@code "notificationPoolB"}: that attribute is a registry
 * lookup by id, and a Spring bean of the same id now satisfies it (the returned {@code TimedExecutorService}
 * is still an {@link ExecutorService}, so Camel uses it directly).
 *
 * <p>Pool sizes/queues mirror the former {@code <threadPool>} definitions; the {@link CustomizableThreadFactory}
 * preserves the original {@code agg-poolA-*} / {@code notify-poolB-*} thread names. Result: the metrics light
 * up the dashboard's "Application Thread Pools (Executors)" panels under {@code name="aggregatePoolA"} and
 * {@code name="notificationPoolB"}.
 */
@Configuration(proxyBeanMethods = false)
public class CamelThreadPoolConfiguration {

    /**
     * Pool A: branches of the aggregation multicasts run here (threads named agg-poolA-*).
     */
    @Bean(destroyMethod = "shutdown")
    ExecutorService aggregatePoolA(MeterRegistry meterRegistry, TaskDecorator taskDecorator) {
//        ThreadPoolExecutor executor = new ThreadPoolExecutor(
//                8, 8, 0L, TimeUnit.MILLISECONDS,
//                new LinkedBlockingQueue<>(200),
//                new CustomizableThreadFactory("agg-poolA-"));
//        return ExecutorServiceMetrics.monitor(meterRegistry, executor, "aggregatePoolA");
        ThreadPoolTaskExecutor taskExecutor = new ThreadPoolTaskExecutor();
        taskExecutor.setCorePoolSize(8);
        taskExecutor.setMaxPoolSize(8);
        taskExecutor.setQueueCapacity(200);
        taskExecutor.setKeepAliveSeconds(0);
        taskExecutor.setThreadNamePrefix("agg-poolA-");

        taskExecutor.setRejectedExecutionHandler(new ThreadPoolExecutor.CallerRunsPolicy());
        taskExecutor.setWaitForTasksToCompleteOnShutdown(true);
        taskExecutor.setAwaitTerminationSeconds(60);
        taskExecutor.setTaskDecorator(taskDecorator);
        taskExecutor.initialize();
        return ExecutorServiceMetrics.monitor(meterRegistry, taskExecutor.getThreadPoolExecutor(), "aggregatePoolA");
    }

    /** Pool B: the wire-tapped notification runs here (threads named notify-poolB-*). */
    @Bean(destroyMethod = "shutdown")
    ExecutorService notificationPoolB(MeterRegistry meterRegistry, TaskDecorator taskDecorator) {
//        ThreadPoolExecutor executor = new ThreadPoolExecutor(
//                4, 4, 0L, TimeUnit.MILLISECONDS,
//                new LinkedBlockingQueue<>(100),
//                new CustomizableThreadFactory("notify-poolB-"));
//        return ExecutorServiceMetrics.monitor(meterRegistry, executor, "notificationPoolB");

        ThreadPoolTaskExecutor taskExecutor = new ThreadPoolTaskExecutor();
        taskExecutor.setCorePoolSize(4);
        taskExecutor.setMaxPoolSize(4);
        taskExecutor.setQueueCapacity(100);
        taskExecutor.setKeepAliveSeconds(0);
        taskExecutor.setThreadNamePrefix("notify-poolB-");

        taskExecutor.setRejectedExecutionHandler(new ThreadPoolExecutor.CallerRunsPolicy());
        taskExecutor.setWaitForTasksToCompleteOnShutdown(true);
        taskExecutor.setAwaitTerminationSeconds(60);
        taskExecutor.setTaskDecorator(taskDecorator);
        taskExecutor.initialize();
        return ExecutorServiceMetrics.monitor(meterRegistry, taskExecutor.getThreadPoolExecutor(), "notificationPoolB");
    }
}
