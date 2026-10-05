package com.creed.resource.envmatrix.domain;

/**
 * A server as the AES page lists it: one distinct {@code (appSystem, host, ip)} out of
 * {@code env_endpoint}, however many services, ports and schemes that address carries.
 */
public record EnvServer(String appSystem, String host, String ip, String envInstance, String instance) {
}
