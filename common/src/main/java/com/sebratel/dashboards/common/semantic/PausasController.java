package com.sebratel.dashboards.common.semantic;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * Semantic routes for the "pausas" domain — agent pause events. Backed by db_*_stops, whose data
 * carries the pause reason ({@code pausa}, 9 types), the agent and the seconds paused. Same knobs as
 * the other domains ({@code por}, {@code meses}, dimension filters).
 */
@RestController
public class PausasController {

    private static final String DOMINIO = "pausas";

    private final SemanticService semantic;

    public PausasController(SemanticService semantic) {
        this.semantic = semantic;
    }

    @GetMapping("/pausas/resumo")
    public MetricResponse resumo(@RequestParam(required = false) Integer meses,
                                 @RequestParam Map<String, String> allParams) {
        return semantic.resumo(DOMINIO, SemanticQuery.filtros(allParams), meses);
    }

    @GetMapping("/pausas/volume")
    public MetricResponse volume(@RequestParam(defaultValue = "semana") String por,
                                 @RequestParam(required = false) Integer meses,
                                 @RequestParam Map<String, String> allParams) {
        return semantic.volume(DOMINIO, por, SemanticQuery.filtros(allParams), meses);
    }

    @GetMapping("/pausas/por/{dimensao}")
    public MetricResponse porDimensao(@PathVariable String dimensao,
                                      @RequestParam(required = false) Integer meses,
                                      @RequestParam Map<String, String> allParams) {
        return semantic.porDimensao(DOMINIO, dimensao, SemanticQuery.filtros(allParams), meses);
    }

    @GetMapping("/pausas/duracao")
    public MetricResponse duracao(@RequestParam(required = false) Integer meses,
                                  @RequestParam Map<String, String> allParams) {
        return semantic.duracao(DOMINIO, "duracaoSegundos", "segundos", SemanticQuery.filtros(allParams), meses);
    }
}
