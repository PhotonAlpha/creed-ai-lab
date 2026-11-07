package com.creed.parent.listener;

import com.creed.parent.service.SseServicePool;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.event.EventListener;
import org.springframework.http.MediaType;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.text.MessageFormat;
import java.util.Objects;
import java.util.concurrent.TimeUnit;
import java.util.function.BiConsumer;
import java.util.function.BiFunction;

import static com.creed.parent.config.ThreadPoolConfig.EVENT_EXECUTOR;

/**
 * @author EthanCao
 * @description ccrg-tool
 * @date 7/11/25
 */
@Component
@Slf4j
public class EmitterEventListener {
    @Resource
    SseServicePool sseServicePool;
    
    @EventListener
    @Async(EVENT_EXECUTOR)
    public void emitterEvent(EmitterEvent emitterEvent) {
        String connectionId = emitterEvent.getConnectionId();

        emitterConsumer().accept(connectionId, emitterEvent);
    }

    protected BiConsumer<String, EmitterEvent> emitterConsumer() {
        return (connectionId, event) -> {

            EmitterProtocol emitter = sseServicePool.getEmitter(connectionId);
            try {
                if (Objects.isNull(emitter) && EmitterEventEnum.SET_TOTAL.equals(event.getEventType())) {
                    //wait 1s if SET_TOTAL
                    try {
                        TimeUnit.SECONDS.sleep(1);
                    } catch (InterruptedException e) {
                        log.error("InterruptedException", e);
                        /* Clean up whatever needs to be handled before interrupting  */
                        Thread.currentThread().interrupt();
                    }
                    emitter = sseServicePool.getEmitter(connectionId);
                }
                if (Objects.nonNull(emitter)) {
                    if (EmitterEventEnum.SET_TOTAL.equals(event.getEventType())) {
                        int currentCount = emitter.getTotal().get();
                        emitter.getTotal().set(currentCount + event.getTotal());
                    }
                    String sendingMessage = emitterFunc().apply(emitter, event.getEventType());
                    emitter.getSseEmitter().send(SseEmitter.event().name(EmitterEvent.EVENT_NAME).data(sendingMessage, MediaType.TEXT_EVENT_STREAM));

                    if (EmitterEventEnum.COMPLETED.equals(event.getEventType())) {
                        emitter.getSseEmitter().complete();
                    }
                } else {
                    log.warn("connectionId:{} not found", connectionId);
                }
            } catch (IOException e) {
                throw new RuntimeException(e);
            }
        };
    }
    protected BiFunction<EmitterProtocol, EmitterEventEnum, String> emitterFunc() {
        return (emitter, type) -> switch (type) {
            case INIT -> "Loading...";
            case SET_TOTAL ->
                    MessageFormat.format("Reading configuration file... ({0}/{1})", emitter.getPropertyCount().get(), emitter.getTotal().get());
            case PROPERTIES_COUNTING ->
                    MessageFormat.format("Reading configuration file... ({0}/{1})", emitter.getPropertyCount().incrementAndGet(), emitter.getTotal().get());
            case REPORT_INIT ->
                    MessageFormat.format("Generating reports file... ({0}/{1})", emitter.getReportCount().get(), emitter.getTotal().get());
            case REPORT_COUNTING -> MessageFormat.format("Generating reports file... ({0}/{1})", emitter.getReportCount().incrementAndGet(), emitter.getTotal().get());
            case COMPLETED -> "All completed!";
        };
    }
}
