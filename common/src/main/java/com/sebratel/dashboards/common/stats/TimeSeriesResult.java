package com.sebratel.dashboards.common.stats;

import java.util.List;

public record TimeSeriesResult(
        String granularity,
        List<TimeSeriesPoint> points,
        Double trendSlope,
        Double periodOverPeriodChangePct,
        Double yearOverYearChangePct,
        // Rolling-window volumes behind the variation: the last {@code windowDays} days vs the
        // {@code windowDays} days before them. The chart still uses calendar-aligned points above.
        long currentPeriodCount,
        long previousPeriodCount,
        int windowDays
) {
}
