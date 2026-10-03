package com.creed.resource.envmatrix.api.dto;

/**
 * The current code, for the page's rotating display.
 *
 * @param secondsRemaining how long this code has left, 1..period
 */
public record TotpCodeDto(String code, long step, int secondsRemaining, int periodSeconds, long serverTimeMillis) {
}
