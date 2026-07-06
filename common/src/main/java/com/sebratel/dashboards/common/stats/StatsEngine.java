package com.sebratel.dashboards.common.stats;

import java.util.*;
import java.util.stream.Collectors;

/**
 * Pure-Java statistics engine (no external stats library) operating over data already
 * fetched from the database. Every method is data-driven — none of it assumes a
 * particular table or column name, so the same engine backs every dashboard view.
 */
public final class StatsEngine {

    private StatsEngine() {
    }

    // ---------------------------------------------------------------- descriptive

    public static DescriptiveStats descriptive(List<Double> rawValues) {
        List<Double> values = rawValues.stream().filter(Objects::nonNull).sorted().toList();
        int n = values.size();
        if (n == 0) {
            return new DescriptiveStats(0, 0, 0, 0, 0, null, 0, 0, 0, 0, 0, 0, 0, 0, 0,
                    new OutlierResult(0, 0, List.of()), List.of());
        }

        double min = values.get(0);
        double max = values.get(n - 1);
        double mean = values.stream().mapToDouble(Double::doubleValue).average().orElse(0);
        double median = percentile(values, 50);
        double q1 = percentile(values, 25);
        double q3 = percentile(values, 75);
        double iqr = q3 - q1;
        double range = max - min;

        double variance = n > 1
                ? values.stream().mapToDouble(v -> Math.pow(v - mean, 2)).sum() / (n - 1)
                : 0;
        double stdDev = Math.sqrt(variance);
        double coefficientOfVariation = mean != 0 ? stdDev / Math.abs(mean) : 0;

        double skewness = skewness(values, mean, stdDev);
        double kurtosis = kurtosis(values, mean, stdDev);
        Double mode = mode(values);
        OutlierResult outliers = detectOutliers(values, q1, q3, mean, stdDev);
        List<HistogramBin> histogram = histogram(values, min, max);

        return new DescriptiveStats(n, min, max, mean, median, mode, variance, stdDev, range,
                q1, q3, iqr, coefficientOfVariation, skewness, kurtosis, outliers, histogram);
    }

    /** Sturges' rule for bin count — a reasonable default without extra config for arbitrary columns. */
    private static List<HistogramBin> histogram(List<Double> sorted, double min, double max) {
        int n = sorted.size();
        if (n == 0 || max == min) {
            return n == 0 ? List.of() : List.of(new HistogramBin(min, max, n));
        }
        int binCount = Math.max(1, (int) Math.ceil(Math.log(n) / Math.log(2)) + 1);
        double width = (max - min) / binCount;

        long[] counts = new long[binCount];
        for (double v : sorted) {
            int idx = (int) Math.floor((v - min) / width);
            if (idx >= binCount) idx = binCount - 1;
            if (idx < 0) idx = 0;
            counts[idx]++;
        }

        List<HistogramBin> bins = new ArrayList<>(binCount);
        for (int i = 0; i < binCount; i++) {
            bins.add(new HistogramBin(min + i * width, min + (i + 1) * width, counts[i]));
        }
        return bins;
    }

    private static double percentile(List<Double> sorted, double p) {
        int n = sorted.size();
        if (n == 1) return sorted.get(0);
        double rank = (p / 100.0) * (n - 1);
        int lower = (int) Math.floor(rank);
        int upper = (int) Math.ceil(rank);
        if (lower == upper) return sorted.get(lower);
        double fraction = rank - lower;
        return sorted.get(lower) + fraction * (sorted.get(upper) - sorted.get(lower));
    }

    private static Double mode(List<Double> sorted) {
        Map<Double, Long> freq = sorted.stream()
                .collect(Collectors.groupingBy(v -> v, Collectors.counting()));
        Map.Entry<Double, Long> best = freq.entrySet().stream()
                .max(Map.Entry.comparingByValue())
                .orElse(null);
        if (best == null || best.getValue() <= 1) return null;
        return best.getKey();
    }

    private static double skewness(List<Double> values, double mean, double stdDev) {
        int n = values.size();
        if (n < 3 || stdDev == 0) return 0;
        double sum = values.stream().mapToDouble(v -> Math.pow((v - mean) / stdDev, 3)).sum();
        return ((double) n / ((n - 1.0) * (n - 2.0))) * sum;
    }

    private static double kurtosis(List<Double> values, double mean, double stdDev) {
        int n = values.size();
        if (n < 4 || stdDev == 0) return 0;
        double sum = values.stream().mapToDouble(v -> Math.pow((v - mean) / stdDev, 4)).sum();
        double term1 = ((double) n * (n + 1) / ((n - 1.0) * (n - 2.0) * (n - 3.0))) * sum;
        double term2 = (3.0 * Math.pow(n - 1, 2)) / ((n - 2.0) * (n - 3.0));
        return term1 - term2;
    }

    private static OutlierResult detectOutliers(List<Double> sorted, double q1, double q3, double mean, double stdDev) {
        double iqr = q3 - q1;
        double lowerFence = q1 - 1.5 * iqr;
        double upperFence = q3 + 1.5 * iqr;

        List<Double> iqrOutliers = sorted.stream()
                .filter(v -> v < lowerFence || v > upperFence)
                .toList();

        long zScoreOutliers = stdDev == 0 ? 0 : sorted.stream()
                .filter(v -> Math.abs((v - mean) / stdDev) > 3)
                .count();

        List<Double> sample = iqrOutliers.stream().limit(20).toList();
        return new OutlierResult(iqrOutliers.size(), zScoreOutliers, sample);
    }

