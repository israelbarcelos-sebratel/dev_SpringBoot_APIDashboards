package com.sebratel.dashboards.common.web;

import com.sebratel.dashboards.common.config.WidgetProperties;
import com.sebratel.dashboards.common.stats.CategoricalStats;
import com.sebratel.dashboards.common.stats.StatsEngine;
import org.junit.jupiter.api.Test;

import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DurationSqlTest {

    @Test
    void normalizesEmptyAndDashHmsValuesBeforeConverting() {
        assertEquals("TIME_TO_SEC(NULLIF(NULLIF(TRIM(`espera`), ''), '-'))",
                DurationSql.hmsSeconds("espera"));
    }

    @Test
    void nullsMissingDateEndpointsAndKeepsNegativeGuardForAverage() {
        assertEquals("TIMESTAMPDIFF(SECOND, NULLIF(NULLIF(TRIM(`data_fila`), ''), '-'), "
                        + "NULLIF(NULLIF(TRIM(`data_atendimento`), ''), '-'))",
                DurationSql.rangeSeconds("data_fila", "data_atendimento"));
        assertEquals("CASE WHEN (d) >= 0 THEN (d) END", DurationSql.nonNegative("d"));
    }

    @Test
    void widgetMetricsUseNormalizedSqlForEveryConfiguredDurationShape() {
        WidgetProperties.Metrica hms = new WidgetProperties.Metrica();
        hms.setHms("tmic");
        assertEquals(DurationSql.hmsSeconds("tmic"), hms.expressaoSegundos());

        WidgetProperties.Metrica range = new WidgetProperties.Metrica();
        range.setInicio("data_fila");
        range.setFim("data_atendimento");
        assertEquals(DurationSql.rangeSeconds("data_fila", "data_atendimento"), range.expressaoSegundos());
    }

    @Test
    void durationMeanIgnoresBlankDashNullAndNegativeWhileCountingZeroAsValid() {
        CategoricalStats.DurationSummary summary = StatsEngine.categorical(
                Arrays.asList("", "-", null, "-00:00:14", "00:00:00", "00:00:00", "00:01:30"))
                .durationSummary();

        assertTrue(summary != null);
        assertEquals(3, summary.count());
        assertEquals(90, summary.totalSeconds());
        assertEquals(30.0, summary.meanSeconds());
    }
}
