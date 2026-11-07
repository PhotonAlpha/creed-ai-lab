package com.creed.parent.controller;

import com.creed.parent.listener.EmitterProtocol;
import com.creed.parent.service.SseServicePool;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.exception.ExceptionUtils;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.TimeUnit;

/**
 * @author EthanCao
 * @description ccrg-tool
 * @date 7/11/25
 */
@RestController
@RequestMapping("/api/sse")
@Slf4j
public class SseController {
    @Resource
    SseServicePool sseServicePool;

    @GetMapping("/{id}")
    public SseEmitter connect(@PathVariable("id") String id) {
        return Optional.ofNullable(sseServicePool.getEmitter(id))
                .map(EmitterProtocol::getSseEmitter)
                .orElse(sseServicePool.createEmitter(id));
    }

    @GetMapping("/connect/{id}")
    public SseEmitter reConnect(@PathVariable("id") String id) {
        return Optional.ofNullable(sseServicePool.getEmitter(id))
                .map(EmitterProtocol::getSseEmitter)
                .orElse(sseServicePool.defaultCompleted(id));
    }

    @GetMapping("/trigger/{id}")
    public String trigger(@PathVariable("id") String id) {
        SseEmitter emitter = Optional.ofNullable(sseServicePool.getEmitter(id))
                .map(EmitterProtocol::getSseEmitter)
                .orElse(null);
        if (Objects.nonNull(emitter)) {
            for (int i = 0; i < 10; i++) {
                try {
                    emitter.send(SseEmitter.event().name("message").data("%s/%s reports generating".formatted(i + 1, 10), MediaType.TEXT_EVENT_STREAM));
                    TimeUnit.SECONDS.sleep(1);
                } catch (IOException e) {
                    log.error("emitter send error:{}", ExceptionUtils.getMessage(e));
                } catch (InterruptedException e) {
                    log.error("InterruptedException:{}", ExceptionUtils.getMessage(e));
                    throw new RuntimeException(e);
                }
            }
            emitter.complete();
            return "Event Done";
        } else {
            return "Not Found";
        }
    }
}
