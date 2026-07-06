package com.sebratel.dashboards.common.dto;

import java.util.List;
import java.util.Map;

public record RowsPage(
        List<Map<String, Object>> rows,
        long totalCount,
        int page,
        int size
) {
}
