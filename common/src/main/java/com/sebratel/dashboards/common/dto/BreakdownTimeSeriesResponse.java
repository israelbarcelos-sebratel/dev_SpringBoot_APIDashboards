package com.sebratel.dashboards.common.dto;

import java.util.List;

/**
 * A time series split by a categorical column: for each period on the shared X axis (time), one
 * count per distinct category value. Backs the satisfaction-over-time chart, where each category
 * (satisfaction vote) is drawn as its own line so the mix can be read at a glance over time.
 */
public record BreakdownTimeSeriesResponse(
        String table,
        String dateColumn,
        String breakdownColumn,
        String granularity,
        List<String> periods,
        List<BreakdownSeries> series
) {
}
