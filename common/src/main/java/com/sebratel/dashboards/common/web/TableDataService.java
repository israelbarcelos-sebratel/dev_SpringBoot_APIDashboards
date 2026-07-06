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

        Where filter = buildWhere(schema, filters);
        String baseQuery = "FROM `" + table + "`" + filter.leading();
        long total = jdbcTemplate.queryForObject("SELECT COUNT(*) " + baseQuery, filter.params().toArray(), Long.class);

        List<Object> pageParams = new ArrayList<>(filter.params());
        pageParams.add(Math.max(size, 1));
        pageParams.add(Math.max(page, 0) * Math.max(size, 1));

        List<Map<String, Object>> rows = jdbcTemplate.queryForList(
                "SELECT * " + baseQuery + " ORDER BY `" + orderBy + "` " + direction + " LIMIT ? OFFSET ?",
                pageParams.toArray());

        return new RowsPage(rows, total, page, size);
    }

    public StatsResponse getStats(String table, Map<String, String> filters) {
        TableSchema schema = getSchema(table);
        Where filter = buildWhere(schema, filters);
        List<ColumnStats> columnStats = new ArrayList<>();
        List<Insight> insights = new ArrayList<>();

        long rowCount = filter.isEmpty()
                ? schema.rowCount()
                : jdbcTemplate.queryForObject(
                        "SELECT COUNT(*) FROM `" + table + "`" + filter.leading(), filter.params().toArray(), Long.class);

        for (ColumnMetadata column : schema.columns()) {
            if (column.type() == ColumnType.NUMERIC || column.type() == ColumnType.INTEGER) {
                List<Double> values = jdbcTemplate.queryForList(
                        "SELECT `" + column.name() + "` FROM `" + table + "` WHERE `" + column.name() + "` IS NOT NULL"
                                + filter.additional(),
                        filter.params().toArray(), Double.class);
                var stats = StatsEngine.descriptive(values);
                columnStats.add(new ColumnStats(column.name(), column.type(), stats, null));
                insights.addAll(InsightGenerator.fromDescriptive(column.name(), stats));
            } else if (column.type() == ColumnType.CATEGORICAL || column.type() == ColumnType.BOOLEAN) {
                List<String> values = jdbcTemplate.queryForList(
                        "SELECT `" + column.name() + "` FROM `" + table + "`" + filter.leading(),
                        filter.params().toArray(), Object.class)
                        .stream().map(v -> v == null ? null : v.toString()).toList();
                var stats = StatsEngine.categorical(values);
                columnStats.add(new ColumnStats(column.name(), column.type(), null, stats));
                insights.addAll(InsightGenerator.fromCategorical(column.name(), stats));
                insights.addAll(InsightGenerator.fromDuration(column.name(), stats));
            }
        }

        return new StatsResponse(table, rowCount, columnStats, insights);
    }

    public TimeSeriesResponse getTimeSeries(String table, String requestedColumn, String granularity,
                                             Map<String, String> filters) {
        TableSchema schema = getSchema(table);
        String dateColumn = resolveDateColumn(schema, requestedColumn);
        Where filter = buildWhere(schema, filters);
        String format = switch (granularity) {
            case "day" -> "%Y-%m-%d";
            case "week" -> "%x-W%v";
            default -> "%Y-%m";
        };

        List<Object> params = new ArrayList<>();
        params.add(format);
        params.addAll(filter.params());
        List<Map<String, Object>> rows = jdbcTemplate.queryForList(
                "SELECT DATE_FORMAT(`" + dateColumn + "`, ?) AS period, COUNT(*) AS cnt " +
                        "FROM `" + table + "` WHERE `" + dateColumn + "` IS NOT NULL " + filter.additional() +
                        " GROUP BY period ORDER BY period ASC",
                params.toArray());

        rows = dropNegligiblePeriods(rows);

        List<String> labels = rows.stream().map(r -> String.valueOf(r.get("period"))).toList();
        List<Long> counts = rows.stream().map(r -> ((Number) r.get("cnt")).longValue()).toList();

        TimeSeriesResult series = StatsEngine.timeSeries(granularity, labels, counts);
        // The series counts rows per period, so the insight subject is the record volume — not the
        // raw date column, which read as "db_matrix.data_entrada" and confused what was measured.
        List<Insight> insights = InsightGenerator.fromTimeSeries("O volume de registros", series);

        return new TimeSeriesResponse(table, dateColumn, series, insights);
    }

    public CorrelationResponse getCorrelations(String table, Map<String, String> filters) {
        TableSchema schema = getSchema(table);
        Where filter = buildWhere(schema, filters);
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
                "SELECT " + columnList + " FROM `" + table + "`" + filter.leading(),
                filter.params().toArray());

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

    public List<Insight> getInsights(String table, Map<String, String> filters) {
        List<Insight> insights = new ArrayList<>();
        TableSchema schema = getSchema(table);

        boolean hasDateColumn = schema.columns().stream().anyMatch(c -> c.type() == ColumnType.DATETIME);
        if (hasDateColumn) {
            insights.addAll(getTimeSeries(table, null, "month", filters).insights());
        }
        insights.addAll(getStats(table, filters).insights());
        insights.addAll(getCorrelations(table, filters).insights());
        return insights;
    }

    /**
     * Equality filter clause built from request params. Only keys matching an actual column of the
     * table are honored (blank values are ignored), so filters can never inject arbitrary SQL — the
     * column identifiers come from the introspected schema, never straight from the request. Exposes
     * the same conditions as either a leading {@code WHERE ...} or a trailing {@code AND ...} so it
     * composes with queries that already carry a {@code WHERE col IS NOT NULL} guard.
     */
    private record Where(String conditions, List<Object> params) {
        boolean isEmpty() {
            return conditions.isEmpty();
        }

        String leading() {
            return isEmpty() ? "" : " WHERE " + conditions;
        }

        String additional() {
            return isEmpty() ? "" : " AND " + conditions;
        }
    }

    private Where buildWhere(TableSchema schema, Map<String, String> filters) {
        if (filters == null || filters.isEmpty()) {
            return new Where("", List.of());
        }
        Set<String> validColumns = columnNames(schema);
        StringBuilder conditions = new StringBuilder();
        List<Object> params = new ArrayList<>();
        for (Map.Entry<String, String> f : filters.entrySet()) {
            if (!validColumns.contains(f.getKey()) || f.getValue() == null || f.getValue().isBlank()) continue;
            if (!conditions.isEmpty()) conditions.append(" AND ");
            if (StatsEngine.EMPTY_LABEL.equals(f.getValue())) {
                // The categorical engine buckets SQL NULL / empty / whitespace-only rows under the
                // "(vazio)" label; selecting it must match those rows, not a literal "(vazio)" string.
                conditions.append("(`").append(f.getKey()).append("` IS NULL OR TRIM(`")
                        .append(f.getKey()).append("`) = '')");
            } else {
                conditions.append('`').append(f.getKey()).append("` = ?");
                params.add(f.getValue());
            }
        }
        return new Where(conditions.toString(), params);
    }

    /** Periods below this fraction of the busiest period are treated as noise and dropped. */
    private static final double PERIOD_VOLUME_FLOOR_RATIO = 0.01;

    /**
     * Some tables carry a handful of legacy/malformed rows with ancient or stray dates, producing
     * sparse periods (counts of 1–5) years before the real data begins — these flatten the volume
     * chart. Drop any period whose count is negligible relative to the busiest one. Data-driven, so
     * it works across every table without hardcoding a cutoff date.
     */
    private List<Map<String, Object>> dropNegligiblePeriods(List<Map<String, Object>> rows) {
        long peak = rows.stream().mapToLong(r -> ((Number) r.get("cnt")).longValue()).max().orElse(0);
        if (peak == 0) {
            return rows;
        }
        long floor = (long) Math.ceil(peak * PERIOD_VOLUME_FLOOR_RATIO);
        List<Map<String, Object>> significant = rows.stream()
                .filter(r -> ((Number) r.get("cnt")).longValue() >= floor)
                .toList();
        return significant.isEmpty() ? rows : significant;
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
