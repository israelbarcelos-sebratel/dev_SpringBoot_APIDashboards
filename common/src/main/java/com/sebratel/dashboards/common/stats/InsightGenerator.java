package com.sebratel.dashboards.common.stats;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** Turns raw statistics into human-readable, Portuguese-language insight sentences for the dashboard. */
public final class InsightGenerator {

    private InsightGenerator() {
    }

    public static List<Insight> fromTimeSeries(String metricLabel, TimeSeriesResult series) {
        List<Insight> insights = new ArrayList<>();

        if (series.periodOverPeriodChangePct() != null) {
            double pct = series.periodOverPeriodChangePct();
            String direction = pct >= 0 ? "cresceu" : "caiu";
            String comparison = switch (series.granularity()) {
                case "day" -> "no último dia vs. o dia anterior";
                case "week" -> "nos últimos 7 dias vs. os 7 dias anteriores";
                default -> "nos últimos 30 dias vs. os 30 dias anteriores";
            };
            insights.add(new Insight(
                    "period_change",
                    Math.abs(pct) >= 20 ? "warning" : "info",
                    String.format("%s %s %.1f%% %s", metricLabel, direction, Math.abs(pct), comparison),
                    pct,
                    null,
                    pct >= 0 ? Insight.POSITIVE : Insight.NEGATIVE
            ));
        }

        if (series.yearOverYearChangePct() != null) {
            double pct = series.yearOverYearChangePct();
            String direction = pct >= 0 ? "cresceu" : "caiu";
            insights.add(Insight.info(
                    "yoy_change",
                    String.format("%s %s %.1f%% nos últimos 365 dias vs. os 365 dias anteriores", metricLabel, direction, Math.abs(pct)),
                    pct,
                    null,
                    pct >= 0 ? Insight.POSITIVE : Insight.NEGATIVE
            ));
        }

        if (series.trendSlope() != null && Math.abs(series.trendSlope()) > 0.01) {
            String direction = series.trendSlope() > 0 ? "tendência de alta" : "tendência de queda";
            insights.add(Insight.info(
                    "trend",
                    String.format("%s apresenta %s ao longo do período analisado", metricLabel, direction),
                    series.trendSlope(),
                    null,
                    series.trendSlope() > 0 ? Insight.POSITIVE : Insight.NEGATIVE
            ));
        }

        return insights;
    }

    public static List<Insight> fromDescriptive(String columnLabel, DescriptiveStats stats) {
        List<Insight> insights = new ArrayList<>();
        if (stats.count() == 0) return insights;

        if (stats.outliers().iqrOutlierCount() > 0) {
            double pct = 100.0 * stats.outliers().iqrOutlierCount() / stats.count();
            insights.add(Insight.warning(
                    "outliers",
                    String.format("%s tem %d valores atípicos (%.1f%% dos registros, método IQR)", columnLabel, stats.outliers().iqrOutlierCount(), pct),
                    pct,
                    columnLabel
            ));
        }

        if (Math.abs(stats.skewness()) > 1) {
            String direction = stats.skewness() > 0 ? "à direita (cauda longa de valores altos)" : "à esquerda (cauda longa de valores baixos)";
            insights.add(Insight.info(
                    "skewness",
                    String.format("Distribuição de %s é assimétrica %s", columnLabel, direction),
                    stats.skewness(),
                    columnLabel
            ));
        }

        if (stats.coefficientOfVariation() > 1) {
            insights.add(Insight.info(
                    "dispersion",
                    String.format("%s tem alta dispersão relativa (CV=%.2f) — valores muito heterogêneos", columnLabel, stats.coefficientOfVariation()),
                    stats.coefficientOfVariation(),
                    columnLabel
            ));
        }

        return insights;
    }

    public static List<Insight> fromCorrelation(CorrelationResult correlation) {
        List<Insight> insights = new ArrayList<>();
        List<String> cols = correlation.columns();
        for (int i = 0; i < cols.size(); i++) {
            for (int j = i + 1; j < cols.size(); j++) {
                double r = correlation.matrix()[i][j];
                if (Math.abs(r) >= 0.6) {
                    String strength = Math.abs(r) >= 0.8 ? "forte" : "moderada";
                    String direction = r > 0 ? "positiva" : "negativa";
                    insights.add(Insight.info(
                            "correlation",
                            String.format("Correlação %s %s entre %s e %s (r=%.2f)", strength, direction, cols.get(i), cols.get(j), r),
                            r
                    ));
                }
            }
        }
        return insights;
    }

    public static List<Insight> fromCategorical(String columnLabel, CategoricalStats stats) {
        List<Insight> insights = new ArrayList<>();
        if (stats.distribution().isEmpty()) return insights;

        var top = stats.distribution().get(0);
        if (top.percentage() >= 50) {
            if (isMissingLabel(top.label())) {
                // A column dominated by null/blank is incomplete data, not a real concentration — flag it as bad.
                insights.add(Insight.info(
                        "missing_concentration",
                        String.format("%.1f%% dos registros de %s estão sem preenchimento (null/vazio) — dado incompleto", top.percentage(), columnLabel),
                        top.percentage(),
                        columnLabel,
                        Insight.NEGATIVE
                ));
            } else {
                insights.add(Insight.info(
                        "concentration",
                        String.format("'%s' concentra %.1f%% dos registros de %s", top.label(), top.percentage(), columnLabel),
                        top.percentage(),
                        columnLabel
                ));
            }
        }

        return insights;
    }

    private static boolean isMissingLabel(String label) {
        return StatsEngine.EMPTY_LABEL.equals(label) || "null".equalsIgnoreCase(label);
    }

    /** A duration column with a good/bad target: lower is always better (wait and response times). */
    private record DurationTarget(String label, long thresholdSeconds) {
    }

    // "Padrão call center": queue wait <= 5min, team response (TMIA) <= 3min, client response (TMIC) <= 10min.
    private static final Map<String, DurationTarget> DURATION_TARGETS = Map.of(
            "tempo_fila", new DurationTarget("tempo de espera em fila", 300),
            "tmia", new DurationTarget("tempo de resposta do time", 180),
            "tmic", new DurationTarget("tempo de resposta do cliente", 600)
    );

    /**
     * For known duration columns, report the average time against its target — the real analytical
     * goal (is the queue short, is the team/client responding fast?), flagged good/bad by the target.
     */
    public static List<Insight> fromDuration(String column, CategoricalStats stats) {
        List<Insight> insights = new ArrayList<>();
        DurationTarget target = DURATION_TARGETS.get(column);
        if (target == null || stats.durationSummary() == null) return insights;

        long mean = Math.round(stats.durationSummary().meanSeconds());
        boolean good = mean <= target.thresholdSeconds();
        insights.add(Insight.info(
                "duration",
                String.format("%s: média de %s (meta ≤ %s)",
                        capitalize(target.label()), formatHms(mean), formatHms(target.thresholdSeconds())),
                (double) mean,
                column,
                good ? Insight.POSITIVE : Insight.NEGATIVE
        ));
        return insights;
    }

    private static String capitalize(String text) {
        return text.isEmpty() ? text : Character.toUpperCase(text.charAt(0)) + text.substring(1);
    }

    private static String formatHms(long totalSeconds) {
        long h = totalSeconds / 3600;
        long m = (totalSeconds % 3600) / 60;
        long s = totalSeconds % 60;
        return String.format("%02d:%02d:%02d", h, m, s);
    }
}
