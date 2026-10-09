package com.creed.resource.envmatrix.api.dto;

import java.util.List;

/**
 * The result list's filter options, each narrowed by the choices to its left: env instances by the
 * app system, hosts by app system + env instances, property keys by those and the hosts. Only values
 * some record actually carries — an option that matches nothing is not offered.
 */
public record AesRecordFilterOptions(List<String> envInstances, List<String> hosts, List<String> propertyKeys) {
}
