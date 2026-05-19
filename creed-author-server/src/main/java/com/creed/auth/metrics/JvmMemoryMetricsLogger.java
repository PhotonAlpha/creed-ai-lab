package com.creed.auth.metrics;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Tag;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
public class JvmMemoryMetricsLogger {

    private static final Logger log = LoggerFactory.getLogger(JvmMemoryMetricsLogger.class);

    private static final List<String> METRIC_NAMES = List.of(
            "jvm.memory.used",
            "jvm.memory.committed",
            "jvm.memory.max",
            "jvm.memory.usage.after.gc"
    );

    private final MeterRegistry meterRegistry;
    private final String application;

    public JvmMemoryMetricsLogger(MeterRegistry meterRegistry,
                                  @Value("${spring.application.name:unknown}") String application) {
        this.meterRegistry = meterRegistry;
        this.application = application;
    }

    @Scheduled(fixedDelayString = "${creed.metrics.jvm-memory.interval-ms:30000}",
            initialDelayString = "${creed.metrics.jvm-memory.initial-delay-ms:10000}")
    public void logJvmMemory() {
        for (String name : METRIC_NAMES) {
            for (Gauge gauge : meterRegistry.find(name).gauges()) {
                double value = gauge.value();
                if (Double.isNaN(value)) {
                    continue;
                }
                log.info("metric_type=jvm_memory application=\"{}\" metric=\"{}\" area=\"{}\" id=\"{}\" value={}",
                        application,
                        name,
                        tag(gauge, "area"),
                        tag(gauge, "id"),
                        (long) value);
            }
        }
    }

    private static String tag(Gauge gauge, String key) {
        for (Tag t : gauge.getId().getTagsAsIterable()) {
            if (t.getKey().equals(key)) {
                return t.getValue();
            }
        }
        return "";
    }
}
