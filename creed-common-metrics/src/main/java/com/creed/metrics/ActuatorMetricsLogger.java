package com.creed.metrics;

import io.micrometer.core.instrument.*;
import io.micrometer.core.instrument.Timer;
import io.micrometer.core.instrument.distribution.CountAtBucket;
import io.micrometer.core.instrument.distribution.HistogramSnapshot;
import io.micrometer.core.instrument.distribution.HistogramSupport;
import jakarta.annotation.Resource;
import org.apache.commons.lang3.tuple.Pair;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.micrometer.metrics.actuate.endpoint.MetricsEndpoint;
import org.springframework.context.annotation.Lazy;
import org.springframework.scheduling.annotation.Async;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.util.StopWatch;
import org.springframework.util.StringUtils;

import java.text.DecimalFormat;
import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.function.BiFunction;
import java.util.function.Predicate;
import java.util.stream.Collectors;
import java.util.stream.StreamSupport;

import static com.creed.metrics.config.ThreadPoolConfig.TASK_EXECUTOR;

/**
 * @author EthanCao
 * @description creed-ai-lab
 * @date 2026-05-20T16
 */
@Service
public class ActuatorMetricsLogger {
    @Lazy
    @Resource
    ActuatorMetricsLogger self;

    @Async(TASK_EXECUTOR)
    public void initTaskExecutor() {
        try {
            TimeUnit.SECONDS.sleep(1);
        } catch (InterruptedException e) {
            throw new RuntimeException(e);
        }
        log.info("Initializing task executor successful");
    }

    /**
     * 指标日志使用固定的具名 logger("METRICS"),与类名/包名解耦。
     * 任何项目只要复用本类与 logback 中的 {@code <logger name="METRICS">} 配置即可通用。
     */
    private static final Logger log = LoggerFactory.getLogger("METRICS");

    public static final DecimalFormat DF = new DecimalFormat("0");
    public static final DecimalFormat DF_00 = new DecimalFormat("0.00");
    public static final DecimalFormat DF_0 = new DecimalFormat("0.0");

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
    private static final List<String> EXECUTOR_METRIC_NAMES = List.of(
            "executor.active",
            "executor.queued",
            "executor.completed",
            "executor.pool.size",
            "executor.pool.core",
            "executor.pool.max",
            "executor.queue.remaining"
    );
    /**
     * Apache HttpClient 5 connection-pool gauges registered by
     * {@code ConnectionPoolMetrics} (see {@code GatewayRestTemplateConfiguration}).
     * Every meter carries an {@code httpclient=<name>} tag; {@code total.connections} additionally splits
     * into {@code state=available} / {@code state=leased}.
     */
    private static final String HTTPCLIENT_POOL_PREFIX = "httpcomponents.httpclient.pool";

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

    public ActuatorMetricsLogger(MeterRegistry meterRegistry,
                                 @Value("${spring.application.name:unknown}") String application) {
        this.meterRegistry = meterRegistry;
        this.application = application;
        metricsEndpoint = new MetricsEndpoint(meterRegistry);
    }

    // ------------------------------------------------------------------------------------------------------------
    // Splunk-friendly key=value exposition helpers
    //
    // Every metric line is rendered as a flat, space-delimited key=value record that Splunk auto-extracts
    // (KV_MODE=auto) with no props.conf needed:
    //     actuator-metrics metric=<name> application=<app> <label>=<v> ... value=<n>
    // The field names {@code metric}, {@code application} and {@code value} are STATIC across every line, so a single
    // query can filter by {@code metric=...} and aggregate {@code value} (stats/timechart) — unlike a Prometheus
    // {@code name{labels} value} line, whose metric name and value are positional and need custom extraction.
    // Dots in Micrometer meter names are converted to underscores so the {@code metric} token stays a clean value.
    // These few helpers replace the hand-built string templates that used to be repeated in every logging method.
    // ------------------------------------------------------------------------------------------------------------

    /** Marker token prefixing every metric line, used as a cheap Splunk base-search anchor. */
    private static final String LINE_PREFIX = "actuator-metrics metric=";

    /** Micrometer meter names use dots; underscores keep the {@code metric} token a single clean value. */
    private static String prometheusName(String metricType) {
        return StringUtils.replace(metricType, ".", "_");
    }

    /** A single {@code key=value} field. */
    private static String label(String key, String value) {
        return key + "=" + value;
    }

    /** Append one {@code key=value} field to an existing (possibly empty) space-delimited field string. */
    private static String addLabel(String labels, String key, String value) {
        String pair = label(key, value);
        return labels.isEmpty() ? pair : labels + " " + pair;
    }

    /** Render a meter's tags as a space-delimited {@code key=value} field string (no metric/application/value). */
    private static String tagLabels(Meter.Id id) {
        return StreamSupport.stream(id.getTagsAsIterable().spliterator(), false)
                .map(t -> label(t.getKey(), t.getValue()))
                .collect(Collectors.joining(" "));
    }

