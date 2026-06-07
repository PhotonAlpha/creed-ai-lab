package com.ethan.controller;

import com.ethan.controller.dto.HeavyResponse;
import com.ethan.service.MockRetryService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.*;

import java.util.Optional;
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
@RequiredArgsConstructor
public class MockRestController {
    public static final Random RANDOM = new Random();

    private final MockRetryService mockRetryService;

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

    /**
     * Exercises the resilience4j {@code @Retry} on {@link MockRetryService#unstableCall()}.
     * Called from a separate bean so the AOP proxy applies (self-invocation would bypass it).
     */
    @PostMapping("/retry")
    public HeavyResponse retry() {
        Optional<Object> result = mockRetryService.unstableCall();
        return new HeavyResponse("retry", result.map(Object::toString).orElse("recovered"));
    }

}
