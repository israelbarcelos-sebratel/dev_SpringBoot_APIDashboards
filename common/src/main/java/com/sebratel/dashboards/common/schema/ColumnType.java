package com.sebratel.dashboards.common.schema;

/**
 * Semantic classification of a column, derived from its SQL type and
 * (for VARCHAR/TEXT columns) observed cardinality. Drives which charts
 * the frontend renders for a given column — never hardcode a column name.
 */
public enum ColumnType {
    NUMERIC,
    INTEGER,
    DATETIME,
    BOOLEAN,
    CATEGORICAL,
    /** Near-unique integer key (PK, protocol/code number). Not a measure — never charted as one. */
    IDENTIFIER,
    TEXT
}
