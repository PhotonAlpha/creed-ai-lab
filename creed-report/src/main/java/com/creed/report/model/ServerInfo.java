package com.creed.report.model;

public record ServerInfo(
        String host,
        String ip,
        String app,
        String country,
        String service,
        String env,
        String zone,
        String slot
) {
}
