package com.sebratel.dashboards.common.dto;

import com.sebratel.dashboards.common.stats.Insight;
import com.sebratel.dashboards.common.stats.TimeSeriesResult;

import java.util.List;

public record TimeSeriesResponse(
        String table,
        String column,
        TimeSeriesResult series,
        List<Insight> insights
) {
}
