package com.creed.resource.envmatrix.service;

import org.springframework.util.StringUtils;

import java.util.List;

/**
 * What the result list is narrowed to. Empty lists mean "any". A record's env instance is not a
 * column of its own: it matches when an endpoint row for the same {@code (appSystem, host, ip)}
 * carries one of the listed env instances.
 */
public record AesRecordQuery(String appSystem, List<String> envInstances, List<String> hosts, List<String> propertyKeys) {

    public AesRecordQuery {
        appSystem = StringUtils.hasText(appSystem) ? appSystem.strip() : null;
        envInstances = clean(envInstances);
        hosts = clean(hosts);
        propertyKeys = clean(propertyKeys);
    }

    private static List<String> clean(List<String> values) {
        return values == null ? List.of()
                : values.stream().filter(StringUtils::hasText).map(String::strip).distinct().toList();
    }
}
