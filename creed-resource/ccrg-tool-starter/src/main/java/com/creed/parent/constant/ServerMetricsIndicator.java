package com.creed.parent.constant;

import lombok.Getter;

/**
 * @author EthanCao
 * @description ccrg-tool
 * @date 2026-03-21T01
 */
public enum ServerMetricsIndicator {
    MEMORY_USED("jvm.memory.used"),
    MEMORY_MAX("jvm.memory.max"),
    MEMORY_MAX_AFTER_GC("jvm.memory.usage.after.gc"),
    MEMORY_COMMITTED("jvm.memory.committed"),
    PROCESS_UPTIME("process.uptime"),
    THREADS_DAEMON("jvm.threads.daemon"),
    THREADS_LIVE("jvm.threads.live"),
    CPU_USAGE("process.cpu.usage"),
    ;
    @Getter
    private String indicator;

    ServerMetricsIndicator(String indicator) {
        this.indicator = indicator;
    }
}
