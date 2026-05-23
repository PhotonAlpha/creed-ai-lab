package com.creed.auth.metrics;

import com.nimbusds.jose.util.Pair;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Tag;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.actuate.metrics.MetricsEndpoint;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.text.DecimalFormat;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.function.BiFunction;
import java.util.stream.Collectors;

/**
 * @author EthanCao
 * @description creed-ai-lab
 * @date 2026-05-20T16
 */
@Slf4j
@Service
public class JvmMemoryMetricsLogger {
    public static final DecimalFormat DF = new DecimalFormat("0");
    public static final DecimalFormat DF_00 = new DecimalFormat("0.00");
    public static final DecimalFormat DF_0 = new DecimalFormat("0.0");
    /**
     * description JvmMemoryMetricsLogger
     * @author EthanCao
     * @since 2026-05-22T22
    {
    "names": [
    "application.ready.time",
    "application.started.time",
    "disk.free",
    "disk.total",
    "executor.active",
    "executor.completed",
    "executor.pool.core",
    "executor.pool.max",
    "executor.pool.size",
    "executor.queue.remaining",
    "executor.queued",
    "http.server.requests",
    "http.server.requests.active",
    "jvm.buffer.count",
    "jvm.buffer.memory.used",
    "jvm.buffer.total.capacity",
    "jvm.classes.loaded",
    "jvm.classes.unloaded",
    "jvm.compilation.time",
    "jvm.gc.live.data.size",
    "jvm.gc.max.data.size",
    "jvm.gc.memory.allocated",
    "jvm.gc.memory.promoted",
    "jvm.gc.overhead",
    "jvm.info",
    "jvm.memory.committed",
    "jvm.memory.max",
    "jvm.memory.usage.after.gc",
    "jvm.memory.used",
    "jvm.threads.daemon",
    "jvm.threads.live",
    "jvm.threads.peak",
    "jvm.threads.started",
    "jvm.threads.states",
    "logback.events",
    "process.cpu.time",
    "process.cpu.usage",
    "process.files.max",
    "process.files.open",
    "process.start.time",
    "process.uptime",
    "system.cpu.count",
    "system.cpu.usage",
    "system.load.average.1m",
    "tomcat.cache.access",
    "tomcat.cache.hit",
    "tomcat.connections.config.max",
    "tomcat.connections.current",
    "tomcat.connections.keepalive.current",
    "tomcat.global.error",
    "tomcat.global.received",
    "tomcat.global.request",
    "tomcat.global.request.max",
    "tomcat.global.sent",
    "tomcat.servlet.error",
    "tomcat.servlet.request",
    "tomcat.servlet.request.max",
    "tomcat.threads.busy",
    "tomcat.threads.config.max",
    "tomcat.threads.current"
    ]
    }
     */
    private static final List<String> JVM_USAGE_METRIC_NAMES = List.of(
            "jvm.memory.used",
            "jvm.memory.committed",
            "jvm.memory.max",
            "jvm.memory.usage.after.gc"
    );
    private static final List<String> CPU_METRIC_NAMES = List.of(
            "process.cpu.usage",
            "system.cpu.usage",
            "system.load.average.1m"
    );
    private static final List<String> JVM_THREADS_METRIC_NAMES = List.of(
            "jvm.threads.daemon",
            "jvm.threads.live",
            "jvm.threads.peak",
            "jvm.threads.states"
    );
    private static final List<String> TOMCAT_METRIC_NAMES = List.of(
            "tomcat.cache.access",
            "tomcat.cache.hit",
            "tomcat.connections.config.max",
            "tomcat.connections.current",
            "tomcat.connections.keepalive.current",
            "tomcat.global.error",
            "tomcat.global.received",
            "tomcat.global.request",
            "tomcat.global.request.max",
            "tomcat.global.sent",
            "tomcat.servlet.error",
            "tomcat.servlet.request",
            "tomcat.servlet.request.max",
            "tomcat.threads.busy",
            "tomcat.threads.config.max",
            "tomcat.threads.current"
    );

