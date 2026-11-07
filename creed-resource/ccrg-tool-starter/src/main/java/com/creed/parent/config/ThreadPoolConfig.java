package com.creed.parent.config;

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
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.util.Arrays;
import java.util.concurrent.ThreadPoolExecutor;


@Configuration
@EnableAsync
@Slf4j
public class ThreadPoolConfig {

    public static final String TASK_EXECUTOR = "taskExecutor";
    public static final String EVENT_EXECUTOR = "eventExecutor";
    @Resource
    @Lazy
    Tracer tracer;
    @Bean
    public TaskDecorator taskDecorator() {
        return new CompositeTaskDecorator(Arrays.asList( new SpanTaskDecorator(tracer),
                new ContextPropagatingTaskDecorator()));
    }

    @Bean(name = TASK_EXECUTOR)
    public ThreadPoolTaskExecutor taskExecutor() {
        ThreadPoolTaskExecutor taskExecutor = new ThreadPoolTaskExecutor();
        taskExecutor.setCorePoolSize(100);
        taskExecutor.setMaxPoolSize(300);
        taskExecutor.setQueueCapacity(300);
        taskExecutor.setKeepAliveSeconds(180);
        taskExecutor.setThreadNamePrefix("Asy-");
        taskExecutor.setRejectedExecutionHandler(new ThreadPoolExecutor.CallerRunsPolicy());
        taskExecutor.setWaitForTasksToCompleteOnShutdown(true);
        taskExecutor.setAwaitTerminationSeconds(60);
        taskExecutor.setTaskDecorator(taskDecorator());
        taskExecutor.initialize();
        return taskExecutor;
    }
    @Bean(name = EVENT_EXECUTOR)
    public ThreadPoolTaskExecutor eventExecutor() {
        ThreadPoolTaskExecutor taskExecutor = new ThreadPoolTaskExecutor();
        taskExecutor.setCorePoolSize(30);
        taskExecutor.setMaxPoolSize(100);
        taskExecutor.setQueueCapacity(100);
        taskExecutor.setKeepAliveSeconds(180);
        taskExecutor.setThreadNamePrefix("Evn-");
        taskExecutor.setRejectedExecutionHandler(new ThreadPoolExecutor.CallerRunsPolicy());
        taskExecutor.setWaitForTasksToCompleteOnShutdown(true);
        taskExecutor.setAwaitTerminationSeconds(60);
        taskExecutor.setTaskDecorator(taskDecorator());
        taskExecutor.initialize();
        return taskExecutor;
    }
}
