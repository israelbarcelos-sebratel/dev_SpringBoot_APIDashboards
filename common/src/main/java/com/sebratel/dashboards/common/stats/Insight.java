package com.sebratel.dashboards.common.stats;

public record Insight(
        String type,
        String severity,
        String message,
        Double value,
        String column,
        String sentiment
) {
    /** Whether an insight reads as good, bad or merely descriptive — drives the frontend indicator. */
    public static final String POSITIVE = "positive";
    public static final String NEGATIVE = "negative";
    public static final String NEUTRAL = "neutral";

    public static Insight info(String type, String message, Double value) {
        return new Insight(type, "info", message, value, null, NEUTRAL);
    }

    public static Insight warning(String type, String message, Double value) {
        return new Insight(type, "warning", message, value, null, NEGATIVE);
    }

    /** Column-scoped variants so the frontend can drop insights for columns it doesn't display. */
    public static Insight info(String type, String message, Double value, String column) {
        return new Insight(type, "info", message, value, column, NEUTRAL);
    }

    public static Insight warning(String type, String message, Double value, String column) {
        return new Insight(type, "warning", message, value, column, NEGATIVE);
    }

    /** Full variant for insights whose good/bad reading depends on the value (growth vs. decline). */
    public static Insight info(String type, String message, Double value, String column, String sentiment) {
        return new Insight(type, "info", message, value, column, sentiment);
    }
}
