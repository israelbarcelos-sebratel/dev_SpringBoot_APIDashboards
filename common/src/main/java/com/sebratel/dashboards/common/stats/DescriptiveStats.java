package com.sebratel.dashboards.common.stats;

import java.util.List;

/** Advanced descriptive statistics for a single numeric column: central tendency, dispersion and shape. */
public record DescriptiveStats(
        long count,
        double min,
        double max,
        double mean,
        double median,
        Double mode,
        double variance,
        double stdDev,
        double range,
        double q1,
        double q3,
        double iqr,
        double coefficientOfVariation,
        double skewness,
        double kurtosis,
        OutlierResult outliers,
        List<HistogramBin> histogram
) {
}
