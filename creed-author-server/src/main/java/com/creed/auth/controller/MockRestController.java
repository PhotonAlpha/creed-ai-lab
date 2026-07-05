package com.creed.auth.controller;

import com.creed.auth.controller.dto.HeavyResponse;
import com.creed.metrics.ActuatorMetricsLogger;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.util.StopWatch;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.client.RestTemplate;

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
    ActuatorMetricsLogger actuatorMetricsLogger;

    /** HttpClient5-backed RestTemplate that calls creed-gateway over HTTPS (GatewayRestTemplateConfiguration). */
    @Resource
    RestTemplate gatewayRestTemplate;

    /**
     * Calls creed-gateway over HTTPS through the pooled HttpClient5 RestTemplate. The {@code path}
     * resolves against the gateway base URL (default https://localhost:8080), e.g.
     * {@code /api/load/gateway?path=/api/catalog/items}. Each call leases a connection from the pool,
     * so the httpcomponents.httpclient.pool.* metrics move while requests are in flight.
     */
    @GetMapping("/gateway")
    public ResponseEntity<String> callGateway(@RequestParam(defaultValue = "/api/catalog/items") String path) {
        ResponseEntity<String> response = gatewayRestTemplate.getForEntity(path, String.class);
        log.info("gateway call path={} status={}", path, response.getStatusCode());
        actuatorMetricsLogger.loggingHttpClientPoolMetrics();
        return response;
    }

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
        actuatorMetricsLogger.loggingJVMMetrics();
        actuatorMetricsLogger.loggingTomcatMetrics();
        actuatorMetricsLogger.loggingHttpBucketMetrics();
        actuatorMetricsLogger.loggingTomcatRequestMetrics();
        actuatorMetricsLogger.loggingHttpClientPoolMetrics();
        stopWatch.stop();
        log.info(stopWatch.prettyPrint(TimeUnit.MILLISECONDS));
        return "OK";
    }
}
