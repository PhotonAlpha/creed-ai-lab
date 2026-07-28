package com.creed.simple.config;

import com.creed.simple.config.logging.SpanTaskDecorator;
import io.micrometer.tracing.Tracer;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Lazy;
import org.springframework.core.task.TaskDecorator;
import org.springframework.core.task.support.CompositeTaskDecorator;
import org.springframework.core.task.support.ContextPropagatingTaskDecorator;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.util.Arrays;
import java.util.concurrent.ThreadPoolExecutor;

/**
 * https://thelogiclooms.medium.com/how-to-get-ideal-thread-pool-size-9b2c0bb74906
 * Number of threads = Number of Available Cores * (1 + Wait time / Service time)
 *      - Wait time: Time spent waiting for IO-bound tasks to complete.
 *      - Service time: Time spent processing tasks.
 *      - Blocking coefficient: Wait time / Service time.
 *   core-size = 88    Number of Available Cores(8) * (1 + Wait time(500ms) / Service time(50ms))
 *   max-pool-size = coreSize * (2 ~ 4)
 *   queue-capacity = maxSize * (2 ~10)
 *
 */
@Configuration
@EnableAsync
@Slf4j
@EnableScheduling
public class SimpleThreadPoolConfig {

    public static final String TASK_EXECUTOR = "myTask";
    @Resource
    @Lazy
    Tracer tracer;

    @Bean
    public TaskDecorator taskDecorator() {
        return new CompositeTaskDecorator(Arrays.asList( new SpanTaskDecorator(tracer),
                new ContextPropagatingTaskDecorator())); //也可以自己实现，这边使用框架解决
    }

//    @Bean(name = TASK_EXECUTOR)
    public ThreadPoolTaskExecutor taskExecutor() {
        ThreadPoolTaskExecutor taskExecutor = new ThreadPoolTaskExecutor();
        taskExecutor.setCorePoolSize(20);
        taskExecutor.setMaxPoolSize(80);
        taskExecutor.setQueueCapacity(10);
        taskExecutor.setKeepAliveSeconds(2000);
        taskExecutor.setThreadNamePrefix("Asy-");
        taskExecutor.setRejectedExecutionHandler(new ThreadPoolExecutor.CallerRunsPolicy());
        taskExecutor.setWaitForTasksToCompleteOnShutdown(true);
        taskExecutor.setAwaitTerminationSeconds(60);
        taskExecutor.setTaskDecorator(taskDecorator());
        taskExecutor.initialize();
        return taskExecutor;
    }
}
