package com.sebratel.dashboards.common.stats;

import java.util.List;

public record TimeSeriesResult(
        String granularity,
        List<TimeSeriesPoint> points,
        Double trendSlope,
        Double periodOverPeriodChangePct,
        Double yearOverYearChangePct
) {
}
