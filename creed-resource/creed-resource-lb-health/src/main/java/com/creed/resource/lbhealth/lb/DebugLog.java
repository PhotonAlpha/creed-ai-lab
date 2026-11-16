package com.creed.resource.lbhealth.lb;

import java.time.LocalTime;
import java.time.format.DateTimeFormatter;

/** Wall-clock stamp shared by the {@code [REFETCH]} / {@code [HC]} / {@code [HC-STREAM]} lines so they line up. */
final class DebugLog {

    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("HH:mm:ss.SSS");

    private DebugLog() {
    }

    static String now() {
        return LocalTime.now().format(TIME);
    }
}
