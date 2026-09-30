package com.sebratel.dashboards.common.auth;

import com.sebratel.dashboards.common.cache.PausasComportamento;
import com.sebratel.dashboards.common.cache.ReferenciaMensalJob;
import com.sebratel.dashboards.common.cache.TemposHojeJob;
import com.sebratel.dashboards.common.cache.TempoLogado;
import com.sebratel.dashboards.common.cache.TmeaNovaRegra;
import com.sebratel.dashboards.common.config.TableGroupProperties;
import com.sebratel.dashboards.common.config.WidgetProperties;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Admin screen "Maiores ofensores" of the Chrome extension ({@code ofensores.html}): TMA, TME and TMEA
 * of every atendente of this system, today (the widget's in-memory snapshot, {@link TemposHojeJob},
 * refreshed every minute) or over the last 30 days ({@code ?periodo=30d}, {@link ReferenciaMensalJob},
 * refreshed every 10 minutes), with each metric's limit and each atendente's
 * sector reference for the TMEA. Ranking, filters and the minimum number of calls are up to the
 * screen — this only hands out the same numbers each atendente sees in their own widget.
 */
@RestController
public class OfensoresController {

    /** Metrics of the ranking; TMIC/TMIA (Matrix only) stay out. */
    private static final List<String> METRICAS = List.of("tma", "tme", TemposHojeJob.TMEA);

    private final UsuarioRepository usuarios;
    private final TemposHojeJob temposHoje;
    private final ReferenciaMensalJob referencia;
    private final WidgetProperties widgetProperties;
    private final TableGroupProperties groupProperties;
    private final TmeaNovaRegra tmeaNova;
    private final PausasComportamento pausas;

    public OfensoresController(UsuarioRepository usuarios, TemposHojeJob temposHoje, ReferenciaMensalJob referencia,
                               WidgetProperties widgetProperties, TableGroupProperties groupProperties,
                               TmeaNovaRegra tmeaNova, PausasComportamento pausas) {
        this.tmeaNova = tmeaNova;
        this.pausas = pausas;
        this.usuarios = usuarios;
        this.temposHoje = temposHoje;
        this.referencia = referencia;
        this.widgetProperties = widgetProperties;
        this.groupProperties = groupProperties;
    }

    /**
     * {@code atendentes}: one item per atendente name with calls today — {@code nome}, {@code setor}
     * (label or null), {@code atendimentos} (calls today), {@code tempos} (metrica -> {segundosMedios,
     * amostras}, only those with data) and {@code tmeaReferencia} (sector average, may be null); plus
     * {@code metas} (metrica -> limit in seconds) and the snapshot's freshness.
     */
    @GetMapping("/ext/ofensores")
    public Map<String, Object> ofensores(@RequestAttribute(ExtAuthInterceptor.EMAIL_ATTR) String email,
                                         @RequestParam(defaultValue = "hoje") String periodo) {
        requireAdmin(email);
        boolean hojeApenas = !"30d".equals(periodo);
        TemposHojeJob.Snapshot hoje = temposHoje.hoje();
        // Últimos dias: calculado a cada 10 min pelo ReferenciaMensalJob (inclui hoje até esse momento).
        TemposHojeJob.Periodo dados = hojeApenas
                ? new TemposHojeJob.Periodo(hoje.porAtendente(), hoje.atendimentos())
                : referencia.periodo();
        if (dados == null) {
            throw new AuthException(503, "Os números dos últimos dias ainda estão sendo calculados. Tente em alguns minutos.");
        }
        List<Map<String, Object>> atendentes = new ArrayList<>();
        dados.porAtendente().forEach((nome, tempos) -> {
            if ("null".equalsIgnoreCase(nome.trim())) {
                return; // db_matrix grava o texto 'null' quando não há atendente
            }
            Map<String, Object> t = new LinkedHashMap<>();
            for (String m : METRICAS) {
                TemposHojeJob.Tempo v = tempos.get(m);
                if (v != null) {
                    t.put(m, v);
                }
            }
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("nome", nome);
            item.put("setor", referencia.setor(nome));
            item.put("atendimentos", dados.atendimentos().getOrDefault(nome, 0L));
            item.put("tempos", t);
            item.put("tmeaReferencia", referencia.tmeaSetor(List.of(nome)));
            atendentes.add(item);
        });

        Map<String, Integer> metas = new LinkedHashMap<>();
        widgetProperties.getTempos().forEach((chave, m) -> {
            if (METRICAS.contains(chave) && m.getMetaSegundos() != null) {
                metas.put(chave, m.getMetaSegundos());
            }
        });

        Map<String, Object> resp = new LinkedHashMap<>();
        resp.put("sistema", groupProperties.groupName());
        resp.put("periodo", hojeApenas ? "hoje" : "30d");
        resp.put("dias", hojeApenas ? 1 : referencia.dias());
        resp.put("metas", metas);
        resp.put("atendentes", atendentes);
        resp.put("ultimoRegistro", hoje.ultimoRegistro());
        resp.put("calculadoEm", hojeApenas ? hoje.calculadoEm() : referencia.periodoCalculadoEm());
        return resp;
    }

