package com.sebratel.dashboards.common.stats;

public record HistogramBin(double rangeStart, double rangeEnd, long count) {
}