    // ---------------------------------------------------------------- categorical

    public static CategoricalStats categorical(List<String> rawValues) {
        List<String> values = rawValues.stream()
                .map(v -> v == null || v.isBlank() ? "(vazio)" : v)
                .toList();
        long total = values.size();

        Map<String, Long> freq = values.stream()
                .collect(Collectors.groupingBy(v -> v, LinkedHashMap::new, Collectors.counting()));

        String mode = freq.entrySet().stream()
                .max(Map.Entry.comparingByValue())
                .map(Map.Entry::getKey)
                .orElse(null);

        double entropy = 0;
        for (long count : freq.values()) {
            double p = (double) count / total;
            entropy -= p * (Math.log(p) / Math.log(2));
        }

        List<Map.Entry<String, Long>> sortedFreq = freq.entrySet().stream()
                .sorted(Map.Entry.<String, Long>comparingByValue().reversed())
                .toList();

        int topN = 20;
        List<CategoricalStats.CategoryShare> distribution = sortedFreq.stream()
                .limit(topN)
                .map(e -> new CategoricalStats.CategoryShare(e.getKey(), e.getValue(), 100.0 * e.getValue() / total))
                .collect(Collectors.toCollection(ArrayList::new));

        if (sortedFreq.size() > topN) {
            long othersCount = sortedFreq.stream().skip(topN).mapToLong(Map.Entry::getValue).sum();
            distribution.add(new CategoricalStats.CategoryShare("(outros)", othersCount, 100.0 * othersCount / total));
        }

        Map<String, Long> topFrequencies = sortedFreq.stream()
                .limit(10)
                .collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue, (a, b) -> a, LinkedHashMap::new));

        return new CategoricalStats(freq.size(), mode, entropy, topFrequencies, distribution);
    }

    // ---------------------------------------------------------------- time series

    /** counts must already be ordered chronologically by the caller (grouped/aggregated in SQL). */
    public static TimeSeriesResult timeSeries(String granularity, List<String> periodLabels, List<Long> counts) {
        int n = counts.size();
        List<TimeSeriesPoint> points = new ArrayList<>(n);
        int window = 3;

        for (int i = 0; i < n; i++) {
            Double movingAverage = null;
            if (i >= window - 1) {
                double sum = 0;
                for (int j = i - window + 1; j <= i; j++) sum += counts.get(j);
                movingAverage = sum / window;
            }
            points.add(new TimeSeriesPoint(periodLabels.get(i), counts.get(i), movingAverage));
        }

        Double trendSlope = n >= 2 ? linearRegressionSlope(counts) : null;
        Double periodOverPeriodChangePct = n >= 2 ? percentChange(counts.get(n - 2), counts.get(n - 1)) : null;
        Double yearOverYearChangePct = null;
        if ("month".equals(granularity) && n >= 13) {
            yearOverYearChangePct = percentChange(counts.get(n - 13), counts.get(n - 1));
        }

        return new TimeSeriesResult(granularity, points, trendSlope, periodOverPeriodChangePct, yearOverYearChangePct);
    }

    private static Double percentChange(long previous, long current) {
        if (previous == 0) return current == 0 ? 0.0 : null;
        return 100.0 * (current - previous) / previous;
    }

    private static double linearRegressionSlope(List<Long> series) {
        int n = series.size();
        double sumX = 0, sumY = 0, sumXY = 0, sumXX = 0;
        for (int i = 0; i < n; i++) {
            sumX += i;
            sumY += series.get(i);
            sumXY += (double) i * series.get(i);
            sumXX += (double) i * i;
        }
        double denominator = n * sumXX - sumX * sumX;
        if (denominator == 0) return 0;
        return (n * sumXY - sumX * sumY) / denominator;
    }

    // ---------------------------------------------------------------- correlation

    public static CorrelationResult pearsonCorrelation(LinkedHashMap<String, List<Double>> columns) {
        List<String> names = new ArrayList<>(columns.keySet());
        int k = names.size();
        double[][] matrix = new double[k][k];

        for (int i = 0; i < k; i++) {
            for (int j = 0; j < k; j++) {
                matrix[i][j] = pearson(columns.get(names.get(i)), columns.get(names.get(j)));
            }
        }
        return new CorrelationResult(names, matrix);
    }

    private static double pearson(List<Double> xs, List<Double> ys) {
        int n = Math.min(xs.size(), ys.size());
        if (n < 2) return 0;
        double meanX = xs.stream().mapToDouble(Double::doubleValue).average().orElse(0);
        double meanY = ys.stream().mapToDouble(Double::doubleValue).average().orElse(0);

        double numerator = 0, sumSqX = 0, sumSqY = 0;
        for (int i = 0; i < n; i++) {
            double dx = xs.get(i) - meanX;
            double dy = ys.get(i) - meanY;
            numerator += dx * dy;
            sumSqX += dx * dx;
            sumSqY += dy * dy;
        }
        double denominator = Math.sqrt(sumSqX * sumSqY);
        if (denominator == 0) return 0;
        return numerator / denominator;
    }
}
