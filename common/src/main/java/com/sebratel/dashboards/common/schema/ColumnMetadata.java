package com.sebratel.dashboards.common.schema;

public record ColumnMetadata(
        String name,
        String sqlType,
        boolean nullable,
        boolean primaryKey,
        ColumnType type,
        Long distinctCount
) {
}
