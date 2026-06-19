package com.creed.report.model;

import java.util.List;

/**
 * A property source as it appears in the assembled environment, in precedence order.
 *
 * @param name       the property source name (e.g. the config file URI)
 * @param type       the implementing class of the source
 * @param properties the properties contributed by this source
 */
public record PropertySourceView(String name, String type, List<PropertyEntry> properties) {
}
