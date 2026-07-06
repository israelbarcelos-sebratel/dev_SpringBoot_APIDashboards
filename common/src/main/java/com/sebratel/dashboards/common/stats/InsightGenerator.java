package com.sebratel.dashboards.common.stats;

import java.util.ArrayList;
import java.util.List;

/** Turns raw statistics into human-readable, Portuguese-language insight sentences for the dashboard. */
public final class InsightGenerator {

    private InsightGenerator() {
    }

    public static List<Insight> fromTimeSeries(String metricLabel, TimeSeriesResult series) {
        List<Insight> insights = new ArrayList<>();

        if (series.periodOverPeriodChangePct() != null) {
            double pct = series.periodOverPeriodChangePct();
            String direction = pct >= 0 ? "cresceu" : "caiu";
            String period = switch (series.granularity()) {
                case "day" -> "no último dia";
                case "week" -> "na última semana";
                default -> "no último mês";
            };
            insights.add(new Insight(
                    "period_change",
                    Math.abs(pct) >= 20 ? "warning" : "info",
                    String.format("%s %s %.1f%% %s em relação ao período anterior", metricLabel, direction, Math.abs(pct), period),
                    pct
            ));
        }

        if (series.yearOverYearChangePct() != null) {
            double pct = series.yearOverYearChangePct();
            String direction = pct >= 0 ? "cresceu" : "caiu";
            insights.add(Insight.info(
                    "yoy_change",
                    String.format("%s %s %.1f%% comparado ao mesmo mês do ano anterior", metricLabel, direction, Math.abs(pct)),
                    pct
            ));
        }

        if (series.trendSlope() != null && Math.abs(series.trendSlope()) > 0.01) {
            String direction = series.trendSlope() > 0 ? "tendência de alta" : "tendência de queda";
            insights.add(Insight.info(
                    "trend",
                    String.format("%s apresenta %s ao longo do período analisado", metricLabel, direction),
                    series.trendSlope()
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
                    pct
            ));
        }

        if (Math.abs(stats.skewness()) > 1) {
            String direction = stats.skewness() > 0 ? "à direita (cauda longa de valores altos)" : "à esquerda (cauda longa de valores baixos)";
            insights.add(Insight.info(
                    "skewness",
                    String.format("Distribuição de %s é assimétrica %s", columnLabel, direction),
                    stats.skewness()
            ));
        }

        if (stats.coefficientOfVariation() > 1) {
            insights.add(Insight.info(
                    "dispersion",
                    String.format("%s tem alta dispersão relativa (CV=%.2f) — valores muito heterogêneos", columnLabel, stats.coefficientOfVariation()),
                    stats.coefficientOfVariation()
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
            insights.add(Insight.info(
                    "concentration",
                    String.format("'%s' concentra %.1f%% dos registros de %s", top.label(), top.percentage(), columnLabel),
                    top.percentage()
            ));
        }

        if (stats.distinctCount() > 1) {
            double maxEntropy = Math.log(stats.distinctCount()) / Math.log(2);
            double normalized = maxEntropy > 0 ? stats.shannonEntropy() / maxEntropy : 0;
            if (normalized < 0.3) {
                insights.add(Insight.info(
                        "low_diversity",
                        String.format("%s tem baixa diversidade de categorias (entropia normalizada %.2f)", columnLabel, normalized),
                        normalized
                ));
            }
        }

        return insights;
    }
}
