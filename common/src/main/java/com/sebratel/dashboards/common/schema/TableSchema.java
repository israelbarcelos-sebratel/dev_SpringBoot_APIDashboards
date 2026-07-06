package com.sebratel.dashboards.common.schema;

import java.util.List;

public record TableSchema(
        String tableName,
        long rowCount,
        List<ColumnMetadata> columns
) {
    public List<ColumnMetadata> columnsOfType(ColumnType type) {
        return columns.stream().filter(c -> c.type() == type).toList();
    }
}
