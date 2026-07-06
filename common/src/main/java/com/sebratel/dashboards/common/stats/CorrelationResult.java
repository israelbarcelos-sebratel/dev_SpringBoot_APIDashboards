package com.sebratel.dashboards.common.stats;

import java.util.List;

public record CorrelationResult(
        List<String> columns,
        double[][] matrix
) {
}
