package com.sebratel.dashboards.common.web;

import com.sebratel.dashboards.common.config.TableGroupProperties;
import com.sebratel.dashboards.common.dto.BreakdownSeries;
import com.sebratel.dashboards.common.dto.BreakdownTimeSeriesResponse;
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
import com.sebratel.dashboards.common.stats.CategoricalStats;
import com.sebratel.dashboards.common.stats.CorrelationResult;
import com.sebratel.dashboards.common.stats.DescriptiveStats;
import com.sebratel.dashboards.common.stats.Insight;
import com.sebratel.dashboards.common.stats.InsightGenerator;
import com.sebratel.dashboards.common.stats.StatsEngine;
import com.sebratel.dashboards.common.stats.TimeSeriesResult;
import com.sebratel.dashboards.common.stats.WindowComparison;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;

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
                             Map<String, String> filters, Integer months) {
        TableSchema schema = getSchema(table);
        Set<String> validColumns = columnNames(schema);

        String orderBy = validColumns.contains(sortColumn) ? sortColumn : schema.columns().get(0).name();
        String direction = "asc".equalsIgnoreCase(sortDir) ? "ASC" : "DESC";

        Where filter = scopedWhere(table, schema, filters, months);
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

    public StatsResponse getStats(String table, Map<String, String> filters, Integer months) {
        TableSchema schema = getSchema(table);
        Where filter = scopedWhere(table, schema, filters, months);
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

    /**
     * Categorical distribution for a SINGLE column, scoped to the default/overridden time window.
     * Cheaper than {@link #getStats} (which scans every column) — the semantic layer's "por-canal",
     * "por-servico" etc. only ever need one column, so this avoids computing the whole table.
     */
    public CategoricalStats categoricalColumn(String table, String column, Map<String, String> filters,
                                              Integer months) {
        TableSchema schema = getSchema(table);
        requireColumn(schema, column);
        Where filter = scopedWhere(table, schema, filters, months);
        List<String> values = jdbcTemplate.queryForList(
                        "SELECT `" + column + "` FROM `" + table + "`" + filter.leading(),
                        filter.params().toArray(), Object.class)
                .stream().map(v -> v == null ? null : v.toString()).toList();
        return StatsEngine.categorical(values);
    }

    /** Descriptive stats (histogram, quartiles, mean) for a SINGLE numeric/duration column, scoped. */
    public DescriptiveStats numericColumn(String table, String column, Map<String, String> filters,
                                          Integer months) {
        TableSchema schema = getSchema(table);
        requireColumn(schema, column);
        Where filter = scopedWhere(table, schema, filters, months);
        List<Double> values = jdbcTemplate.queryForList(
                "SELECT `" + column + "` FROM `" + table + "` WHERE `" + column + "` IS NOT NULL"
                        + filter.additional(),
                filter.params().toArray(), Double.class);
        return StatsEngine.descriptive(values);
    }

    /**
     * Descriptive stats (histogram, quartiles, mean) of a duration expressed in SECONDS, scoped to the
     * window. Unlike {@link #numericColumn}, the seconds don't have to live in a numeric column: the
     * {@link DurationSource} says whether to read a seconds column as-is, parse an {@code "HH:MM:SS"}
     * varchar ({@code TIME_TO_SEC}) or compute the gap between two datetimes ({@code TIMESTAMPDIFF}),
     * so matrix and native each feed their own representation into the same histogram. Every column the
     * source names is validated against the schema and guarded {@code IS NOT NULL}; rows a conversion
     * can't turn into seconds ({@code TIME_TO_SEC} of a malformed value yields NULL) are dropped
     * downstream by {@link StatsEngine#descriptive}.
     */
    public DescriptiveStats durationColumn(String table, DurationSource source, Map<String, String> filters,
                                           Integer months) {
        TableSchema schema = getSchema(table);
        List<String> cols = source.columns();
        cols.forEach(c -> requireColumn(schema, c));
        Where filter = scopedWhere(table, schema, filters, months);

        String valueExpr = switch (source.kind()) {
            case SECONDS -> "`" + source.column() + "`";
            case HMS -> "TIME_TO_SEC(`" + source.column() + "`)";
            case RANGE -> "TIMESTAMPDIFF(SECOND, `" + source.startColumn() + "`, `" + source.endColumn() + "`)";
        };
        String guard = cols.stream().map(c -> "`" + c + "` IS NOT NULL").reduce((a, b) -> a + " AND " + b).orElseThrow();
        // A duration is non-negative by definition; drop the glitched rows the data actually carries
        // (a stray "-00:00:14" pause, a session whose fim precedes its inicio) so they don't pull the
        // histogram's minimum below zero. This also excludes rows a conversion couldn't parse, since a
        // NULL value fails the comparison.
        guard = guard + " AND (" + valueExpr + ") >= 0";

        List<Double> values = jdbcTemplate.queryForList(
                "SELECT " + valueExpr + " FROM `" + table + "` WHERE " + guard + filter.additional(),
                filter.params().toArray(), Double.class);
        return StatsEngine.descriptive(values);
    }

    /** Row count over the scoped window — backs the semantic "resumo" total. */
    public long count(String table, Map<String, String> filters, Integer months) {
        TableSchema schema = getSchema(table);
        Where filter = scopedWhere(table, schema, filters, months);
        return jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM `" + table + "`" + filter.leading(), filter.params().toArray(), Long.class);
    }

    /**
     * The concrete calendar bounds of the current window, formatted {@code yyyy-MM-dd}, so the
     * semantic envelope can label the period the user is actually seeing. Formatting happens in SQL
     * to sidestep JDBC/timezone conversion. Null when the table has no date column.
     */
    public String[] windowBounds(String table, Map<String, String> filters, Integer months) {
        TableSchema schema = getSchema(table);
        String dateColumn = firstDateColumn(schema);
        if (dateColumn == null) {
            return null;
        }
        String inicio = reservedValue(filters, "inicio");
        String fim = reservedValue(filters, "fim");
        if (inicio != null || fim != null) {
            // An explicit inicio/fim override IS the period label — no need to ask the database.
            return new String[]{inicio != null ? inicio : fim, fim != null ? fim : inicio};
        }
        String col = "`" + dateColumn + "`";
        String interval = (months != null && months > 0) ? "INTERVAL " + months + " MONTH" : DEFAULT_RANGE_INTERVAL;
        Map<String, Object> r = jdbcTemplate.queryForMap(
                "SELECT DATE_FORMAT(DATE_SUB(MAX(" + col + "), " + interval + "), '%Y-%m-%d') AS de, " +
                        "DATE_FORMAT(MAX(" + col + "), '%Y-%m-%d') AS ate FROM `" + table + "`");
        Object de = r.get("de");
        Object ate = r.get("ate");
        return (de == null || ate == null) ? null : new String[]{de.toString(), ate.toString()};
    }

    private void requireColumn(TableSchema schema, String column) {
        if (column == null || !columnNames(schema).contains(column)) {
            throw new IllegalArgumentException("Coluna desconhecida na tabela " + schema.tableName() + ": " + column);
        }
    }

    private String firstDateColumn(TableSchema schema) {
        return schema.columns().stream()
                .filter(c -> c.type() == ColumnType.DATETIME)
                .map(ColumnMetadata::name)
                .findFirst()
                .orElse(null);
    }

    public TimeSeriesResponse getTimeSeries(String table, String requestedColumn, String granularity,
                                             Map<String, String> filters, Integer months) {
        TableSchema schema = getSchema(table);
        String dateColumn = resolveDateColumn(schema, requestedColumn);
        // The rolling-window variation (incl. YoY, which needs a full year of history) is always
        // computed over the unscoped filter — only the bucketed series itself respects the requested
        // time window, so narrowing the window never breaks the year-over-year comparison.
        Where filter = buildWhere(schema, filters);
        Where scoped = mergeWhere(mergeWhere(filter, dateRangeFilter(table, schema, filters, months)),
                hourOfDayFilter(schema, filters));
        String format = dateFormatFor(granularity);

        List<Object> params = new ArrayList<>();
        params.add(format);
        params.addAll(scoped.params());
        List<Map<String, Object>> rows = jdbcTemplate.queryForList(
                "SELECT DATE_FORMAT(`" + dateColumn + "`, ?) AS period, COUNT(*) AS cnt " +
                        "FROM `" + table + "` WHERE `" + dateColumn + "` IS NOT NULL " + scoped.additional() +
                        " GROUP BY period ORDER BY period ASC",
                params.toArray());

        rows = dropNegligiblePeriods(rows);

        List<String> labels = rows.stream().map(r -> String.valueOf(r.get("period"))).toList();
        List<Long> counts = rows.stream().map(r -> ((Number) r.get("cnt")).longValue()).toList();

        WindowComparison windows = rollingWindows(table, dateColumn, filter, windowDaysFor(granularity));
        TimeSeriesResult series = StatsEngine.timeSeries(granularity, labels, counts, windows);
        // The series counts rows per period, so the insight subject is the record volume — not the
        // raw date column, which read as "db_matrix.data_entrada" and confused what was measured.
        List<Insight> insights = InsightGenerator.fromTimeSeries("O volume de registros", series);

        return new TimeSeriesResponse(table, dateColumn, series, insights);
    }

    /** DATE_FORMAT pattern that buckets a datetime into a day / ISO week / month label. */
    private static String dateFormatFor(String granularity) {
        return switch (granularity) {
            case "hour" -> "%Y-%m-%dT%H:00:00";
            case "day" -> "%Y-%m-%d";
            case "week" -> "%x-W%v";
            default -> "%Y-%m";
        };
    }

    /**
     * Time series split by a categorical column: per period (time on the X axis), the row count for
     * each distinct value of {@code breakdownColumn}. Powers the satisfaction-over-time chart, where
     * each vote is drawn as its own line. Rows with a null date or null category are excluded; the
     * per-category counts are zero-filled so every line spans the whole X axis.
     */
    public BreakdownTimeSeriesResponse getTimeSeriesBreakdown(String table, String breakdownColumn,
                                                              String granularity, Map<String, String> filters,
                                                              Integer months) {
        TableSchema schema = getSchema(table);
        String dateColumn = resolveDateColumn(schema, null);
        if (breakdownColumn == null || !columnNames(schema).contains(breakdownColumn)) {
            throw new IllegalArgumentException("Coluna de quebra desconhecida: " + breakdownColumn);
        }
        Where filter = scopedWhere(table, schema, filters, months);
        String format = dateFormatFor(granularity);

        List<Object> params = new ArrayList<>();
        params.add(format);
        params.addAll(filter.params());
        List<Map<String, Object>> rows = jdbcTemplate.queryForList(
                "SELECT DATE_FORMAT(`" + dateColumn + "`, ?) AS period, `" + breakdownColumn + "` AS cat, COUNT(*) AS cnt " +
                        "FROM `" + table + "` WHERE `" + dateColumn + "` IS NOT NULL AND `" + breakdownColumn + "` IS NOT NULL " +
                        filter.additional() + " GROUP BY period, cat ORDER BY period ASC",
                params.toArray());

        // Pivot the (period, cat, cnt) rows into a period axis + one count list per category.
        LinkedHashSet<String> periodOrder = new LinkedHashSet<>();
        Map<String, Map<String, Long>> byCategory = new LinkedHashMap<>();
        Map<String, Long> periodTotals = new HashMap<>();
        for (Map<String, Object> r : rows) {
            String period = String.valueOf(r.get("period"));
            String cat = String.valueOf(r.get("cat"));
            long cnt = ((Number) r.get("cnt")).longValue();
            periodOrder.add(period);
            byCategory.computeIfAbsent(cat, k -> new HashMap<>()).put(period, cnt);
            periodTotals.merge(period, cnt, Long::sum);
        }

        List<String> periods = dropNegligiblePeriodLabels(periodOrder, periodTotals);

        // Order categories numerically when they all look like numbers (satisfaction 1..5), otherwise
        // by total volume so the busiest line is listed first.
        List<String> categories = new ArrayList<>(byCategory.keySet());
        if (categories.stream().allMatch(TableDataService::isNumeric)) {
            categories.sort(Comparator.comparingDouble(Double::parseDouble));
        } else {
            categories.sort(Comparator.comparingLong(
                    (String c) -> byCategory.get(c).values().stream().mapToLong(Long::longValue).sum()).reversed());
        }

        List<BreakdownSeries> series = new ArrayList<>();
        for (String cat : categories) {
            Map<String, Long> perPeriod = byCategory.get(cat);
            List<Long> counts = periods.stream().map(p -> perPeriod.getOrDefault(p, 0L)).toList();
            series.add(new BreakdownSeries(cat, counts));
        }

        return new BreakdownTimeSeriesResponse(table, dateColumn, breakdownColumn, granularity, periods, series);
    }

    private static boolean isNumeric(String value) {
        if (value == null || value.isBlank()) return false;
        try {
            Double.parseDouble(value.trim());
            return true;
        } catch (NumberFormatException e) {
            return false;
        }
    }

    public CorrelationResponse getCorrelations(String table, Map<String, String> filters, Integer months) {
        TableSchema schema = getSchema(table);
        Where filter = scopedWhere(table, schema, filters, months);
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

    public List<Insight> getInsights(String table, Map<String, String> filters, Integer months) {
        List<Insight> insights = new ArrayList<>();
        TableSchema schema = getSchema(table);

        boolean hasDateColumn = schema.columns().stream().anyMatch(c -> c.type() == ColumnType.DATETIME);
        if (hasDateColumn) {
            insights.addAll(getTimeSeries(table, null, "month", filters, months).insights());
        }
        insights.addAll(getStats(table, filters, months).insights());
        insights.addAll(getCorrelations(table, filters, months).insights());
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

    /**
     * Query keys that control the time window/hour range rather than naming a real column — read
     * directly by {@link #dateRangeFilter} / {@link #hourOfDayFilter}, so the generic equality loop
     * below must never treat them as a column filter.
     */
    private static final Set<String> RESERVED_RANGE_KEYS = Set.of("inicio", "fim", "horaInicio", "horaFim");

    private Where buildWhere(TableSchema schema, Map<String, String> filters) {
        if (filters == null || filters.isEmpty()) {
            return new Where("", List.of());
        }
        Set<String> validColumns = columnNames(schema);
        StringBuilder conditions = new StringBuilder();
        List<Object> params = new ArrayList<>();
        for (Map.Entry<String, String> f : filters.entrySet()) {
            if (RESERVED_RANGE_KEYS.contains(f.getKey())) continue;
            if (!validColumns.contains(f.getKey()) || f.getValue() == null || f.getValue().isBlank()) continue;
            // A comma-separated value (e.g. "FILA_A,FILA_B") selects any of several values — used by
            // multi-select filters like fila/agente — while a single value keeps the plain equality.
            List<String> values = Arrays.stream(f.getValue().split(","))
                    .map(String::trim)
                    .filter(v -> !v.isEmpty())
                    .toList();
            if (values.isEmpty()) continue;
            List<String> clauses = new ArrayList<>();
            for (String v : values) {
                if (StatsEngine.EMPTY_LABEL.equals(v)) {
                    // The categorical engine buckets SQL NULL / empty / whitespace-only rows under the
                    // "(vazio)" label; selecting it must match those rows, not a literal "(vazio)" string.
                    clauses.add("(`" + f.getKey() + "` IS NULL OR TRIM(`" + f.getKey() + "`) = '')");
                } else {
                    clauses.add("`" + f.getKey() + "` = ?");
                    params.add(v);
                }
            }
            if (!conditions.isEmpty()) conditions.append(" AND ");
            conditions.append(clauses.size() > 1 ? "(" + String.join(" OR ", clauses) + ")" : clauses.get(0));
        }
        return new Where(conditions.toString(), params);
    }

    /** Default fetch window when no {@code months} override is given: 6 weeks. */
    private static final String DEFAULT_RANGE_INTERVAL = "INTERVAL 6 WEEK";

    /**
     * Equality filters combined with the default/overridden time-range bound and, when requested, an
     * hour-of-day bound — the three knobs the "atendimentos" filter panel (Início/Fim, Filas/Agentes,
     * Filtro por hora) needs.
     */
    private Where scopedWhere(String table, TableSchema schema, Map<String, String> filters, Integer months) {
        Where scoped = mergeWhere(buildWhere(schema, filters), dateRangeFilter(table, schema, filters, months));
        return mergeWhere(scoped, hourOfDayFilter(schema, filters));
    }

    private static final Pattern ISO_DATE = Pattern.compile("^\\d{4}-\\d{2}-\\d{2}$");

    /** Reads a reserved control key (inicio/fim/horaInicio/horaFim) out of the filters map, if present. */
    private static String reservedValue(Map<String, String> filters, String key) {
        String v = filters == null ? null : filters.get(key);
        return (v == null || v.isBlank()) ? null : v.trim();
    }

    /**
     * Bounds a query to an explicit {@code inicio}/{@code fim} (yyyy-MM-dd) range when given, or
     * falls back to the last {@code months} months (or the last 6 weeks by default) of the table's
     * date column, anchored to {@code MAX(dateColumn)} rather than the wall clock — same anchoring
     * rationale as {@link #rollingWindows}: a table whose data lags "today" still returns its most
     * recent slice instead of an empty result. Tables without a date column are returned unscoped,
     * since there is nothing to bound.
     */
    private Where dateRangeFilter(String table, TableSchema schema, Map<String, String> filters, Integer months) {
        String dateColumn = schema.columns().stream()
                .filter(c -> c.type() == ColumnType.DATETIME)
                .map(ColumnMetadata::name)
                .findFirst()
                .orElse(null);
        if (dateColumn == null) {
            return new Where("", List.of());
        }
        String col = "`" + dateColumn + "`";

        String inicio = reservedValue(filters, "inicio");
        String fim = reservedValue(filters, "fim");
        if (inicio != null || fim != null) {
            StringBuilder conditions = new StringBuilder();
            List<Object> params = new ArrayList<>();
            if (inicio != null) {
                if (!ISO_DATE.matcher(inicio).matches()) {
                    throw new IllegalArgumentException("Data 'inicio' inválida (use yyyy-MM-dd): " + inicio);
                }
                conditions.append(col).append(" >= ?");
                params.add(inicio);
            }
            if (fim != null) {
                if (!ISO_DATE.matcher(fim).matches()) {
                    throw new IllegalArgumentException("Data 'fim' inválida (use yyyy-MM-dd): " + fim);
                }
                if (!conditions.isEmpty()) conditions.append(" AND ");
                // Exclusive upper bound one day later, so a date-only "fim" still includes that whole day.
                conditions.append(col).append(" < DATE_ADD(?, INTERVAL 1 DAY)");
                params.add(fim);
            }
            return new Where(conditions.toString(), params);
        }

        // months is a server-validated positive integer (see TableController), so inlining it carries
        // no injection risk — the same pattern rollingWindows uses for its own interval constants.
        String interval = (months != null && months > 0) ? "INTERVAL " + months + " MONTH" : DEFAULT_RANGE_INTERVAL;
        String condition = col + " > (SELECT DATE_SUB(MAX(" + col + "), " + interval + ") FROM `" + table + "`)";
        return new Where(condition, List.of());
    }

    /**
     * Restricts rows to an hour-of-day range ({@code horaInicio}..{@code horaFim}, 0-23, either end
     * optional) — e.g. only the 08h-18h shift — independent of which calendar days are in range.
     */
    private Where hourOfDayFilter(TableSchema schema, Map<String, String> filters) {
        String horaInicio = reservedValue(filters, "horaInicio");
        String horaFim = reservedValue(filters, "horaFim");
        if (horaInicio == null && horaFim == null) {
            return new Where("", List.of());
        }
        String dateColumn = firstDateColumn(schema);
        if (dateColumn == null) {
            return new Where("", List.of());
        }
        int from = parseHour(horaInicio, 0);
        int to = parseHour(horaFim, 23);
        // from/to are validated 0-23 ints, not request text, so inlining them carries no injection risk.
        String condition = "HOUR(`" + dateColumn + "`) BETWEEN " + from + " AND " + to;
        return new Where(condition, List.of());
    }

    private static int parseHour(String raw, int fallback) {
        if (raw == null) {
            return fallback;
        }
        int h;
        try {
            h = Integer.parseInt(raw);
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("Hora inválida (use 0-23): " + raw);
        }
        if (h < 0 || h > 23) {
            throw new IllegalArgumentException("Hora fora do intervalo 0-23: " + raw);
        }
        return h;
    }

    /** Combines two filters with AND, short-circuiting when either side is empty. */
    private Where mergeWhere(Where a, Where b) {
        if (a.isEmpty()) return b;
        if (b.isEmpty()) return a;
        List<Object> params = new ArrayList<>(a.params());
        params.addAll(b.params());
        return new Where(a.conditions() + " AND " + b.conditions(), params);
    }

    /** Rolling-window length (in days) for the variation, by chart granularity. */
    private static int windowDaysFor(String granularity) {
        return switch (granularity) {
            case "hour" -> 1;
            case "day" -> 1;
            case "week" -> 7;
            default -> 30;
        };
    }

    /**
     * Computes the two equal-length rolling windows behind the variation: the last {@code windowDays}
     * days vs the {@code windowDays} days before them, plus the last 365 days vs the 365 before them.
     *
     * <p>The windows are anchored to the most recent record ({@code MAX(dateColumn)}), not the wall
     * clock: a table whose data lags "today" still compares its latest window against the previous
     * one, and when the data is current (the usual case) the anchor is effectively today. Comparing
     * equal-length windows is what fixes the old distortion of a partial month vs a full month.
     *
     * <p>The day counts (window, 2×window, 365, 730) are server-derived integers, never request
     * input, so inlining them in the SQL carries no injection risk; only the filter values bind as
     * parameters — once for the anchor subquery and once for the outer scan.
     */
    private WindowComparison rollingWindows(String table, String dateColumn, Where filter, int windowDays) {
        String col = "`" + dateColumn + "`";
        String tbl = "`" + table + "`";
        String notNull = col + " IS NOT NULL";
        String sql =
                "SELECT " +
                "SUM(" + col + " > DATE_SUB(a.anchor, INTERVAL " + windowDays + " DAY)) AS cur, " +
                "SUM(" + col + " <= DATE_SUB(a.anchor, INTERVAL " + windowDays + " DAY) AND " +
                        col + " > DATE_SUB(a.anchor, INTERVAL " + (2 * windowDays) + " DAY)) AS prev, " +
                "SUM(" + col + " > DATE_SUB(a.anchor, INTERVAL 365 DAY)) AS cur_year, " +
                "SUM(" + col + " <= DATE_SUB(a.anchor, INTERVAL 365 DAY) AND " +
                        col + " > DATE_SUB(a.anchor, INTERVAL 730 DAY)) AS prev_year " +
                "FROM " + tbl + " CROSS JOIN (SELECT MAX(" + col + ") AS anchor FROM " + tbl +
                        " WHERE " + notNull + filter.additional() + ") a " +
                "WHERE " + notNull + filter.additional();

        List<Object> params = new ArrayList<>();
        params.addAll(filter.params()); // anchor subquery
        params.addAll(filter.params()); // outer scan
        Map<String, Object> r = jdbcTemplate.queryForMap(sql, params.toArray());

        long current = asLong(r.get("cur"));
        long previous = asLong(r.get("prev"));
        Long yoyCurrent = r.get("cur_year") == null ? null : asLong(r.get("cur_year"));
        Long yoyPrevious = r.get("prev_year") == null ? null : asLong(r.get("prev_year"));
        // Only surface a year-over-year comparison when the prior year actually has data to compare to.
        boolean hasYoy = yoyPrevious != null && yoyPrevious > 0;
        return new WindowComparison(current, previous, windowDays,
                hasYoy ? yoyCurrent : null, hasYoy ? yoyPrevious : null);
    }

    private static long asLong(Object value) {
        return value == null ? 0L : ((Number) value).longValue();
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

    /**
     * Same negligible-period trimming as {@link #dropNegligiblePeriods}, but for the breakdown chart:
     * drops periods whose TOTAL volume (across all categories) is below 1% of the busiest period, so
     * the multi-line chart shares the same clean X axis as the volume chart. Preserves input order.
     */
    private List<String> dropNegligiblePeriodLabels(Collection<String> periodsInOrder, Map<String, Long> totals) {
        long peak = totals.values().stream().mapToLong(Long::longValue).max().orElse(0);
        if (peak == 0) {
            return new ArrayList<>(periodsInOrder);
        }
        long floor = (long) Math.ceil(peak * PERIOD_VOLUME_FLOOR_RATIO);
        List<String> kept = periodsInOrder.stream()
                .filter(p -> totals.getOrDefault(p, 0L) >= floor)
                .toList();
        return kept.isEmpty() ? new ArrayList<>(periodsInOrder) : new ArrayList<>(kept);
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
