package com.creed.parent.listener;

import lombok.Data;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.concurrent.atomic.AtomicInteger;

/**
 * @author EthanCao
 * @description ccrg-tool
 * @date 7/11/25
 */
@Data
public class EmitterProtocol {
    private String connectionId;
    private AtomicInteger propertyCount;
    private AtomicInteger reportCount;
    private AtomicInteger total;
    private SseEmitter sseEmitter;
    private EmitterEventEnum eventName;

    public EmitterProtocol(SseEmitter sseEmitter, EmitterEventEnum eventName) {
        this.propertyCount = new AtomicInteger(0);
        this.reportCount = new AtomicInteger(0);
        this.total = new AtomicInteger(0);
        this.sseEmitter = sseEmitter;
        this.eventName = eventName;
    }
}
