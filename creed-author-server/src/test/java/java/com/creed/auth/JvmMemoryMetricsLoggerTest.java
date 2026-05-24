package java.com.creed.auth;

import com.creed.auth.CreedAuthorServerApplication;
import com.creed.auth.metrics.JvmMemoryMetricsLogger;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.junit.jupiter.api.Test;
import org.springframework.boot.actuate.metrics.MetricsEndpoint;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.TestPropertySource;

/**
 * @author EthanCao
 * @description creed-ai-lab
 * @date 2026-05-20T16
 */
@SpringBootTest(classes = CreedAuthorServerApplication.class)
@TestPropertySource(properties = {"spring.config.location=classpath:/"})
@Slf4j
class JvmMemoryMetricsLoggerTest {
    @Resource
    JvmMemoryMetricsLogger metricsLogger;

    @Test
    void listKeysTest() {
        MetricsEndpoint.MetricNamesDescriptor metricNamesDescriptor = metricsLogger.listKeys();
        for (String name : metricNamesDescriptor.getNames()) {
            log.info("metricName:{}", name);
        }
    }
    @Test
    void loadJVMMetricsTest() {
        metricsLogger.loggingJVMMetrics();

    }

    @Test
    void loggingTomcatMetricsTest() {
        metricsLogger.loggingHttpRequestActiveBucketMetrics();
        metricsLogger.loggingHttpBucketMetrics();
    }

    @Test
    void loggingPrometheusRegistry() {

    }
}
