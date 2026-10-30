package com.sebratel.dashboards.common.cliente;

import com.sebratel.dashboards.common.cliente.ResumoClienteCalculadora.Nota;
import com.sebratel.dashboards.common.cliente.ResumoClienteCalculadora.Protocolo;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ResumoClienteCalculadoraTest {

    private static final LocalDateTime AGORA = LocalDateTime.of(2026, 10, 30, 12, 0);
    private static final String LENTA = "ISA_SUPORTE_TÉCNICO,ISA_CLIENTE_CONEXÃO_LENTA,ISA_REBOOT_REALIZADO";

    private static Protocolo p(int diasAtras, String servico, String tags) {
        return new Protocolo("P" + diasAtras + servico + tags + Math.random(), AGORA.minusDays(diasAtras), servico, tags, null);
    }

    private static List<Protocolo> espalhados(int n, int dias, String tags) {
        List<Protocolo> l = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            l.add(p(1 + i * (dias - 1) / Math.max(1, n - 1), "17295-SEBRATEL-SUPORTE", tags));
        }
        return l;
    }

    @Test
    void cincoProtocolosEmDuasSemanasPesamMaisQueVinteEmDoisAnos() {
        Map<String, Object> recente = ResumoClienteCalculadora.calcular(espalhados(5, 14, LENTA), List.of(), AGORA);
        Map<String, Object> antigo = ResumoClienteCalculadora.calcular(espalhados(20, 360, LENTA), List.of(), AGORA);
        assertTrue((int) recente.get("nivel") >= 2, "5 em 14 dias = recorrente ou pior");
        assertTrue((int) antigo.get("nivel") <= 1, "20 em 12 meses espalhados no máximo pede atenção");
        assertTrue((double) recente.get("pontuacao") > (double) antigo.get("pontuacao"));
    }

    @Test
    void frasePrincipalUsaMenorJanelaComDoisOuMais() {
        Map<String, Object> r = ResumoClienteCalculadora.calcular(espalhados(5, 14, LENTA), List.of(), AGORA);
        assertEquals(7, r.get("janela_principal_dias"));
        String resumo = (String) r.get("resumo");
        assertTrue(resumo.contains("já abriu 3 protocolos nos últimos 7 dias"), resumo);
        assertTrue(resumo.contains("lentidão"), resumo);
    }

    @Test
    void problemaTecnicoRecorrenteSugereVisita() {
        Map<String, Object> r = ResumoClienteCalculadora.calcular(espalhados(4, 20, LENTA), List.of(), AGORA);
        assertTrue(((String) r.get("resumo")).contains("visita técnica"));
    }

    @Test
    void semProtocolosOuUmSo() {
        Map<String, Object> vazio = ResumoClienteCalculadora.calcular(List.of(), List.of(), AGORA);
        assertEquals(0, vazio.get("nivel"));
        assertNull(vazio.get("ultimo_contato_dias"));
        assertTrue(((String) vazio.get("resumo")).contains("Não há protocolos"));
        Map<String, Object> um = ResumoClienteCalculadora.calcular(List.of(p(10, "17295-SEBRATEL-SUPORTE", LENTA)), List.of(), AGORA);
        assertNull(um.get("janela_principal_dias"));
        assertTrue(((String) um.get("resumo")).contains("1 protocolo"));
    }

    @Test
    void campanhaEMarketingNaoContam() {
        List<Protocolo> l = List.of(p(1, "SEBRATEL - CAOC", "caoc_collection_automation_hsm"),
                p(2, "17295-SEBRATEL COMERCIAL VENDAS RECEPTIVAS", "MARKETING_SITE_ORGANICO_PLANOS"));
        assertEquals(0, ResumoClienteCalculadora.calcular(l, List.of(), AGORA).get("total_12_meses"));
    }

    @Test
    void protocoloRepetidoContaUmaVez() {
        Protocolo a = new Protocolo("X1", AGORA.minusDays(1), "17295-SEBRATEL-SUPORTE", LENTA, null);
        assertEquals(1, ResumoClienteCalculadora.calcular(List.of(a, a), List.of(), AGORA).get("total_12_meses"));
    }

    @Test
    void csatBaixoEClienteRecorrenteViraAlerta() {
        List<Nota> notas = List.of(new Nota(AGORA.minusDays(2), 1), new Nota(AGORA.minusDays(5), 2), new Nota(AGORA.minusDays(9), 5));
        Map<String, Object> r = ResumoClienteCalculadora.calcular(espalhados(6, 14, LENTA), notas, AGORA);
        @SuppressWarnings("unchecked")
        Map<String, Object> csat = (Map<String, Object>) r.get("csat");
        assertEquals(2.7, csat.get("media"));
        assertEquals(3, csat.get("respostas"));
        assertTrue(((String) r.get("resumo")).contains("insatisfeito"));
    }

    @Test
    void temasPorPalavraChave() {
        assertEquals(List.of(TemaAtendimento.SEM_CONEXAO), TemaAtendimento.detectar("ISA_CLIENTE_SEM_CONEXÃO"));
        assertEquals(List.of(TemaAtendimento.LENTIDAO), TemaAtendimento.detectar("ISA_CLIENTE_CONEXÃO_LENTA"));
        assertEquals(List.of(TemaAtendimento.FINANCEIRO), TemaAtendimento.detectar("ISA_SEGUNDA_VIA_BOLETOS"));
        assertEquals(List.of(TemaAtendimento.SUPORTE), TemaAtendimento.detectar("17295-SEBRATEL-SUPORTE"));
        assertEquals(List.of(), TemaAtendimento.detectar("marketing_melhor_plano"));
        assertEquals(List.of(), TemaAtendimento.detectar("solos"), "'los' só vale como palavra inteira");
    }
}
