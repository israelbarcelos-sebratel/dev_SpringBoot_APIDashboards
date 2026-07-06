package com.sebratel.dashboards.common.web;

import com.sebratel.dashboards.common.config.TableGroupProperties;
import com.sebratel.dashboards.common.dto.ColumnStats;
import com.sebratel.dashboards.common.dto.CorrelationResponse;
import com.sebratel.dashboards.common.dto.RowsPage;
import com.sebratel.dashboards.common.dto.StatsResponse;
import com.sebratel.dashboards.common.dto.TimeSeriesResponse;
import com.sebratel.dashboards.common.schema.ColumnMetadata;
import com.sebratel.dashboards.common.schema.ColumnType;
import com.sebratel.dashboards.common.schema.SchemaIntrospector;
import com.sebratel.dashboards.common.schema.TableSchema;
import com.sebratel.dashboards.common.schema.UnknownTableException;
import com.sebratel.dashboards.common.stats.CorrelationResult;
import com.sebratel.dashboards.common.stats.Insight;
import com.sebratel.dashboards.common.stats.InsightGenerator;
import com.sebratel.dashboards.common.stats.StatsEngine;
import com.sebratel.dashboards.common.stats.TimeSeriesResult;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Generic, schema-agnostic query layer shared by every dashboard service. All table/column
 * identifiers used to build SQL come from {@link SchemaIntrospector} results (never straight
 * from request params), so a request can only ever touch the tables the app was configured with.
 */
@Service
public class TableDataService {

    private final JdbcTemplate jdbcTemplate;
    private final SchemaIntrospector introspector;
    private final TableGroupProperties groupProperties;
    private final String dbSchema;
    private final Map<String, TableSchema> schemaCache = new ConcurrentHashMap<>();

    public TableDataService(JdbcTemplate jdbcTemplate,
                             SchemaIntrospector introspector,
                             TableGroupProperties groupProperties,
                             @Value("${app.db-schema}") String dbSchema) {
        this.jdbcTemplate = jdbcTemplate;
        this.introspector = introspector;
        this.groupProperties = groupProperties;
        this.dbSchema = dbSchema;
    }

    public List<String> listTables() {
        return groupProperties.tables();
    }

    public TableSchema getSchema(String table) {
        assertAllowed(table);
        // Introspection issues one query per column (cardinality + date-pattern sampling), which
        // would exhaust the HikariCP pool if repeated on every request, so cache it per table name.
        return schemaCache.computeIfAbsent(table, t -> introspector.introspect(dbSchema, t));
    }

    public RowsPage getRows(String table, int page, int size, String sortColumn, String sortDir,
                             Map<String, String> filters) {
        TableSchema schema = getSchema(table);
        Set<String> validColumns = columnNames(schema);

        String orderBy = validColumns.contains(sortColumn) ? sortColumn : schema.columns().get(0).name();
        String direction = "asc".equalsIgnoreCase(sortDir) ? "ASC" : "DESC";

        List<Object> params = new ArrayList<>();
        StringBuilder where = new StringBuilder();
        for (Map.Entry<String, String> f : filters.entrySet()) {
            if (!validColumns.contains(f.getKey()) || f.getValue() == null || f.getValue().isBlank()) continue;
            where.append(where.isEmpty() ? " WHERE " : " AND ");
            where.append('`').append(f.getKey()).append("` = ?");
            params.add(f.getValue());
        }

        String baseQuery = "FROM `" + table + "`" + where;
        long total = jdbcTemplate.queryForObject("SELECT COUNT(*) " + baseQuery, params.toArray(), Long.class);

        List<Object> pageParams = new ArrayList<>(params);
        pageParams.add(Math.max(size, 1));
        pageParams.add(Math.max(page, 0) * Math.max(size, 1));

        List<Map<String, Object>> rows = jdbcTemplate.queryForList(
                "SELECT * " + baseQuery + " ORDER BY `" + orderBy + "` " + direction + " LIMIT ? OFFSET ?",
                pageParams.toArray());

        return new RowsPage(rows, total, page, size);
    }

