package com.sebratel.dashboards.common.schema;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.regex.Pattern;

/**
 * Discovers table structure at runtime via INFORMATION_SCHEMA instead of relying on
 * hardcoded column names. Table/column identifiers used in raw SQL always come from
 * this introspection (never directly from request input), which is what keeps the
 * generic query building below safe from SQL injection.
 */
@Component
public class SchemaIntrospector {

    private static final long CATEGORICAL_MAX_DISTINCT = 50;
    private static final double CATEGORICAL_MAX_RATIO = 0.2;
    /** Integer columns this close to fully unique are identifiers (PK, protocol/code), not measures. */
    private static final double IDENTIFIER_MIN_UNIQUE_RATIO = 0.9;
    /** Integers larger than this are protocol/phone/document numbers, not quantities to chart. */
    private static final long IDENTIFIER_MAGNITUDE_THRESHOLD = 10_000_000_000L;
    private static final int DATE_SAMPLE_SIZE = 20;
    private static final double DATE_SAMPLE_MATCH_RATIO = 0.8;
    private static final Pattern DATE_LIKE_PATTERN =
            Pattern.compile("^\\d{4}-\\d{2}-\\d{2}([ T]\\d{2}:\\d{2}:\\d{2})?$");

    private final JdbcTemplate jdbcTemplate;

    public SchemaIntrospector(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public TableSchema introspect(String schemaName, String tableName) {
        long rowCount = countRows(tableName);

        List<ColumnMetadata> columns = jdbcTemplate.query(
                """
                SELECT COLUMN_NAME, DATA_TYPE, COLUMN_TYPE, IS_NULLABLE, COLUMN_KEY
                FROM INFORMATION_SCHEMA.COLUMNS
                WHERE TABLE_SCHEMA = ? AND TABLE_NAME = ?
                ORDER BY ORDINAL_POSITION
                """,
                (rs, rowNum) -> {
                    String columnName = rs.getString("COLUMN_NAME");
                    String dataType = rs.getString("DATA_TYPE");
                    String columnType = rs.getString("COLUMN_TYPE");
                    boolean nullable = "YES".equalsIgnoreCase(rs.getString("IS_NULLABLE"));
                    boolean primaryKey = "PRI".equalsIgnoreCase(rs.getString("COLUMN_KEY"));

                    Long distinctCount = null;
                    ColumnType type = classifyByDataType(dataType, columnType);
                    if (type == ColumnType.CATEGORICAL || type == ColumnType.TEXT) {
                        if (looksLikeDateColumn(tableName, columnName)) {
                            type = ColumnType.DATETIME;
                        } else {
                            distinctCount = countDistinct(tableName, columnName);
                            type = refineStringType(distinctCount, rowCount);
                        }
                    } else if (type == ColumnType.INTEGER) {
                        // An integer column is only a real "measure" if charting it makes sense.
                        // Codes/enums (few distinct values, e.g. status) → CATEGORICAL;
                        // PKs, near-unique or very large integers (codigo, protocolo, cpf, phone) →
                        // IDENTIFIER, since histograms/box plots/correlations over ids are meaningless.
                        distinctCount = countDistinct(tableName, columnName);
                        if (distinctCount > 0 && distinctCount <= CATEGORICAL_MAX_DISTINCT) {
                            type = ColumnType.CATEGORICAL;
                        } else if (isIdentifier(primaryKey, distinctCount, rowCount)
                                || hasIdentifierMagnitude(tableName, columnName)) {
                            type = ColumnType.IDENTIFIER;
                        }
                    }

                    return new ColumnMetadata(columnName, columnType, nullable, primaryKey, type, distinctCount);
                },
                schemaName, tableName
        );

        return new TableSchema(tableName, rowCount, columns);
    }

    private ColumnType classifyByDataType(String dataType, String columnType) {
        String dt = dataType.toLowerCase();
        return switch (dt) {
            case "tinyint" -> columnType.toLowerCase().contains("tinyint(1)") ? ColumnType.BOOLEAN : ColumnType.INTEGER;
            case "bit", "boolean" -> ColumnType.BOOLEAN;
            case "smallint", "mediumint", "int", "integer", "bigint" -> ColumnType.INTEGER;
            case "decimal", "numeric", "float", "double" -> ColumnType.NUMERIC;
            case "date", "datetime", "timestamp", "time", "year" -> ColumnType.DATETIME;
            case "char", "varchar", "text", "tinytext", "mediumtext", "longtext", "enum", "set" -> ColumnType.CATEGORICAL;
            default -> ColumnType.TEXT;
        };
    }

    private boolean isIdentifier(boolean primaryKey, long distinctCount, long rowCount) {
        if (primaryKey) {
            return true;
        }
        if (rowCount == 0) {
            return false;
        }
        return (double) distinctCount / rowCount >= IDENTIFIER_MIN_UNIQUE_RATIO;
    }

    /** True when the largest magnitude value is far bigger than any plausible measure/count. */
    private boolean hasIdentifierMagnitude(String tableName, String columnName) {
        Long maxAbs = jdbcTemplate.queryForObject(
                "SELECT MAX(ABS(`" + columnName + "`)) FROM `" + tableName + "`", Long.class);
        return maxAbs != null && maxAbs >= IDENTIFIER_MAGNITUDE_THRESHOLD;
    }

    /** VARCHAR/TEXT columns start as CATEGORICAL; demote to TEXT if cardinality is too high to chart. */
    private ColumnType refineStringType(long distinctCount, long rowCount) {
        if (rowCount == 0) {
            return distinctCount <= CATEGORICAL_MAX_DISTINCT ? ColumnType.CATEGORICAL : ColumnType.TEXT;
        }
        double ratio = (double) distinctCount / rowCount;
        boolean lowCardinality = distinctCount <= CATEGORICAL_MAX_DISTINCT || ratio <= CATEGORICAL_MAX_RATIO;
        return lowCardinality ? ColumnType.CATEGORICAL : ColumnType.TEXT;
    }

    /**
     * Some source tables store dates as VARCHAR ("2026-07-06 09:28:11") instead of a native
     * DATE/DATETIME column. Sampling values lets time-series/wave charts still pick these up.
     */
    private boolean looksLikeDateColumn(String tableName, String columnName) {
        List<String> samples = jdbcTemplate.query(
                "SELECT `" + columnName + "` AS v FROM `" + tableName + "` WHERE `" + columnName + "` IS NOT NULL LIMIT " + DATE_SAMPLE_SIZE,
                (rs, rowNum) -> rs.getString("v"));
        if (samples.isEmpty()) {
            return false;
        }
        long matches = samples.stream()
                .filter(s -> DATE_LIKE_PATTERN.matcher(s.trim()).matches())
                .count();
        return matches >= Math.ceil(samples.size() * DATE_SAMPLE_MATCH_RATIO);
    }

    private long countRows(String tableName) {
        Long count = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM `" + tableName + "`", Long.class);
        return count == null ? 0 : count;
    }

    private long countDistinct(String tableName, String columnName) {
        Long count = jdbcTemplate.queryForObject(
                "SELECT COUNT(DISTINCT `" + columnName + "`) FROM `" + tableName + "`", Long.class);
        return count == null ? 0 : count;
    }
}
