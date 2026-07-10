package com.sebratel.dashboards.common.dto;

import java.util.List;

/**
 * One line of a breakdown time series: the raw category value ({@code key}, e.g. "1".."5" for a
 * satisfaction score) and its per-period counts, aligned index-for-index with the response's
 * {@code periods} list (zero-filled where a period has no rows for this category).
 */
public record BreakdownSeries(
        String key,
        List<Long> counts
) {
}
