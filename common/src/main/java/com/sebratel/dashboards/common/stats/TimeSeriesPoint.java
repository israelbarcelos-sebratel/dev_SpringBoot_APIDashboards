package com.sebratel.dashboards.common.stats;

public record TimeSeriesPoint(String period, long count, Double movingAverage) {
}
