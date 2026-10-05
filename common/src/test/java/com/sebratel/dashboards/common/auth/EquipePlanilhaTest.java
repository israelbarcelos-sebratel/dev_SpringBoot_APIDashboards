package com.sebratel.dashboards.common.auth;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;

import java.sql.Timestamp;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** Sheet names (full) against system names (short, with the sector), as in "Agentes e horários Suporte". */
class EquipePlanilhaTest {

    private EquipePlanilha equipe;

    private static EquipePlanilha.Pessoa p(String nome, String supervisor, String turno) {
        return new EquipePlanilha.Pessoa(nome, null, null, supervisor, turno);
    }

    @BeforeEach
    @SuppressWarnings("unchecked")
    void carregar() {
        JdbcTemplate db = mock(JdbcTemplate.class);
        List<EquipePlanilha.Pessoa> planilha = List.of(
                p("Bruno Diaz Braga", "Bruna Machado", "Manhã"),
                p("Dionatan Ernane H. Vedoy", "Bruna Machado", "Manhã"),
                p("Matheus Christian R.Dos Santos", "Ana Lucia Costa", "Madrugada"),
                p("Rafael Silva Da Silva", "Ana Lucia Costa", "Tarde"),
                p("Daniel De Oliveira De Lima", "Ana Lucia Costa", "Madrugada"),
                p("Daniel Da Silva Lopes", "Fabiano Alves Madruga", "Tarde"),
                p("Luiz Gabriel Cougo Ribeiro", "Ana Lucia Costa", "Madrugada"),
                p("Luis Ricardo Ribeiro da Silva", "Ana Lucia Costa", "Tarde"),
                p("Gabriel Oliveira Da Silva", "Bruna Machado", "Manhã"),
                p("Herick Eduardo Dos Santos", "Fabiano Alves Madruga", "Tarde"),
                p("Ivan Felipe Cavalheiro Da Costa", "Ana Lucia Costa", "Madrugada"),
                p("Robson Ferraz", "Fabiano Alves Madruga", "Intermediário"),
                p("Marcos Eduardo Dias", "Bruna Machado", "Manhã"),
                p("Pedro Henrique Araújo Pires", "Bruna Machado", "Manhã"),
                p("Pedro Henrique L. Barbosa", "Fabiano Alves Madruga", "Intermediário"));
        when(db.query(anyString(), any(RowMapper.class))).thenReturn((List) planilha);
        when(db.queryForObject(anyString(), eq(Timestamp.class))).thenReturn(Timestamp.valueOf("2026-10-05 06:00:00"));
        equipe = new EquipePlanilha(db);
        equipe.recarregar();
    }

    private String turno(String nomeSistema) {
        EquipePlanilha.Pessoa p = equipe.de(nomeSistema);
        return p == null ? null : p.turno();
    }

    @Test
    void casaNomeCurtoComCompleto() {
        assertEquals("Manhã", turno("Bruno Braga - Suporte Técnico"));
        assertEquals("Manhã", turno("Dionatan Vedoy - Suporte Técnico"));
        assertEquals("Madrugada", turno("Matheus Santos - Suporte Técnico"));
        assertEquals("Tarde", turno("Rafael Silva - Suporte Técnico"));
        assertEquals("Tarde", turno("Herick Santos - Suporte Técnico"));
        assertEquals("Madrugada", turno("Ivan Costa - Suporte Técnico"));
        assertEquals("Intermediário", turno("Robson Ferraz - Suporte Técnico"));
        assertEquals("Manhã", turno("Gabriel Da Silva - Suporte Técnico"));
        assertEquals("Fabiano Alves Madruga", equipe.de("Herick Santos - Suporte Técnico").supervisor());
    }

    @Test
    void setorAntesDoNomeEAcentos() {
        assertEquals("Manhã", turno("Suporte Técnico - Bruno Braga"));
        assertEquals("Manhã", turno("BRUNO  BRAGA - Suporte Tecnico"));
    }

    @Test
    void naoConfundePessoasParecidas() {
        assertEquals("Madrugada", turno("Daniel Lima - Suporte Técnico"));   // não o Daniel Lopes
        assertEquals("Madrugada", turno("Luiz Ribeiro - Suporte Técnico"));  // não o Luis Ricardo Ribeiro
        assertNull(turno("Marcio Dias - Suporte Técnico"));                    // Marcos ≠ Marcio
        assertNull(turno("Pedro Henrique - Suporte Técnico"));                 // dois Pedro Henrique: nenhum
        assertNull(turno("Cezar Goulart - Suporte Técnico"));                  // não está na planilha
        assertNull(turno("Ana Pires - Vendas Interno"));
        assertNull(turno("Daniel Silva - Backoffice"));                        // não é o Daniel Da Silva Lopes
        assertNull(turno("Backoffice - Daniel da Silva"));
        assertNull(turno("Rafael Silva - COR"));                               // não é o Rafael Silva Da Silva
    }

    @Test
    void sobrenomeAbreviadoNaPlanilha() {
        assertEquals("Intermediário", turno("Pedro Lacerda - Suporte Técnico"));  // Pedro Henrique L. Barbosa
        assertEquals("Manhã", turno("Pedro Pires - Suporte Técnico"));             // o outro Pedro
        assertEquals("Manhã", turno("Dionatan Hoffmann - Suporte Técnico"));       // Dionatan Ernane H. Vedoy
        assertNull(turno("Pedro Henrique - Suporte Técnico"));                      // os dois Pedro Henrique: nenhum
    }

    @Test
    void inicialGrudadaNoPrimeiroNome() {
        // "Pedroh" = Pedro H(enrique): é o Pedro Henrique Araújo Pires, não o L. Barbosa
        assertEquals("Bruna Machado", equipe.de("Pedroh Pires - Suporte Técnico").supervisor());
        assertEquals("Fabiano Alves Madruga", equipe.de("Pedroh Barbosa - Suporte Técnico").supervisor());
        assertNull(turno("Pedrox Pires - Suporte Técnico"));                         // inicial que não é a do 2º nome
        assertNull(turno("Pedroh - Suporte Técnico"));                               // sem sobrenome: os dois Pedro Henrique
    }

    @Test
    void soPrimeiroNomeNaMatrix() {
        assertEquals("Madrugada", turno("Ivan  - Suporte Técnico"));          // um Ivan só na planilha
        assertEquals("Intermediário", turno("Róbson - Suporte Técnico"));     // acento não importa
        assertNull(turno("Daniel - Suporte Técnico"));                          // dois Daniel: nenhum
        assertNull(turno("Pedro - Suporte Técnico"));                           // dois Pedro: nenhum
        assertNull(turno("Ivan - Financeiro"));                                 // a planilha é só do suporte
        assertNull(turno("Fernando - Suporte Técnico"));                        // não está na planilha
    }
}
