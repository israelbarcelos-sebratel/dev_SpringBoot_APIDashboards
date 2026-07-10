package com.sebratel.dashboards.common.semantic;

import java.util.HashMap;
import java.util.Map;

/** Strips the semantic control knobs so only real dimension filters reach the query layer. */
final class SemanticQuery {

    private SemanticQuery() {
    }

    static Map<String, String> filtros(Map<String, String> allParams) {
        Map<String, String> filtros = new HashMap<>(allParams);
        filtros.remove("por");
        filtros.remove("meses");
        return filtros;
    }
}