    public StatsResponse getStats(String table) {
        TableSchema schema = getSchema(table);
        List<ColumnStats> columnStats = new ArrayList<>();
        List<Insight> insights = new ArrayList<>();

        for (ColumnMetadata column : schema.columns()) {
            if (column.type() == ColumnType.NUMERIC || column.type() == ColumnType.INTEGER) {
                List<Double> values = jdbcTemplate.queryForList(
                        "SELECT `" + column.name() + "` FROM `" + table + "` WHERE `" + column.name() + "` IS NOT NULL",
                        Double.class);
                var stats = StatsEngine.descriptive(values);
                columnStats.add(new ColumnStats(column.name(), column.type(), stats, null));
                insights.addAll(InsightGenerator.fromDescriptive(column.name(), stats));
            } else if (column.type() == ColumnType.CATEGORICAL || column.type() == ColumnType.BOOLEAN) {
                List<String> values = jdbcTemplate.queryForList(
                        "SELECT `" + column.name() + "` FROM `" + table + "`", Object.class)
                        .stream().map(v -> v == null ? null : v.toString()).toList();
                var stats = StatsEngine.categorical(values);
                columnStats.add(new ColumnStats(column.name(), column.type(), null, stats));
                insights.addAll(InsightGenerator.fromCategorical(column.name(), stats));
            }
        }

        return new StatsResponse(table, schema.rowCount(), columnStats, insights);
    }

    public TimeSeriesResponse getTimeSeries(String table, String requestedColumn, String granularity) {
        TableSchema schema = getSchema(table);
        String dateColumn = resolveDateColumn(schema, requestedColumn);
        String format = switch (granularity) {
            case "day" -> "%Y-%m-%d";
            case "week" -> "%x-W%v";
            default -> "%Y-%m";
        };

        List<Map<String, Object>> rows = jdbcTemplate.queryForList(
                "SELECT DATE_FORMAT(`" + dateColumn + "`, ?) AS period, COUNT(*) AS cnt " +
                        "FROM `" + table + "` WHERE `" + dateColumn + "` IS NOT NULL " +
                        "GROUP BY period ORDER BY period ASC",
                format);

        List<String> labels = rows.stream().map(r -> String.valueOf(r.get("period"))).toList();
        List<Long> counts = rows.stream().map(r -> ((Number) r.get("cnt")).longValue()).toList();

        TimeSeriesResult series = StatsEngine.timeSeries(granularity, labels, counts);
        List<Insight> insights = InsightGenerator.fromTimeSeries(table + "." + dateColumn, series);

        return new TimeSeriesResponse(table, dateColumn, series, insights);
    }

    public CorrelationResponse getCorrelations(String table) {
        TableSchema schema = getSchema(table);
        List<ColumnMetadata> numericColumns = schema.columns().stream()
                .filter(c -> c.type() == ColumnType.NUMERIC || c.type() == ColumnType.INTEGER)
                .toList();

        if (numericColumns.size() < 2) {
            return new CorrelationResponse(table, new CorrelationResult(List.of(), new double[0][0]), List.of());
        }

        String columnList = numericColumns.stream()
                .map(c -> "`" + c.name() + "`")
                .reduce((a, b) -> a + ", " + b).orElse("");

        List<Map<String, Object>> rows = jdbcTemplate.queryForList(
                "SELECT " + columnList + " FROM `" + table + "`");

        LinkedHashMap<String, List<Double>> columnValues = new LinkedHashMap<>();
        for (ColumnMetadata col : numericColumns) {
            List<Double> values = rows.stream()
                    .map(r -> r.get(col.name()))
                    .filter(v -> v != null)
                    .map(v -> ((Number) v).doubleValue())
                    .toList();
            columnValues.put(col.name(), values);
        }

        CorrelationResult correlation = StatsEngine.pearsonCorrelation(columnValues);
        List<Insight> insights = InsightGenerator.fromCorrelation(correlation);

        return new CorrelationResponse(table, correlation, insights);
    }

    public List<Insight> getInsights(String table) {
        List<Insight> insights = new ArrayList<>();
        TableSchema schema = getSchema(table);

        boolean hasDateColumn = schema.columns().stream().anyMatch(c -> c.type() == ColumnType.DATETIME);
        if (hasDateColumn) {
            insights.addAll(getTimeSeries(table, null, "month").insights());
        }
        insights.addAll(getStats(table).insights());
        insights.addAll(getCorrelations(table).insights());
        return insights;
    }

    private String resolveDateColumn(TableSchema schema, String requestedColumn) {
        if (requestedColumn != null) {
            boolean valid = schema.columns().stream()
                    .anyMatch(c -> c.name().equals(requestedColumn) && c.type() == ColumnType.DATETIME);
            if (valid) return requestedColumn;
        }
        return schema.columns().stream()
                .filter(c -> c.type() == ColumnType.DATETIME)
                .findFirst()
                .map(ColumnMetadata::name)
                .orElseThrow(() -> new IllegalStateException("Table " + schema.tableName() + " has no date/time column"));
    }

    private Set<String> columnNames(TableSchema schema) {
        return schema.columns().stream().map(ColumnMetadata::name).collect(java.util.stream.Collectors.toSet());
    }

    private void assertAllowed(String table) {
        if (!groupProperties.tables().contains(table)) {
            throw new UnknownTableException(table);
        }
    }
}