    /** First measurement value of a meter, or 0 when it exposes none. */
    private static double firstMeasurement(Meter meter) {
        return StreamSupport.stream(meter.measure().spliterator(), false)
                .mapToDouble(Measurement::getValue)
                .findFirst()
                .orElse(0d);
    }

    /** Build one flat Splunk record: {@code actuator-metrics metric=<name> application=<app> <labels> value=<n>}. */
    private String format(String metricName, String extraLabels, String value) {
        StringBuilder sb = new StringBuilder(LINE_PREFIX)
                .append(prometheusName(metricName))
                .append(" application=").append(application);
        if (!extraLabels.isEmpty()) {
            sb.append(' ').append(extraLabels);
        }
        return sb.append(" value=").append(value).toString();
    }

    /** Log one flat Splunk sample line. */
    private void emit(String metricName, String extraLabels, String value) {
        log.info("{}", format(metricName, extraLabels, value));
    }

    /** Line marking that no meters were registered for {@code metricName} this cycle. */
    private void emitNoMeters(String metricName) {
        log.info("{}{} application={} note=no_meters_registered", LINE_PREFIX, prometheusName(metricName), application);
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
            emit(metricPrefix + "_count", tagLabels(meter.getId()), DF.format(meter.takeSnapshot().count()));
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
     * @param metricPrefix metric name prefix (e.g. {@code http_server_requests_seconds}); this method appends
     *                     {@code _bucket} / {@code _sum} / {@code _max}
     * @param meters       the timers to report; {@link Timer} and {@link LongTaskTimer} both expose
     *                     {@link HistogramSupport#takeSnapshot()}
     */
    private void loggingSlowestHttpHistograms(String metricPrefix, Collection<? extends HistogramSupport> meters) {
        if (meters.isEmpty()) {
            emitNoMeters(metricPrefix + "_bucket");
            return;
        }
        meters.stream()
                .filter(meter -> isReportableUri(meter.getId().getTag("uri")))
                .map(meter -> Pair.of(tagLabels(meter.getId()), meter.takeSnapshot()))
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
            emitNoMeters(metricPrefix + "_bucket");
            return;
        }
        meters.stream()
                .filter(meter -> isReportableUri(meter.getId().getTag("uri")))
                .forEach(meter -> loggingHttpSnapshot(metricPrefix, tagLabels(meter.getId()), meter.takeSnapshot()));
    }

    private void loggingHttpSnapshot(String metricPrefix, String labels, HistogramSnapshot snapshot) {
        for (double le : HTTP_SLO_SECONDS) {
            emit(metricPrefix + "_bucket", addLabel(labels, "le", DF_00.format(le)),
                    DF.format(cumulativeCountAt(snapshot, le)));
        }
        // _count is emitted for every API by loggingHttpCount; only the top-N bucket/sum/max detail lives here.
        emit(metricPrefix + "_sum", labels, DF_00.format(snapshot.total(TimeUnit.SECONDS)));
        emit(metricPrefix + "_max", labels, DF_00.format(snapshot.max(TimeUnit.SECONDS)));
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
            emitNoMeters("tomcat_servlet_request_seconds_count");
            return;
        }
        String labels = tagLabels(timer.getId());
        emit("tomcat_servlet_request_seconds_count", labels, DF.format(timer.count()));
        emit("tomcat_servlet_request_seconds_sum", labels, DF_00.format(timer.totalTime(TimeUnit.SECONDS)));
    }

    /**
     * tomcat_servlet_error_total, backed by the {@code tomcat.servlet.error} FunctionCounter (one per servlet).
     */
    private void loggingServletErrorMetrics() {
        Collection<FunctionCounter> counters = meterRegistry.find("tomcat.servlet.error").functionCounters();
        if (counters.isEmpty()) {
            emitNoMeters("tomcat_servlet_error_total");
            return;
        }
        for (FunctionCounter counter : counters) {
            emit("tomcat_servlet_error_total", tagLabels(counter.getId()), DF.format(counter.count()));
        }
    }

    /**
     * tomcat_servlet_request_max_seconds, backed by the {@code tomcat.servlet.request.max} TimeGauge (one per servlet).
     */
    private void loggingServletRequestMaxMetrics() {
        Collection<TimeGauge> gauges = meterRegistry.find("tomcat.servlet.request.max").timeGauges();
        if (gauges.isEmpty()) {
            emitNoMeters("tomcat_servlet_request_max_seconds");
            return;
        }
        for (TimeGauge gauge : gauges) {
            emit("tomcat_servlet_request_max_seconds", tagLabels(gauge.getId()),
                    DF_00.format(gauge.value(TimeUnit.SECONDS)));
        }
    }

    public void loggingJVMMetrics() {
        loggingJVMMemory("jvm.memory.used");
        loggingJVMMemory("jvm.memory.committed");
        loggingJVMMemory("jvm.memory.max");
        loggingMetric("jvm.memory.usage.after.gc", DF_00);
    }