    private final MeterRegistry meterRegistry;
    private final String application;
    private final MetricsEndpoint metricsEndpoint;

    public JvmMemoryMetricsLogger(MeterRegistry meterRegistry,
                                  @Value("${spring.application.name:unknown}") String application) {
        this.meterRegistry = meterRegistry;
        this.application = application;
        metricsEndpoint = new MetricsEndpoint(meterRegistry);
    }

    public MetricsEndpoint.MetricNamesDescriptor listKeys() {
        MetricsEndpoint.MetricNamesDescriptor metricNamesDescriptor = metricsEndpoint.listNames();
        log.info("Metrics names: {}", metricNamesDescriptor.getNames());
        return metricNamesDescriptor;
    }

    public void loggingTomcatBucketMetrics() {
        log.info("Metrics names: {}", "names");
    }
    public void loggingTomcatMetrics() {
        loggingMetricKeys(List.of(
                Pair.of("tomcat.threads.busy", null),
                Pair.of("tomcat.threads.current", null),
                Pair.of("tomcat.threads.config.max", null),
                Pair.of("tomcat.connections.config.max", null),
                Pair.of("tomcat.connections.current", null),
                Pair.of("tomcat.connections.keepalive.current", null)
        ), DF_0);


    }

    public void loggingJVMMetrics() {
        loggingJVMMemory("jvm.memory.used");
        loggingJVMMemory("jvm.memory.committed");
        loggingJVMMemory("jvm.memory.max");
        loggingMetric("jvm.memory.usage.after.gc", DF_00);
    }

    protected void loggingJVMMemory(String metricType) {
        String metricName = StringUtils.replace(metricType, ".", "_");
        log.info("actuator-metrics{metric_type={},application={},{}={},{}={},{}={}}", metricName, application,
                metricName + "_total", DF.format(metricsExtractor().apply(metricType, null)),
                metricName + "_heap", DF.format(metricsExtractor().apply(metricType, List.of("area:heap"))),
                metricName + "_nonheap", DF.format(metricsExtractor().apply(metricType, List.of("area:nonheap"))));
    }


    protected void loggingMetric(String metricType, DecimalFormat decimalFormat) {
        String metricName = StringUtils.replace(metricType, ".", "_");
        log.info("actuator-metrics{metric_type={},application={},{}={}}", metricName, application,
                metricName, decimalFormat.format(metricsExtractor().apply(metricType, null)));
    }

    protected void loggingMetricKeys(List<Pair<String, List<String>>> metricKeys, DecimalFormat decimalFormat) {
        StringBuilder stringBuilder = new StringBuilder("actuator-metrics{");
        for (Pair<String, List<String>> pair : metricKeys) {
            String metricName = StringUtils.replace(pair.getLeft(), ".", "_");
            stringBuilder.append(metricName).append("=").append(decimalFormat.format(metricsExtractor().apply(pair.getLeft(), pair.getRight()))).append(",");
        }
        stringBuilder.append("}");
        log.info("{}", stringBuilder);
    }

    // @Scheduled(fixedDelayString = "${creed.metrics.jvm-memory.interval-ms:30000}",
    //         initialDelayString = "${creed.metrics.jvm-memory.initial-delay-ms:10000}")
    public void logMetrics(List<String>  metricNames) {
        List<MetricsEndpoint.AvailableTag> availableTags = metricsEndpoint.metric("tomcat.threads.busy", null).getAvailableTags();
        availableTags.stream()
                .map(tag -> tag.getTag() + String.join(",", tag.getValues()))
                .collect(Collectors.joining(":"));
        for (String name : metricNames) {
            /* for (Gauge gauge : meterRegistry.find(name).gauges()) {
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
            } */
        }
    }


    protected BiFunction<String, List<String>, Double> metricsExtractor() {
        return (metricType, tags) ->

                Optional.ofNullable(metricsEndpoint.metric(metricType, tags))
                .map(MetricsEndpoint.MetricDescriptor::getMeasurements)
                .orElse(Collections.emptyList())
                .stream().findFirst().map(MetricsEndpoint.Sample::getValue)
                .orElse(0.0);
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
