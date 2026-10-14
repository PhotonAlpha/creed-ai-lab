package com.creed.simple.tutorial;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.binder.jvm.ExecutorServiceMetrics;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.task.TaskDecorator;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.ThreadPoolExecutor;

/**
 * The pool the tutorial routes fan out on: API-4/API-6's parallel {@code <multicast>} and the
 * {@code <wireTap>} of API-5/API-6 ({@code executorService="tutorialTaskPool"} in
 * {@code camel/tutorial/*.xml}). Threads are named {@code tutorial-pool-*}.
 *
 * <p>Its own pool rather than {@code aggregatePoolA}: the tutorial's load must not queue behind (or
 * in front of) the downstream-calling routes. Same shape as {@code CamelThreadPoolConfiguration}'s
 * pools — the module's {@link TaskDecorator} carries the trace context onto the pool threads, and
 * {@link ExecutorServiceMetrics} publishes {@code executor_*{name="tutorialTaskPool"}}.
 */
@Configuration(proxyBeanMethods = false)
public class TutorialThreadPoolConfiguration {

    @Bean(destroyMethod = "shutdown")
    ExecutorService tutorialTaskPool(MeterRegistry meterRegistry, TaskDecorator taskDecorator) {
        ThreadPoolTaskExecutor taskExecutor = new ThreadPoolTaskExecutor();
        taskExecutor.setCorePoolSize(8);
        taskExecutor.setMaxPoolSize(8);
        taskExecutor.setQueueCapacity(200);
        taskExecutor.setKeepAliveSeconds(0);
        taskExecutor.setThreadNamePrefix("tutorial-pool-");
        taskExecutor.setRejectedExecutionHandler(new ThreadPoolExecutor.CallerRunsPolicy());
        taskExecutor.setWaitForTasksToCompleteOnShutdown(true);
        taskExecutor.setAwaitTerminationSeconds(60);
        taskExecutor.setTaskDecorator(taskDecorator);
        taskExecutor.initialize();
        return ExecutorServiceMetrics.monitor(meterRegistry, taskExecutor.getThreadPoolExecutor(), "tutorialTaskPool");
    }
}
