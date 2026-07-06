package com.sebratel.dashboards.common.dto;

import com.sebratel.dashboards.common.stats.CorrelationResult;
import com.sebratel.dashboards.common.stats.Insight;

import java.util.List;

public record CorrelationResponse(
        String table,
        CorrelationResult correlation,
        List<Insight> insights
) {
}