    /**
     * Tab "TMEA · regra nova" (in test, see {@link TmeaNovaRegra}): per atendente, the TMEA under the
     * proposed rule with its parts (idle, pauses and logged-off time taken out, gap in progress), the
     * official TMEA of the same period for comparison, and the sector average under the new rule
     * (other atendentes of the sector over the last days, with at least 10 intervals).
     */
    @GetMapping("/ext/ofensores/tmea-nova")
    public Map<String, Object> tmeaNova(@RequestAttribute(ExtAuthInterceptor.EMAIL_ATTR) String email,
                                        @RequestParam(defaultValue = "hoje") String periodo) {
        requireAdmin(email);
        boolean hojeApenas = !"30d".equals(periodo);
        TmeaNovaRegra.Calculo calc = hojeApenas ? tmeaNova.hoje() : tmeaNova.ultimosDias();
        TmeaNovaRegra.Calculo base = tmeaNova.ultimosDias();
        TemposHojeJob.Snapshot hoje = temposHoje.hoje();
        TemposHojeJob.Periodo oficial = hojeApenas
                ? new TemposHojeJob.Periodo(hoje.porAtendente(), hoje.atendimentos())
                : referencia.periodo();
        if (calc == null || oficial == null) {
            throw new AuthException(503, "O TMEA pela regra nova ainda está sendo calculado. Tente em alguns minutos.");
        }

        // Média do setor pela regra nova (últimos dias): cada colega pesa igual, como na regra oficial.
        Map<String, double[]> porSetor = new LinkedHashMap<>(); // setor -> {soma das médias, n}
        Map<String, TmeaNovaRegra.Resultado> ref = base == null ? Map.of() : base.porAtendente();
        ref.forEach((nome, r) -> {
            String setor = referencia.setor(nome);
            if (r.intervalos() >= 10 && setor != null) {
                double[] a = porSetor.computeIfAbsent(setor, k -> new double[2]);
                a[0] += r.segundosMedios();
                a[1]++;
            }
        });

        List<Map<String, Object>> atendentes = new ArrayList<>();
        calc.porAtendente().forEach((nome, r) -> {
            if ("null".equalsIgnoreCase(nome.trim())) {
                return;
            }
            String setor = referencia.setor(nome);
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("nome", nome);
            item.put("setor", setor);
            item.put("atendimentos", oficial.atendimentos().getOrDefault(nome, 0L));
            item.put("nova", r);
            item.put("atual", oficial.porAtendente().getOrDefault(nome, Map.of()).get(TemposHojeJob.TMEA));
            double[] a = setor == null ? null : porSetor.get(setor);
            TmeaNovaRegra.Resultado proprio = ref.get(nome);
            if (a != null) {
                // Tira a própria pessoa da média do setor.
                double soma = a[0];
                double n = a[1];
                if (proprio != null && proprio.intervalos() >= 10) {
                    soma -= proprio.segundosMedios();
                    n--;
                }
                if (n > 0) {
                    item.put("referencia", Map.of("setor", setor, "segundosMedios", soma / n,
                            "atendentes", (int) n, "dias", tmeaNova.dias()));
                }
            }
            atendentes.add(item);
        });

        Map<String, Object> resp = new LinkedHashMap<>();
        resp.put("sistema", groupProperties.groupName());
        resp.put("periodo", hojeApenas ? "hoje" : "30d");
        resp.put("dias", hojeApenas ? 1 : tmeaNova.dias());
        resp.put("formula", widgetProperties.getTmea().getFormula());
        resp.put("corteAtualMinutos", widgetProperties.getTmea().getMaxIntervaloMinutos());
        resp.put("maxAndamentoMinutos", TmeaNovaRegra.MAX_ANDAMENTO_SEGUNDOS / 60);
        resp.put("minIntervalosReferencia", 10);
        resp.put("atendentes", atendentes);
        resp.put("calculadoEm", calc.calculadoEm());
        return resp;
    }

