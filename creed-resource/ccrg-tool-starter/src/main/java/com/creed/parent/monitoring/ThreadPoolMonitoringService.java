package com.creed.parent.monitoring;

import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import jakarta.annotation.Resource;
import lombok.Getter;
import lombok.Setter;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.util.CollectionUtils;

import java.time.Duration;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.*;

/**
 * @author EthanCao
 * @description idbccs
 * @date 8/2/25
 */
@Service
@Slf4j
public class ThreadPoolMonitoringService {

    @Resource
    private ThreadPoolMonitoringConfiguration threadPoolMonitorConfiguration;
    @Autowired(required = false)
    Map<String, ExecutorService> executorServiceList;
    @Getter
    @Setter
    private ScheduledExecutorService scheduledExecutorService;

    @PostConstruct
    public void setup() {
        log.info("ThreadPoolMonitoringEnabled:{}", threadPoolMonitorConfiguration);
        if (Boolean.TRUE.equals(threadPoolMonitorConfiguration.getEnabled())) {
            scheduledExecutorService = Executors.newScheduledThreadPool(1);
            Duration interval = threadPoolMonitorConfiguration.getInterval();
            scheduledExecutorService.scheduleWithFixedDelay(this::doThreadPoolMonitoring, 0, interval.getSeconds(), TimeUnit.SECONDS);
        }
    }

    public void doThreadPoolMonitoring() {
        if (!CollectionUtils.isEmpty(executorServiceList)) {
            for (Map.Entry<String, ExecutorService> entry : executorServiceList.entrySet()) {
                String poolName = entry.getKey();
                ExecutorService executorService = entry.getValue();
                if (executorService instanceof ThreadPoolExecutor executor) {
                    loggingTheadPoolInfo(executor, poolName);
                } else {
                    log.warn("poolName:{} ExecutorService instance is:{}", poolName, executorService.getClass().getSimpleName());
                }
            }
        }
    }

    private static void loggingTheadPoolInfo(ThreadPoolExecutor executor, String poolName) {
        BlockingQueue<Runnable> queue = executor.getQueue();
        int size = queue.size();
        int corePoolSize = executor.getCorePoolSize();
        int maximumPoolSize = executor.getMaximumPoolSize();
        int activeCount = executor.getActiveCount();
        int remainingCapacity = queue.remainingCapacity();
        long completedTaskCount = executor.getCompletedTaskCount();
        int largestPoolSize = executor.getLargestPoolSize();
        log.info("ThreadPoolMonitoring, poolName:{}, corePoolSize:{}, maximumPoolSize:{}, queueSize:{}, queueRemainingCapacity:{}, activeCount:{}, completedTaskCount:{}, largestPoolSize:{}", poolName, corePoolSize, maximumPoolSize, size, remainingCapacity, activeCount, completedTaskCount, largestPoolSize);
    }

    @PreDestroy
    public void onDestroy() {
        if (Objects.nonNull(scheduledExecutorService)) {
            scheduledExecutorService.shutdown();
        }
    }
}
