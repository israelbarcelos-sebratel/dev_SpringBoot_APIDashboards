package com.sebratel.dashboards.common.cliente;

import com.sebratel.dashboards.common.cliente.ClienteLigacoesService.Ligacao;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ClienteLigacoesServiceTest {

    private static final LocalDateTime AGORA = LocalDateTime.of(2026, 10, 30, 12, 0);

    @Test
    void contaRecebidasPerdidasEEfetuadasPorJanela() {
        List<Ligacao> l = List.of(
                new Ligacao(AGORA.minusDays(1), "Recebida", false, "FILA_ATD_SUPORTE"),
                new Ligacao(AGORA.minusDays(3), "Recebida", true, "FILA_ATD_SUPORTE"),
                new Ligacao(AGORA.minusDays(4), "Efetuada", true, "FILA_ATD_COBRANÇA"),
                new Ligacao(AGORA.minusDays(60), "Recebida", false, "FILA_ATD_SUPORTE"));
        @SuppressWarnings("unchecked")
        Map<String, Object> d30 = (Map<String, Object>) ClienteLigacoesService.calcular(l, AGORA).get("ultimos_30_dias");
        assertEquals(2L, d30.get("recebidas"));
        assertEquals(1L, d30.get("sem_atendimento"));
        assertEquals(1L, d30.get("efetuadas"));
        @SuppressWarnings("unchecked")
        Map<String, Object> d90 = (Map<String, Object>) ClienteLigacoesService.calcular(l, AGORA).get("ultimos_90_dias");
        assertEquals(3L, d90.get("recebidas"));
        assertEquals(1L, ClienteLigacoesService.calcular(l, AGORA).get("ultima_recebida_ha_dias"));
    }

    @Test
    void csatDaNativeFicaSeparadoEPorAtendimento() {
        assertEquals(null, ClienteLigacoesService.resumoCsat(List.of()));
        Map<String, Object> c = ClienteLigacoesService.resumoCsat(List.of(
                new String[] {"4", "A"}, new String[] {"5", "A"}, new String[] {"2", "B"}));
        assertEquals(3.7, c.get("media"));
        assertEquals(3, c.get("respostas"));
        assertEquals(2, c.get("atendimentos"));
        assertEquals(4, c.get("ultima_nota"));
    }

    @Test
    void numeroDaNativeEmQualquerFormatoCasaPelosOitoUltimosDigitos() {
        Set<String> finais = ClienteLigacoesService.finais(new LinkedHashSet<>(List.of("51996772147", "5135233096")));
        for (String nativo : List.of("51996772147", "051996772147", "5551996772147", "0996772147")) {
            assertTrue(finais.contains(nativo.substring(nativo.length() - 8)), nativo);
        }
        assertTrue(finais.contains("35233096"));
    }

    @Test
    void identidadeCanonicaDescartaRamalEAnonimo() {
        Set<String> s = new LinkedHashSet<>();
        for (String t : List.of("5107", "anonymous", "0996772147", "+55 (51) 99677-2147", "051 3523-3096")) {
            PerfilClienteFonte.canonico(t, s);
        }
        assertEquals(List.of("51996772147", "5135233096"), List.copyOf(s));
    }

    @Test
    void matrixGuardaComESemDdiENonoDigito() {
        Set<String> f = ClienteResumoService.formasNaMatrix(Set.of("51996772147"));
        assertTrue(f.containsAll(Set.of("51996772147", "5551996772147", "5196772147", "555196772147")));
    }
}
