package com.sebratel.dashboards.common.stats;

/**
 * Rolling-window volumes used to compute the period-over-period and year-over-year variation.
 * The comparison is made between two windows of the SAME length ("últimos N dias" vs "os N dias
 * anteriores"), so a partial current calendar period no longer looks like a huge drop against a
 * full previous one. YoY counts are null when there is no history in the prior year to compare to.
 */
public record WindowComparison(
        long currentCount,
        long previousCount,
        int windowDays,
        Long yoyCurrentCount,
        Long yoyPreviousCount
) {
}
