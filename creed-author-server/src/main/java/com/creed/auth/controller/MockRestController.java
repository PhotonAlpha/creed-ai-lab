package com.creed.auth.controller;

import com.creed.auth.controller.dto.HeavyResponse;
import com.creed.auth.metrics.JvmMemoryMetricsLogger;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.util.StopWatch;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Random;
import java.util.concurrent.TimeUnit;

/**
 * @author EthanCao
 * @description creed-ai-lab
 * @date 2026-05-19T17
 */
@RestController
@RequestMapping("/api/load")
@Slf4j
public class MockRestController {
    public static final Random RANDOM = new Random();
    @Resource
    JvmMemoryMetricsLogger jvmMemoryMetricsLogger;

    @PostMapping("/heavy")

    public HeavyResponse heavyLoadRequest() {
        int sleeptime = RANDOM.nextInt(10) + 3;
        try {
            TimeUnit.SECONDS.sleep(sleeptime);
        } catch (InterruptedException e) {
            throw new RuntimeException(e);
        }
        log.info("sleeptime is {} s", sleeptime);
        return new HeavyResponse("heavy", "success");
    }

    @PostMapping("/light")
    public HeavyResponse lightLoad() {
        int sleeptime = RANDOM.nextInt(2000) + 500;
        try {
            TimeUnit.MILLISECONDS.sleep(sleeptime);
        } catch (InterruptedException e) {
            throw new RuntimeException(e);
        }
        log.info("sleeptime is {} ms", sleeptime);
        return new HeavyResponse("light", "success");
    }


    @GetMapping("/logging")
    public String logging() {
        StopWatch stopWatch = new StopWatch("metrics");
        stopWatch.start("metrics");
        jvmMemoryMetricsLogger.loggingJVMMetrics();
        jvmMemoryMetricsLogger.loggingTomcatMetrics();
        jvmMemoryMetricsLogger.loggingHttpBucketMetrics();
        jvmMemoryMetricsLogger.loggingTomcatRequestMetrics();
        stopWatch.stop();
        log.info(stopWatch.prettyPrint(TimeUnit.MILLISECONDS));
        return "OK";
    }
}
