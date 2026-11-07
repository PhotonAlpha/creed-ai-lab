package com.creed.parent.service;

import com.creed.parent.convert.EmitterConvert;
import com.creed.parent.listener.EmitterEvent;
import com.creed.parent.listener.EmitterEventEnum;
import com.creed.parent.listener.EmitterProtocol;
import com.creed.parent.listener.vo.ProgressVo;
import com.creed.parent.utils.json.JacksonUtils;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.exception.ExceptionUtils;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * @author EthanCao
 * @description ccrg-tool
 * @date 7/11/25
 */
@Service
@Slf4j
public class SseServicePool {
    private static final Map<String, EmitterProtocol> EMITTERS = new ConcurrentHashMap<>(24);

    public SseEmitter createEmitter(String id) {
        log.info("create new with id:{}", id);
        SseEmitter emitter = new SseEmitter(0L);
        EmitterProtocol emitterEvent = new EmitterProtocol(emitter, EmitterEventEnum.INIT);
        EMITTERS.put(id, emitterEvent);
        emitter.onCompletion(() -> {
            log.info("onCompletion id:{} removing", id);
            EMITTERS.remove(id);
        });
        emitter.onTimeout(() -> {
            log.info("onTimeout id:{} removing", id);
            EMITTERS.remove(id);
        });
        emitter.onError((e) -> {
            log.info("onError id:{} removing:{}", id, ExceptionUtils.getMessage(e));
            EMITTERS.remove(id);
        });
        return emitter;
    }

    public EmitterProtocol getEmitter(String id) {
        log.info("getEmitter:{}", id);
        if (EMITTERS.containsKey(id)) {
            return EMITTERS.get(id);
        } else {
            log.info("Emitter not found with id:{}", id);
        }
        return null;
    }
    public EmitterProtocol remove(String id) {
        return EMITTERS.remove(id);
    }

    public SseEmitter defaultCompleted(String id) {
        log.info("fallback to default");
       /*  SseEmitter emitter = new SseEmitter(1000L);
        try {
            emitter.send(SseEmitter.event().name(EmitterEvent.EVENT_NAME).data("all Completed!", MediaType.TEXT_EVENT_STREAM));
            emitter.onTimeout(emitter::complete); // 主动结束响应
        } catch (IOException e) {
            log.error("send failed", e);
        } */

        SseEmitter emitter = new SseEmitter(5000L);
        AtomicInteger counting = new AtomicInteger(0);
        AtomicInteger total = new AtomicInteger(5);
        CompletableFuture.runAsync(() -> {
            for (int i = 0; i < 5; i++) {
                try {
                    TimeUnit.MILLISECONDS.sleep(500);
                } catch (InterruptedException e) {
                    throw new RuntimeException(e);
                }
                ProgressVo progressVo = EmitterConvert.INSTANCE.convertMock(id, counting, total);
                counting.incrementAndGet();
                try {
                    emitter.send(SseEmitter.event().name(EmitterEvent.EVENT_NAME).data(JacksonUtils.toJsonString(progressVo), MediaType.TEXT_EVENT_STREAM));
                } catch (IOException e) {
                    log.error("send failed", e);
                }
            }

            try {
                emitter.send(SseEmitter.event().name(EmitterEvent.EVENT_END).data("finished", MediaType.TEXT_EVENT_STREAM));
            } catch (IOException e) {
                log.error("force end failed", e);
                emitter.completeWithError(e);
            }
        });
        emitter.onTimeout(() -> {
            log.warn("主动结束响应");
            emitter.complete();
        });

        return emitter;
    }
}
