package com.sebratel.dashboards.common.semantic;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * Semantic routes for the "satisfacao" domain — the customer-satisfaction survey. Same knobs as the
 * other domains ({@code por}, {@code meses}, dimension filters); the score is already translated to
 * its sentiment label (Irritado → Encantado) in the responses.
 */
@RestController
public class SatisfacaoController {

    private static final String DOMINIO = "satisfacao";

    private final SemanticService semantic;

    public SatisfacaoController(SemanticService semantic) {
        this.semantic = semantic;
    }

    @GetMapping("/satisfacao/resumo")
    public MetricResponse resumo(@RequestParam(required = false) Integer meses,
                                 @RequestParam Map<String, String> allParams) {
        return semantic.resumo(DOMINIO, SemanticQuery.filtros(allParams), meses);
    }

    @GetMapping("/satisfacao/evolucao")
    public MetricResponse evolucao(@RequestParam(defaultValue = "semana") String por,
                                   @RequestParam(required = false) Integer meses,
                                   @RequestParam Map<String, String> allParams) {
        return semantic.evolucao(DOMINIO, por, SemanticQuery.filtros(allParams), meses);
    }

    @GetMapping("/satisfacao/distribuicao")
    public MetricResponse distribuicao(@RequestParam(required = false) Integer meses,
                                       @RequestParam Map<String, String> allParams) {
        return semantic.porDimensao(DOMINIO, "nota", SemanticQuery.filtros(allParams), meses);
    }

    @GetMapping("/satisfacao/por/{dimensao}")
    public MetricResponse porDimensao(@PathVariable String dimensao,
                                      @RequestParam(required = false) Integer meses,
                                      @RequestParam Map<String, String> allParams) {
        return semantic.porDimensao(DOMINIO, dimensao, SemanticQuery.filtros(allParams), meses);
    }
}
