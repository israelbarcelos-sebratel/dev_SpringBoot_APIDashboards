package com.sebratel.dashboards.common.stats;

import java.util.List;
import java.util.Map;

public record CategoricalStats(
        long distinctCount,
        String mode,
        double shannonEntropy,
        Map<String, Long> topFrequencies,
        List<CategoryShare> distribution
) {
    public record CategoryShare(String label, long count, double percentage) {
    }
}
