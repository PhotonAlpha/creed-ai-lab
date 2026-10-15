package com.creed.resource.veracode.domain;

import java.util.Locale;

/** Veracode's severity scale, for ordering only - the report's own wording is what gets shown. */
public final class Severity {

    private Severity() {
    }

    /** Very High 5 ... Informational 0; anything unrecognised sorts last. */
    public static int rank(String severity) {
        if (severity == null) {
            return -1;
        }
        return switch (severity.trim().toLowerCase(Locale.ROOT).replaceAll("[\\s_-]+", " ")) {
            case "very high", "critical" -> 5;
            case "high" -> 4;
            case "medium" -> 3;
            case "low" -> 2;
            case "very low" -> 1;
            case "informational", "info" -> 0;
            default -> -1;
        };
    }
}
