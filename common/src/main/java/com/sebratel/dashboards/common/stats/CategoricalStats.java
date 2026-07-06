package com.sebratel.dashboards.common.stats;

import java.util.List;
import java.util.Map;

public record CategoricalStats(
        long distinctCount,
        String mode,
        double shannonEntropy,
        Map<String, Long> topFrequencies,
        List<CategoryShare> distribution,
        DurationSummary durationSummary
) {
    public record CategoryShare(String label, long count, double percentage) {
    }

    /**
     * Present only when the column's values look like "HH:MM:SS" durations. Aggregated over ALL
     * rows (not just the top-N distribution), so totals are accurate even for the long tail.
     */
    public record DurationSummary(long totalSeconds, long count, double meanSeconds) {
    }
}
