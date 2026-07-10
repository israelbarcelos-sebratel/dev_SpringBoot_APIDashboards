package com.sebratel.dashboards.common.semantic;

import java.util.List;
import java.util.Map;

/**
 * The self-describing envelope every semantic metric responds with. Its shape is identical across
 * all domains and endpoints — only {@link #dados} changes — so a consumer (a person, or an AI
 * building a dashboard on the user's behalf) learns one format and can render any metric: the
 * title, the plain-language description, the covered period, the suggested chart, the unit, the
 * plottable data, the header KPIs and the ready-to-show insight sentences all travel together.
 *
 * @param titulo        human title for the panel
 * @param descricao     one-line explanation, in plain Portuguese
 * @param periodo       the calendar window the data actually covers
 * @param visualizacao  chart hint — see {@link Visualizacao}
 * @param unidade       what the values count (e.g. "atendimentos", "segundos", "%")
 * @param dados         the plottable payload, in the shape the chart expects
 * @param resumo        header KPIs (total, variation, trend…), or empty
 * @param insights      pre-written reading sentences, or empty
 */
public record MetricResponse(
        String titulo,
        String descricao,
        Periodo periodo,
        String visualizacao,
        String unidade,
        Object dados,
        Map<String, Object> resumo,
        List<String> insights
) {
    /** The window the metric covers, so the dashboard can label exactly what is being shown. */
    public record Periodo(String de, String ate, String rotulo) {
    }

    /** Suggested chart types — the vocabulary the frontend/AI maps to real components. */
    public static final class Visualizacao {
        public static final String KPI = "kpi";
        public static final String LINHA = "linha";
        public static final String BARRA = "barra";
        public static final String ROSCA = "rosca";
        public static final String HISTOGRAMA = "histograma";
        public static final String MULTILINHA = "multilinha";

        private Visualizacao() {
        }
    }
}
