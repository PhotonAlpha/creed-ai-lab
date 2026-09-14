package com.creed.simple.modcluster;

/**
 * What one proxy currently thinks of this node, as far as the active provider can tell.
 *
 * @param proxy      the configured {@code host:port} entry
 * @param registered whether the proxy is known to hold this node right now
 * @param detail     one line of evidence — the MCMP steps that were sent, or what the proxy reported
 */
public record ProxyState(String proxy, boolean registered, String detail) {
}
