package com.creed.parent.listener;

import lombok.Getter;
import org.springframework.context.ApplicationEvent;

/**
 * @author EthanCao
 * @description ccrg-tool
 * @date 7/11/25
 */
@Getter
public class EmitterEvent extends ApplicationEvent {
    public static final String EVENT_NAME = "message";
    public static final String EVENT_END = "end";
    private final String connectionId;
    private Integer total = 0;
    private final EmitterEventEnum eventType;

    public EmitterEvent(Object source, String connectionId, EmitterEventEnum eventType) {
        super(source);
        this.connectionId = connectionId;
        this.eventType = eventType;
    }

    public EmitterEvent(Object source, String connectionId, EmitterEventEnum eventType, Integer total) {
        super(source);
        this.connectionId = connectionId;
        this.eventType = eventType;
        this.total = total;
    }
}
