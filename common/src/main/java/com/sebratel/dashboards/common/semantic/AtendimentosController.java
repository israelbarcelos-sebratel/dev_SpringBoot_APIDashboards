package com.sebratel.dashboards.common.semantic;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * Semantic routes for the "atendimentos" domain. Each route reads like the business question it
 * answers; all delegate to {@link SemanticService} and honor the common knobs — {@code por}
 * (dia/semana/mes), {@code meses} (window override; default is the last 6 weeks) and any
 * {@code <dimensão>=<valor>} filter. Breakdowns use a generic {@code /por/{dimensao}} so each
 * service can expose its own dimensions (they differ between matrix and native) — the available
 * dimension names come from {@code GET /dominios}.
 */
@RestController
public class AtendimentosController {

    private static final String DOMINIO = "atendimentos";

    private final SemanticService semantic;

    public AtendimentosController(SemanticService semantic) {
        this.semantic = semantic;
    }

    @GetMapping("/atendimentos/resumo")
    public MetricResponse resumo(@RequestParam(required = false) Integer meses,
                                 @RequestParam Map<String, String> allParams) {
        return semantic.resumo(DOMINIO, SemanticQuery.filtros(allParams), meses);
    }

    @GetMapping("/atendimentos/volume")
    public MetricResponse volume(@RequestParam(defaultValue = "semana") String por,
                                 @RequestParam(required = false) Integer meses,
                                 @RequestParam Map<String, String> allParams) {
        return semantic.volume(DOMINIO, por, SemanticQuery.filtros(allParams), meses);
    }

    @GetMapping("/atendimentos/por/{dimensao}")
    public MetricResponse porDimensao(@PathVariable String dimensao,
                                      @RequestParam(required = false) Integer meses,
                                      @RequestParam Map<String, String> allParams) {
        return semantic.porDimensao(DOMINIO, dimensao, SemanticQuery.filtros(allParams), meses);
    }

    @GetMapping("/atendimentos/tempos")
    public MetricResponse tempos(@RequestParam(required = false) Integer meses,
                                 @RequestParam Map<String, String> allParams) {
        return semantic.tempos(DOMINIO, SemanticQuery.filtros(allParams), meses);
    }
}
