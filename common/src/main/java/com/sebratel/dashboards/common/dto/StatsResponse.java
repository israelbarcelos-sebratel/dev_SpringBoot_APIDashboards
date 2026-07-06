package com.sebratel.dashboards.common.dto;

import com.sebratel.dashboards.common.stats.Insight;

import java.util.List;

public record StatsResponse(
        String table,
        long rowCount,
        List<ColumnStats> columns,
        List<Insight> insights
) {
}
