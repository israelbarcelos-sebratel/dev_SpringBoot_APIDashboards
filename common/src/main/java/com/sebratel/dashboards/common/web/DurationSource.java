package com.sebratel.dashboards.common.web;

import java.util.List;

/**
 * Neutral description of where a duration (always read out in seconds) comes from in a table, so
 * {@link TableDataService} can histogram it without knowing anything about the semantic layer. Three
 * shapes cover every data set we have:
 *
 * <ul>
 *   <li>{@link Kind#SECONDS} — a column that already holds whole seconds (matrix
 *       {@code seg_pausado}/{@code seg_logado}).</li>
 *   <li>{@link Kind#HMS} — an {@code "HH:MM:SS"} varchar, converted with {@code TIME_TO_SEC}
 *       (native {@code tempo_em_pausa}).</li>
 *   <li>{@link Kind#RANGE} — the gap between a start and an end datetime, via {@code TIMESTAMPDIFF}
 *       (native login {@code data_hora_inicio}/{@code data_hora_fim}, which has no ready-made
 *       duration column).</li>
 * </ul>
 *
 * The columns each shape names are validated against the table schema by {@link TableDataService}, so
 * a source can never reference a column the table doesn't have.
 */
public record DurationSource(Kind kind, String column, String startColumn, String endColumn) {

    public enum Kind { SECONDS, HMS, RANGE }

    public static DurationSource seconds(String column) {
        return new DurationSource(Kind.SECONDS, column, null, null);
    }

    public static DurationSource hms(String column) {
        return new DurationSource(Kind.HMS, column, null, null);
    }

    public static DurationSource range(String startColumn, String endColumn) {
        return new DurationSource(Kind.RANGE, null, startColumn, endColumn);
    }

    /** The real columns this source reads — the ones to validate against the schema and guard NOT NULL. */
    public List<String> columns() {
        return kind == Kind.RANGE ? List.of(startColumn, endColumn) : List.of(column);
    }
}