    /**
     * executor.* 线程池指标,按线程池(name 标签,如 applicationTaskExecutor)逐条打印 Prometheus 行,
     * 覆盖 active / queued / completed / pool.size / pool.core / pool.max / queue.remaining。
     * <p>
     * 复用 {@link #loggingMeters} 的单次注册表遍历策略:对每个 executor.* 指标按 name 标签过滤,
     * 直接从 {@link Meter#measure()} 读数,避免对每个指标重复扫描注册表。
     */
    public void loggingThreadPoolMetrics() {
        loggingMeters("executor", meter -> EXECUTOR_METRIC_NAMES.contains(meter.getId().getName())
                && StringUtils.hasText(meter.getId().getTag("name")), DF);
    }

    /**
     * httpcomponents.httpclient.pool.* — Apache HttpClient 5 连接池指标,逐条打印 Prometheus 行
     * ({@code httpclient} 标签,如 {@code creed-gateway})。覆盖
     * total.max / total.connections(state=available|leased)/ total.pending / route.max.default。
     * <p>
     * 复用 {@link #loggingMeters} 的单次遍历策略:{@code total.connections} 带 state 标签的两条序列会原样
     * 各打一行,state 作为 Prometheus 标签保留(不再拼进字段名)。
     */
    public void loggingHttpClientPoolMetrics() {
        loggingMeters(HTTPCLIENT_POOL_PREFIX, meter -> meter.getId().getName().startsWith(HTTPCLIENT_POOL_PREFIX)
                && StringUtils.hasText(meter.getId().getTag("httpclient")), DF);
    }

    /**
     * Single-pass registry scan shared by {@link #loggingThreadPoolMetrics()} and
     * {@link #loggingHttpClientPoolMetrics()}: every meter matching {@code filter} is rendered as one flat Splunk
     * sample line. Lines are keyed by {@code name + labels} in a {@link TreeMap} so output ordering is stable across
     * cycles. An {@link #emitNoMeters} line is logged when nothing matches.
     */
    private void loggingMeters(String noMetersName, Predicate<Meter> filter, DecimalFormat decimalFormat) {
        Map<String, String> lines = new TreeMap<>();
        for (Meter meter : meterRegistry.getMeters()) {
            if (!filter.test(meter)) {
                continue;
            }
            Meter.Id id = meter.getId();
            String labels = tagLabels(id);
            String sortKey = prometheusName(id.getName()) + " " + labels;
            lines.put(sortKey, format(id.getName(), labels, decimalFormat.format(firstMeasurement(meter))));
        }
        if (lines.isEmpty()) {
            emitNoMeters(noMetersName);
            return;
        }
        lines.values().forEach(line -> log.info("{}", line));
    }

    /**
     * Emits a JVM memory gauge split by Micrometer's {@code area} tag: a total line plus per-area
     * ({@code heap} / {@code nonheap}) lines, all under the same Prometheus metric name.
     */
    protected void loggingJVMMemory(String metricType) {
        emit(metricType, "", DF.format(metricsExtractor().apply(metricType, null)));
        emit(metricType, label("area", "heap"), DF.format(metricsExtractor().apply(metricType, List.of("area:heap"))));
        emit(metricType, label("area", "nonheap"),
                DF.format(metricsExtractor().apply(metricType, List.of("area:nonheap"))));
    }

    protected void loggingMetric(String metricType, DecimalFormat decimalFormat) {
        emit(metricType, "", decimalFormat.format(metricsExtractor().apply(metricType, null)));
    }

    protected void loggingMetricKeys(List<Pair<String, List<String>>> metricKeys, DecimalFormat decimalFormat) {
        for (Pair<String, List<String>> pair : metricKeys) {
            emit(pair.getLeft(), "", decimalFormat.format(metricsExtractor().apply(pair.getLeft(), pair.getRight())));
        }
    }

    @Scheduled(fixedDelayString = "${creed.metrics.jvm-memory.interval-ms:10000}",
            initialDelayString = "${creed.metrics.jvm-memory.initial-delay-ms:1000}")
    public void logMetrics() {
        StopWatch stopWatch = new StopWatch("metrics");
        stopWatch.start("metrics");
        loggingJVMMetrics();
        loggingTomcatMetrics();
        loggingHttpBucketMetrics();
        loggingTomcatRequestMetrics();
        loggingThreadPoolMetrics();
        loggingHttpClientPoolMetrics();
        self.initTaskExecutor();
        stopWatch.stop();
        log.info(stopWatch.prettyPrint(TimeUnit.MILLISECONDS));
    }

    protected BiFunction<String, List<String>, Double> metricsExtractor() {
        return (metricType, tags) ->
                Optional.ofNullable(metricsEndpoint.metric(metricType, tags))
                        .map(MetricsEndpoint.MetricDescriptor::getMeasurements)
                        .orElse(Collections.emptyList())
                        .stream().findFirst().map(MetricsEndpoint.Sample::getValue)
                        .orElse(0.0);
    }
}
