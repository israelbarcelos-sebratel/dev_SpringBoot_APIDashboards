package com.sebratel.dashboards.common.cliente;

import com.sebratel.dashboards.common.cliente.ResumoClienteCalculadora.Nota;
import com.sebratel.dashboards.common.cliente.ResumoClienteCalculadora.Protocolo;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Lê os protocolos (db_matrix) e as notas da pesquisa (db_matrix_avaliations) do cliente dos últimos 12 meses e entrega
 * o resumo de {@link ResumoClienteCalculadora}. Só existe onde {@code app.cliente-resumo.ativo=true} (a Matrix); o
 * resultado não leva nome, endereço nem o documento de volta.
 */
@Service
@ConditionalOnProperty(prefix = "app.cliente-resumo", name = "ativo", havingValue = "true")
public class ClienteResumoService {

    static final int MESES = 12;
    static final int LIMITE = 1000;
    private static final ZoneId ZONA = ZoneId.of("America/Sao_Paulo");

    private final JdbcTemplate jdbc;

    public ClienteResumoService(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** Resumo por CPF/CNPJ (só dígitos ou formatado) e/ou telefone; null se nenhum identificador for válido. */
    public Map<String, Object> resumir(String documento, String telefone) {
        Set<String> docs = variantesDocumento(documento);
        Set<String> fones = variantesTelefone(telefone);
        if (docs.isEmpty() && fones.isEmpty()) {
            return null;
        }
        LocalDateTime agora = LocalDateTime.now(ZONA);
        Timestamp desde = Timestamp.valueOf(agora.minusMonths(MESES));
        return ResumoClienteCalculadora.calcular(protocolos(docs, fones, desde), notas(docs, fones, desde), agora);
    }

    private List<Protocolo> protocolos(Set<String> docs, Set<String> fones, Timestamp desde) {
        List<Object> args = new ArrayList<>();
        String quem = filtro(docs, fones, "cpf", "num_telefone", args);
        args.add(desde);
        return jdbc.query("SELECT protocolo, data_entrada, servico, tags, classificacao FROM db_matrix WHERE (" + quem
                + ") AND data_entrada >= ? AND ativo_receptivo = 'Receptivo' ORDER BY data_entrada DESC LIMIT " + LIMITE,
                (rs, i) -> new Protocolo(rs.getString("protocolo"), local(rs.getTimestamp("data_entrada")),
                        rs.getString("servico"), rs.getString("tags"), rs.getString("classificacao")),
                args.toArray());
    }

    private List<Nota> notas(Set<String> docs, Set<String> fones, Timestamp desde) {
        List<Object> args = new ArrayList<>();
        String quem = filtro(docs, fones, "num_cpf", "num_telefone", args);
        args.add(desde);
        return jdbc.query("SELECT dat_resposta, nom_valor FROM db_matrix_avaliations WHERE (" + quem
                + ") AND dat_resposta >= ? AND nom_valor REGEXP '^[1-5]$' ORDER BY dat_resposta DESC LIMIT " + LIMITE,
                (rs, i) -> new Nota(local(rs.getTimestamp("dat_resposta")), Integer.parseInt(rs.getString("nom_valor"))),
                args.toArray());
    }

    private static LocalDateTime local(Timestamp t) {
        return t == null ? null : t.toLocalDateTime();
    }

    /** "(col IN (?,?) OR col2 IN (?,?))" só com os identificadores informados; os valores vão em {@code args}. */
    private static String filtro(Set<String> docs, Set<String> fones, String colDoc, String colFone, List<Object> args) {
        List<String> partes = new ArrayList<>();
        if (!docs.isEmpty()) {
            partes.add(colDoc + " IN (" + marcas(docs.size()) + ")");
            args.addAll(docs);
        }
        if (!fones.isEmpty()) {
            partes.add(colFone + " IN (" + marcas(fones.size()) + ")");
            args.addAll(fones);
        }
        return String.join(" OR ", partes);
    }

    private static String marcas(int n) {
        return String.join(",", java.util.Collections.nCopies(n, "?"));
    }

    /**
     * CPF/CNPJ como está em cada tabela: a Matrix guarda com zeros à esquerda (11/14 dígitos) e a pesquisa os perde,
     * então vale a forma completa e a sem zeros.
     */
    static Set<String> variantesDocumento(String documento) {
        String d = documento == null ? "" : documento.replaceAll("\\D", "");
        Set<String> v = new LinkedHashSet<>();
        if (d.length() < 5 || d.length() > 14) {
            return v;
        }
        String semZeros = d.replaceFirst("^0+", "");
        v.add(semZeros);
        int alvo = semZeros.length() <= 11 ? 11 : 14;
        v.add(("0".repeat(alvo) + semZeros).substring(semZeros.length()));
        return v;
    }

    /** Telefone com e sem o 55 (a Matrix grava com DDI). */
    static Set<String> variantesTelefone(String telefone) {
        String t = telefone == null ? "" : telefone.replaceAll("\\D", "");
        Set<String> v = new LinkedHashSet<>();
        if (t.length() < 10) {
            return v;
        }
        String sem55 = t.startsWith("55") && t.length() >= 12 ? t.substring(2) : t;
        v.add(sem55);
        v.add("55" + sem55);
        return v;
    }
}
