package com.sebratel.dashboards.common.auth;

import com.sebratel.dashboards.common.cache.ReferenciaMensalJob;
import com.sebratel.dashboards.common.cache.TemposHojeJob;
import com.sebratel.dashboards.common.config.TableGroupProperties;
import com.sebratel.dashboards.common.config.WidgetProperties;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RestController;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Admin screen "Maiores ofensores" of the Chrome extension ({@code ofensores.html}): today's TMA, TME
 * and TMEA of every atendente of this system, straight from the widget's in-memory snapshot
 * ({@link TemposHojeJob}, refreshed every minute), with each metric's limit and each atendente's
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

    public OfensoresController(UsuarioRepository usuarios, TemposHojeJob temposHoje, ReferenciaMensalJob referencia,
                               WidgetProperties widgetProperties, TableGroupProperties groupProperties) {
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
    public Map<String, Object> ofensores(@RequestAttribute(ExtAuthInterceptor.EMAIL_ATTR) String email) {
        if (!UsuarioRepository.ADMIN.equals(usuarios.find(email).role())) {
            throw new AuthException(403, "Somente administradores.");
        }
        TemposHojeJob.Snapshot hoje = temposHoje.hoje();
        List<Map<String, Object>> atendentes = new ArrayList<>();
        hoje.porAtendente().forEach((nome, tempos) -> {
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
            item.put("atendimentos", hoje.atendimentos().getOrDefault(nome, 0L));
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
        resp.put("metas", metas);
        resp.put("atendentes", atendentes);
        resp.put("ultimoRegistro", hoje.ultimoRegistro());
        resp.put("calculadoEm", hoje.calculadoEm());
        return resp;
    }
}
