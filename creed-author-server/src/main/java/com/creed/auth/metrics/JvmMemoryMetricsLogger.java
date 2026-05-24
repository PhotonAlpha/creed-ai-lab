package com.creed.auth.metrics;

import com.nimbusds.jose.util.Pair;
import io.micrometer.core.instrument.FunctionCounter;
import io.micrometer.core.instrument.FunctionTimer;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.LongTaskTimer;
import io.micrometer.core.instrument.Meter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Tag;
import io.micrometer.core.instrument.TimeGauge;
import io.micrometer.core.instrument.Timer;
import io.micrometer.core.instrument.distribution.CountAtBucket;
import io.micrometer.core.instrument.distribution.HistogramSnapshot;
import io.micrometer.core.instrument.distribution.HistogramSupport;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.actuate.metrics.MetricsEndpoint;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.text.DecimalFormat;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import java.util.function.BiFunction;
import java.util.stream.Collectors;
import java.util.stream.StreamSupport;

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

    /**
     * Fixed SLO latency boundaries (seconds) emitted as http_server_requests_seconds_bucket {@code le=} lines.
     * Replacing the dozens of native percentile-histogram buckets with this short list is what caps the per-API
     * log volume. Adjust to taste; keep it short.
     */
    private static final double[] HTTP_SLO_SECONDS = {1, 2, 5};

    /**
     * Maximum number of APIs (slowest first, by max latency) printed per HTTP histogram cycle. The final cap on log
     * volume regardless of how many distinct endpoints exist.
     */
    private static final int HTTP_TOP_N = 10;

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

    /**
     * http_server_requests_active_seconds_bucket / _count / _sum / _max for in-flight requests, read per API from
     * the MeterRegistry. See {@link #loggingSlowestHttpHistograms} for the line-count controls. Here {@code count}
     * is the number of currently active tasks and the buckets count active tasks whose elapsed duration is
     * &le; the boundary.
     */
    public void loggingHttpRequestActiveBucketMetrics() {
        Collection<LongTaskTimer> timers = meterRegistry.find("http.server.requests.active").longTaskTimers();
        loggingHttpCount("http_server_requests_active_seconds", timers);
        loggingSlowestHttpHistograms("http_server_requests_active_seconds", timers);
    }

    /**
     * http_server_requests_seconds_bucket / _count / _sum / _max, read per API from the MeterRegistry.
     * Full per-API coverage: {@code _count} via {@link #loggingHttpCount} and the {@code _bucket} / {@code _sum} /
     * {@code _max} detail via {@link #loggingHttpHistograms} for every reportable API (no {@link #HTTP_TOP_N} cap).
     */
    public void loggingHttpBucketMetrics() {
        Collection<Timer> timers = meterRegistry.find("http.server.requests").timers();
        loggingHttpCount("http_server_requests_seconds", timers);
        loggingHttpHistograms("http_server_requests_seconds", timers);
    }

    /**
     * Emits {metricPrefix}_count for every reportable API — one cheap line each, independent of the
     * {@link #HTTP_TOP_N} cap — so total request counts (throughput / error rate) stay complete even when the
     * bucket detail is limited to the slowest endpoints.
     */
    private void loggingHttpCount(String metricPrefix, Collection<? extends HistogramSupport> meters) {
        for (HistogramSupport meter : meters) {
            if (!isReportableUri(meter.getId().getTag("uri"))) {
                continue;
            }
            log.info("actuator-metrics{metric_type={}_count,application={},{}value={}}",
                    metricPrefix, application, tagSuffix(meter.getId()), DF.format(meter.takeSnapshot().count()));
        }
    }

    /**
     * Logs an HTTP request histogram (completed or active) per API. {@code http.server.requests} carries the
     * high-cardinality tags uri/method/status/outcome/exception, and each timer with a percentile-histogram holds
     * dozens of native buckets, so the log volume is bounded three ways:
     * <ul>
     *   <li>noise URIs (UNKNOWN / actuator / error / static) are skipped — see {@link #isReportableUri};</li>
     *   <li>only the fixed {@link #HTTP_SLO_SECONDS} boundaries are emitted as {@code le=} lines, instead of every
     *       native bucket;</li>
     *   <li>only the slowest {@link #HTTP_TOP_N} APIs (by max latency) are printed.</li>
     * </ul>
     * Each surviving API costs {@code HTTP_SLO_SECONDS.length + 2} lines, with at most {@code HTTP_TOP_N} APIs.
     * ({@code _count} is handled separately by {@link #loggingHttpCount} for every API.)
     *
     * @param metricPrefix metric_type prefix (e.g. {@code http_server_requests_seconds}); this method appends
     *                     {@code _bucket} / {@code _sum} / {@code _max}
     * @param meters       the timers to report; {@link Timer} and {@link LongTaskTimer} both expose
     *                     {@link HistogramSupport#takeSnapshot()}
     */
    private void loggingSlowestHttpHistograms(String metricPrefix, Collection<? extends HistogramSupport> meters) {
        if (meters.isEmpty()) {
            log.info("actuator-metrics{metric_type={}_bucket,application={},note=no_meters_registered}",
                    metricPrefix, application);
            return;
        }
        meters.stream()
                .filter(meter -> isReportableUri(meter.getId().getTag("uri")))
                .map(meter -> Pair.of(tagSuffix(meter.getId()), meter.takeSnapshot()))
                .sorted(Comparator.comparingDouble(
                        (Pair<String, HistogramSnapshot> pair) -> pair.getRight().max(TimeUnit.SECONDS)).reversed())
                .limit(HTTP_TOP_N)
                .forEach(pair -> loggingHttpSnapshot(metricPrefix, pair.getLeft(), pair.getRight()));
    }

    /**
     * Same {@code _bucket} / {@code _sum} / {@code _max} detail as {@link #loggingSlowestHttpHistograms} but for
     * <em>every</em> reportable API — no {@link #HTTP_TOP_N} cap and no slowest-first ordering. URI filtering and the
     * {@link #HTTP_SLO_SECONDS} bucket restriction still apply, but log volume now scales with the number of
     * endpoints, so prefer this only when full per-API histogram coverage is required.
     */
    private void loggingHttpHistograms(String metricPrefix, Collection<? extends HistogramSupport> meters) {
        if (meters.isEmpty()) {
            log.info("actuator-metrics{metric_type={}_bucket,application={},note=no_meters_registered}",
                    metricPrefix, application);
            return;
        }
        meters.stream()
                .filter(meter -> isReportableUri(meter.getId().getTag("uri")))
                .forEach(meter -> loggingHttpSnapshot(metricPrefix, tagSuffix(meter.getId()), meter.takeSnapshot()));
    }

    private void loggingHttpSnapshot(String metricPrefix, String tagSuffix, HistogramSnapshot snapshot) {
        for (double le : HTTP_SLO_SECONDS) {
            log.info("actuator-metrics{metric_type={}_bucket,application={},{}le={},count={}}",
                    metricPrefix, application, tagSuffix, DF_00.format(le), DF.format(cumulativeCountAt(snapshot, le)));
        }
        // _count is emitted for every API by loggingHttpCount; only the top-N bucket/sum/max detail lives here.
        log.info("actuator-metrics{metric_type={}_sum,application={},{}value={}}",
                metricPrefix, application, tagSuffix, DF_00.format(snapshot.total(TimeUnit.SECONDS)));
        log.info("actuator-metrics{metric_type={}_max,application={},{}value={}}",
                metricPrefix, application, tagSuffix, DF_00.format(snapshot.max(TimeUnit.SECONDS)));
    }

    /**
     * Cumulative request count with latency &le; {@code leSeconds}, derived from the snapshot's native buckets.
     * Bucket counts are cumulative and monotonically increasing, so the largest native boundary &le; the target
     * carries the value. Returns 0 when histograms are disabled (no native buckets present).
     */
    private double cumulativeCountAt(HistogramSnapshot snapshot, double leSeconds) {
        double count = 0d;
        for (CountAtBucket bucket : snapshot.histogramCounts()) {
            if (bucket.bucket(TimeUnit.SECONDS) <= leSeconds) {
                count = bucket.count();
            } else {
                break;
            }
        }
        return count;
    }

    /** Skip low-value / high-cardinality URIs so the per-API output stays bounded. */
    private boolean isReportableUri(String uri) {
        if (!StringUtils.hasText(uri) || "UNKNOWN".equals(uri) || "/**".equals(uri)) {
            return false;
        }
        return
//                !uri.startsWith("/actuator") &&
                !"/error".equals(uri)
                && !uri.startsWith("/swagger")
                && !uri.startsWith("/v3/api-docs")
                && !uri.startsWith("/webjars")
                && !"/favicon.ico".equals(uri);
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

        loggingMetricKeys(List.of(
                Pair.of("tomcat.global.received", null),
                Pair.of("tomcat.global.sent", null)
        ), DF_0);
    }

    public void loggingTomcatRequestMetrics() {
        loggingServletRequestMetrics();
        loggingServletErrorMetrics();
        loggingServletRequestMaxMetrics();
    }

    /**
     * tomcat_servlet_request_seconds_count / tomcat_servlet_request_seconds_sum for the {@code dispatcherServlet}
     * only, backed by the {@code tomcat.servlet.request} FunctionTimer (filtered by its {@code name} tag).
     */
    private void loggingServletRequestMetrics() {
        FunctionTimer timer = meterRegistry.find("tomcat.servlet.request")
                .tag("name", "dispatcherServlet")
                .functionTimer();
        if (timer == null) {
            log.info("actuator-metrics{metric_type=tomcat_servlet_request_seconds_count,application={},note=no_meters_registered}",
                    application);
            return;
        }
        String tagSuffix = tagSuffix(timer.getId());
        log.info("actuator-metrics{metric_type=tomcat_servlet_request_seconds_count,application={},{}value={}}",
                application, tagSuffix, DF.format(timer.count()));
        log.info("actuator-metrics{metric_type=tomcat_servlet_request_seconds_sum,application={},{}value={}}",
                application, tagSuffix, DF_00.format(timer.totalTime(TimeUnit.SECONDS)));
    }

    /**
     * tomcat_servlet_error_total, backed by the {@code tomcat.servlet.error} FunctionCounter (one per servlet).
     */
    private void loggingServletErrorMetrics() {
        Collection<FunctionCounter> counters = meterRegistry.find("tomcat.servlet.error").functionCounters();
        if (counters.isEmpty()) {
            log.info("actuator-metrics{metric_type=tomcat_servlet_error_total,application={},note=no_meters_registered}",
                    application);
            return;
        }
        for (FunctionCounter counter : counters) {
            log.info("actuator-metrics{metric_type=tomcat_servlet_error_total,application={},{}value={}}",
                    application, tagSuffix(counter.getId()), DF.format(counter.count()));
        }
    }

    /**
     * tomcat_servlet_request_max_seconds, backed by the {@code tomcat.servlet.request.max} TimeGauge (one per servlet).
     */
    private void loggingServletRequestMaxMetrics() {
        Collection<TimeGauge> gauges = meterRegistry.find("tomcat.servlet.request.max").timeGauges();
        if (gauges.isEmpty()) {
            log.info("actuator-metrics{metric_type=tomcat_servlet_request_max_seconds,application={},note=no_meters_registered}",
                    application);
            return;
        }
        for (TimeGauge gauge : gauges) {
            log.info("actuator-metrics{metric_type=tomcat_servlet_request_max_seconds,application={},{}value={}}",
                    application, tagSuffix(gauge.getId()), DF_00.format(gauge.value(TimeUnit.SECONDS)));
        }
    }

    private String tagSuffix(Meter.Id id) {
        String tagPart = StreamSupport.stream(id.getTagsAsIterable().spliterator(), false)
                .map(t -> t.getKey() + "=" + t.getValue())
                .collect(Collectors.joining(","));
        return tagPart.isEmpty() ? "" : tagPart + ",";
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
