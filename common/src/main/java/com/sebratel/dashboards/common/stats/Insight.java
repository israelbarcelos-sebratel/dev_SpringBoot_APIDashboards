package com.sebratel.dashboards.common.stats;

public record Insight(
        String type,
        String severity,
        String message,
        Double value
) {
    public static Insight info(String type, String message, Double value) {
        return new Insight(type, "info", message, value);
    }

    public static Insight warning(String type, String message, Double value) {
        return new Insight(type, "warning", message, value);
    }
}
