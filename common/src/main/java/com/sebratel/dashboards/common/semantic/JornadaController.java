package com.sebratel.dashboards.common.semantic;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * Semantic routes for the "jornada" domain — agents' work sessions. Backed by db_*_login, whose data
 * records login/logout and seconds connected ({@code seg_logado}); the meaningful reading is agent
 * presence and time online, not a raw access count — hence "jornada" rather than "acessos".
 */
@RestController
public class JornadaController {

    private static final String DOMINIO = "jornada";

    private final SemanticService semantic;

    public JornadaController(SemanticService semantic) {
        this.semantic = semantic;
    }

    @GetMapping("/jornada/resumo")
    public MetricResponse resumo(@RequestParam(required = false) Integer meses,
                                 @RequestParam Map<String, String> allParams) {
        return semantic.resumo(DOMINIO, SemanticQuery.filtros(allParams), meses);
    }

    @GetMapping("/jornada/volume")
    public MetricResponse volume(@RequestParam(defaultValue = "dia") String por,
                                 @RequestParam(required = false) Integer meses,
                                 @RequestParam Map<String, String> allParams) {
        return semantic.volume(DOMINIO, por, SemanticQuery.filtros(allParams), meses);
    }

    @GetMapping("/jornada/por/{dimensao}")
    public MetricResponse porDimensao(@PathVariable String dimensao,
                                      @RequestParam(required = false) Integer meses,
                                      @RequestParam Map<String, String> allParams) {
        return semantic.porDimensao(DOMINIO, dimensao, SemanticQuery.filtros(allParams), meses);
    }

    @GetMapping("/jornada/duracao")
    public MetricResponse duracao(@RequestParam(required = false) Integer meses,
                                  @RequestParam Map<String, String> allParams) {
        return semantic.duracao(DOMINIO, SemanticQuery.filtros(allParams), meses);
    }
}
