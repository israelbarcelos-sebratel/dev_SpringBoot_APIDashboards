package com.sebratel.dashboards.common.semantic;

import java.util.List;

/**
 * The discovery response served at {@code GET /dominios}: a plain-language map of everything the
 * API offers, so an AI can learn what it may ask for before asking. Each domain lists its metrics
 * (route + title + suggested chart) and the dimensions available to filter or break down by.
 */
public record DomainCatalog(List<DomainInfo> dominios) {

    public record DomainInfo(
            String nome,
            String titulo,
            String descricao,
            List<MetricInfo> metricas,
            List<String> dimensoes
    ) {
    }

    public record MetricInfo(String rota, String titulo, String visualizacao) {
    }
}