    /**
     * Tab "Pausas e comportamentos": per atendente, the {@link PausasComportamento} numbers plus the
     * long idle stretches of the new TMEA rule ({@link TmeaNovaRegra}); {@code limites} are the per-day
     * rates from which the screen flags an atendente (value / days worked >= limit).
     */
    @GetMapping("/ext/ofensores/pausas")
    public Map<String, Object> pausas(@RequestAttribute(ExtAuthInterceptor.EMAIL_ATTR) String email,
                                      @RequestParam(defaultValue = "hoje") String periodo) {
        requireAdmin(email);
        boolean hojeApenas = !"30d".equals(periodo);
        PausasComportamento.Calculo calc = hojeApenas ? pausas.hoje() : pausas.ultimosDias();
        TmeaNovaRegra.Calculo ocio = hojeApenas ? tmeaNova.hoje() : tmeaNova.ultimosDias();
        if (calc == null) {
            throw new AuthException(503, "As pausas ainda estão sendo calculadas. Tente em alguns minutos.");
        }
        List<Map<String, Object>> atendentes = new ArrayList<>();
        calc.porAtendente().forEach((nome, a) -> {
            if ("null".equalsIgnoreCase(nome.trim()) || a.atendimentos == 0) {
                return;
            }
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("nome", nome);
            item.put("setor", referencia.setor(nome));
            item.put("dados", a);
            TmeaNovaRegra.Resultado r = ocio == null ? null : ocio.porAtendente().get(nome);
            item.put("ociosoLongo", r == null ? 0 : r.longos());
            item.put("maiorOcioso", r == null ? null : r.maiorOciosoSegundos());
            atendentes.add(item);
        });
        List<Map<String, Object>> tipos = new ArrayList<>();
        calc.porTipo().forEach((tipo, t) -> tipos.add(Map.of("tipo", tipo, "dados", t)));

        Map<String, Object> resp = new LinkedHashMap<>();
        resp.put("sistema", groupProperties.groupName());
        resp.put("periodo", hojeApenas ? "hoje" : "30d");
        resp.put("dias", hojeApenas ? 1 : pausas.dias());
        resp.put("indicadores", calc.indicadores());
        resp.put("curtosRotulo", calc.curtosRotulo());
        resp.put("tmaLimite", calc.tmaLimite());
        resp.put("longaFator", PausasComportamento.LONGA_FATOR);
        resp.put("encadeadaSegundos", PausasComportamento.ENCADEADA_SEGUNDOS);
        resp.put("relampagoSegundos", PausasComportamento.RELAMPAGO_SEGUNDOS);
        resp.put("ociosoLongoMinutos", TmeaNovaRegra.LONGO_SEGUNDOS / 60);
        // Por dia trabalhado (transferidas: % dos atendimentos; internas: minutos por dia).
        Map<String, Number> limites = new LinkedHashMap<>();
        limites.put("excedidas", 2);
        limites.put("encadeadas", 2);
        limites.put("relampago", 3);
        limites.put("semFim", 1);
        limites.put("motivoGenerico", 3);
        limites.put("ociosoLongo", 1);
        limites.put("curtos", 3);
        limites.put("longos", 1);
        limites.put("sessoes", 4);
        limites.put("transferidasPct", 30);
        limites.put("internasMin", 30);
        resp.put("limites", limites);
        resp.put("atendentes", atendentes);
        resp.put("tipos", tipos);
        // Tempo logado de todos com sessão no período (inclusive quem não atendeu): a tela junta com o
        // do outro sistema pela correspondência de nomes.
        resp.put("logados", calc.logados());
        resp.put("logadoEstimado", calc.logadoEstimado());
        resp.put("maxSessaoAbertaHoras", TempoLogado.MAX_ABERTA_SEGUNDOS / 3600);
        resp.put("calculadoEm", calc.calculadoEm());
        return resp;
    }

    private void requireAdmin(String email) {
        if (!UsuarioRepository.ADMIN.equals(usuarios.find(email).role())) {
            throw new AuthException(403, "Somente administradores.");
        }
    }
}
