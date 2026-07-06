package com.sebratel.dashboards.common.dto;

import com.sebratel.dashboards.common.schema.ColumnType;
import com.sebratel.dashboards.common.stats.CategoricalStats;
import com.sebratel.dashboards.common.stats.DescriptiveStats;

public record ColumnStats(
        String column,
        ColumnType type,
        DescriptiveStats descriptive,
        CategoricalStats categorical
) {
}
