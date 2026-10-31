package com.sebratel.dashboards.common.cliente;

import com.fasterxml.jackson.databind.JsonNode;
import com.sebratel.dashboards.common.cliente.PerfilClienteFonte.Identidade;
import com.sebratel.dashboards.common.cliente.ResumoClienteCalculadora.Nota;
import com.sebratel.dashboards.common.cliente.ResumoClienteCalculadora.Protocolo;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Lê os protocolos (db_matrix) e as notas da pesquisa (db_matrix_avaliations) do cliente dos últimos 12 meses e entrega
 * o resumo de {@link ResumoClienteCalculadora}, junto do que o cadastro do ERP e o histórico de massivas dizem dele
 * ({@link PerfilClienteFonte}). O cliente é achado pelo CPF/CNPJ <b>ou</b> por qualquer telefone do cadastro, então os
 * protocolos abertos de outro número dele também entram. Só existe onde {@code app.cliente-resumo.ativo=true} (a
 * Matrix); o resultado não leva nome, endereço, documento nem telefone de volta.
 */
@Service
@ConditionalOnProperty(prefix = "app.cliente-resumo", name = "ativo", havingValue = "true")
public class ClienteResumoService {

    static final int MESES = 12;
    static final int LIMITE = 1000;
    private static final ZoneId ZONA = ZoneId.of("America/Sao_Paulo");

    private final JdbcTemplate jdbc;
    private final PerfilClienteFonte fonte;

    public ClienteResumoService(JdbcTemplate jdbc, PerfilClienteFonte fonte) {
        this.jdbc = jdbc;
        this.fonte = fonte;
    }

    /** Resumo por CPF/CNPJ (só dígitos ou formatado) e/ou telefone; null se nenhum identificador for válido. */
    public Map<String, Object> resumir(String documento, String telefone) {
        Identidade id = fonte.resolver(documento, telefone);
        if (id.vazia()) {
            return null;
        }
        Set<String> fones = formasNaMatrix(id.telefones());
        LocalDateTime agora = LocalDateTime.now(ZONA);
        Timestamp desde = Timestamp.valueOf(agora.minusMonths(MESES));
        Map<String, Object> r = ResumoClienteCalculadora.calcular(
                protocolos(id.documentos(), fones, desde), notas(id.documentos(), fones, desde), agora);
        r.put("cadastro", cadastro(id.perfil(), id.telefones().size()));
        Map<String, Object> massivas = massivas(id.perfil());
        if (massivas != null) {
            r.put("massivas", massivas);
        }
        return r;
    }

    private static Map<String, Object> cadastro(JsonNode perfil, int telefonesConhecidos) {
        Map<String, Object> c = new LinkedHashMap<>();
        c.put("encontrado", perfil != null && perfil.path("registros_people").asInt(0) > 0);
        c.put("registros_people", perfil == null ? 0 : perfil.path("registros_people").asInt(0));
        c.put("contratos", perfil == null ? 0 : perfil.path("contratos").asInt(0));
        c.put("telefones_vinculados", telefonesConhecidos);
        return c;
    }

    private static Map<String, Object> massivas(JsonNode perfil) {
        JsonNode m = perfil == null ? null : perfil.get("massivas");
        if (m == null || m.isNull()) {
            return null;
        }
        Map<String, Object> r = new LinkedHashMap<>();
        r.put("ultimos_30_dias", m.path("ultimos_30_dias").asLong());
        r.put("ultimos_90_dias", m.path("ultimos_90_dias").asLong());
        r.put("ultimos_12_meses", m.path("ultimos_12_meses").asLong());
        r.put("ultima_em", m.hasNonNull("ultima_em") ? m.get("ultima_em").asText() : null);
        List<Map<String, Object>> assuntos = new ArrayList<>();
        m.path("assuntos").forEach(a -> assuntos.add(Map.of("titulo", a.path("titulo").asText(), "vezes", a.path("vezes").asLong())));
        r.put("assuntos", assuntos);
        return r;
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
        return jdbc.query("SELECT dat_resposta, nom_valor, num_protocolo FROM db_matrix_avaliations WHERE (" + quem
                + ") AND dat_resposta >= ? AND nom_valor REGEXP '^[1-5]$' ORDER BY dat_resposta DESC LIMIT " + LIMITE,
                (rs, i) -> new Nota(local(rs.getTimestamp("dat_resposta")), Integer.parseInt(rs.getString("nom_valor")),
                        rs.getString("num_protocolo")),
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

    /**
     * Formas em que a Matrix pode ter o telefone canônico: com e sem 55 e, para celular, com e sem o nono dígito
     * (cadastros antigos de WhatsApp não têm).
     */
    static Set<String> formasNaMatrix(Set<String> canonicos) {
        Set<String> v = new LinkedHashSet<>();
        for (String c : canonicos) {
            v.addAll(variantesTelefone(c));
            if (c.length() == 11 && c.charAt(2) == '9') {
                v.addAll(variantesTelefone(c.substring(0, 2) + c.substring(3)));
            }
        }
        return v;
    }
}
