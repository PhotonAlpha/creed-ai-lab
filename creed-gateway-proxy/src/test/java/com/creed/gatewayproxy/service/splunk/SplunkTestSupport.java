package com.creed.gatewayproxy.service.splunk;

import java.time.Duration;
import java.util.List;

import com.creed.gatewayproxy.config.SplunkProperties;

/** Builds the properties records the tests need without a Spring context. */
final class SplunkTestSupport {

    private SplunkTestSupport() {
    }

    static SplunkProperties.Target target(String id, String loginUrl, String tunnel) {
        return new SplunkProperties.Target(id, id + " label", loginUrl, "admin", null, null, null, tunnel, false);
    }

    static SplunkProperties splunk(boolean enabled, boolean tlsInsecure, String caFile, String blockWindows,
                                   List<SplunkProperties.Target> targets) {
        return new SplunkProperties(enabled, null, null, null, true, Duration.ofSeconds(2), Duration.ofSeconds(3),
                tlsInsecure, caFile, null, targets, new SplunkProperties.Block(blockWindows, "Asia/Shanghai"),
                new SplunkProperties.Audit(null, null, null, null));
    }
}
