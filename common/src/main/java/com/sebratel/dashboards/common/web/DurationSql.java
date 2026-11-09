package com.sebratel.dashboards.common.web;

/** SQL fragments shared by duration calculations and averages. */
public final class DurationSql {
    private DurationSql() {}

    /** Turns SQL NULL, blank values and the Matrix placeholder "-" into SQL NULL. */
    public static String clean(String column) {
        return "NULLIF(NULLIF(TRIM(`" + column + "`), ''), '-')";
    }

    /** HH:MM:SS to seconds; malformed strings become NULL in MySQL/MariaDB. */
    public static String hmsSeconds(String column) {
        return "TIME_TO_SEC(" + clean(column) + ")";
    }

    /** Difference between two datetime fields; missing/placeholder endpoints become NULL. */
    public static String rangeSeconds(String startColumn, String endColumn) {
        return "TIMESTAMPDIFF(SECOND, " + clean(startColumn) + ", " + clean(endColumn) + ")";
    }

    /** AVG/COUNT input that preserves valid zero and ignores NULL and negative durations. */
    public static String nonNegative(String secondsExpression) {
        return "CASE WHEN (" + secondsExpression + ") >= 0 THEN (" + secondsExpression + ") END";
    }
}
