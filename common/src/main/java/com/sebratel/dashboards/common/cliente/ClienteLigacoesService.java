package com.sebratel.dashboards.common.cliente;

import com.sebratel.dashboards.common.cliente.PerfilClienteFonte.Identidade;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.sql.Timestamp;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Ligações do cliente na Native (db_native), por qualquer telefone do cadastro dele: quantas ele fez para a empresa
 * ({@code sentido = Recebida}), quantas ficaram sem atendimento e quantas a equipe fez para ele
 * ({@code Efetuada}), nos últimos 30 e 90 dias. Os números da Native vêm em formatos diferentes (com 0 na frente, com
 * ou sem DDD), então a comparação é pelos 8 últimos dígitos, que não mudam com o 9 do celular nem com o DDI. Só existe
 * onde {@code app.cliente-ligacoes.ativo=true} (a Native).
 */
@Service
@ConditionalOnProperty(prefix = "app.cliente-ligacoes", name = "ativo", havingValue = "true")
public class ClienteLigacoesService {

    static final int DIAS = 90;
    static final int LIMITE = 2000;
    private static final ZoneId ZONA = ZoneId.of("America/Sao_Paulo");

    private final JdbcTemplate jdbc;
    private final PerfilClienteFonte fonte;

    public ClienteLigacoesService(JdbcTemplate jdbc, PerfilClienteFonte fonte) {
        this.jdbc = jdbc;
        this.fonte = fonte;
    }

    /** Ligações por CPF/CNPJ e/ou telefone; null se não há como achar um telefone do cliente. */
    public Map<String, Object> resumir(String documento, String telefone) {
        Identidade id = fonte.resolver(documento, telefone);
        Set<String> finais = finais(id.telefones());
        if (finais.isEmpty()) {
            return null;
        }
        LocalDateTime agora = LocalDateTime.now(ZONA);
        List<Ligacao> ligacoes = jdbc.query(
                "SELECT data_hora, sentido, perdida, fila FROM db_native WHERE data_hora >= ? AND "
                        + "RIGHT(REGEXP_REPLACE(numero, '[^0-9]', ''), 8) IN (" + String.join(",", java.util.Collections.nCopies(finais.size(), "?"))
                        + ") ORDER BY data_hora DESC LIMIT " + LIMITE,
                (rs, i) -> new Ligacao(rs.getTimestamp("data_hora").toLocalDateTime(), rs.getString("sentido"),
                        rs.getBoolean("perdida"), rs.getString("fila")),
                montarArgs(agora.minusDays(DIAS), finais));
        return calcular(ligacoes, agora);
    }

    private static Object[] montarArgs(LocalDateTime desde, Set<String> finais) {
        List<Object> args = new ArrayList<>();
        args.add(Timestamp.valueOf(desde));
        args.addAll(finais);
        return args.toArray();
    }

    /** Últimos 8 dígitos de cada telefone canônico. */
    static Set<String> finais(Set<String> canonicos) {
        Set<String> f = new LinkedHashSet<>();
        for (String c : canonicos) {
            f.add(c.substring(c.length() - 8));
        }
        return f;
    }

    record Ligacao(LocalDateTime data, String sentido, boolean perdida, String fila) {
        boolean recebida() {
            return "Recebida".equalsIgnoreCase(sentido);
        }
    }

    static Map<String, Object> calcular(List<Ligacao> ligacoes, LocalDateTime agora) {
        Map<String, Object> r = new LinkedHashMap<>();
        for (int dias : new int[] {30, DIAS}) {
            LocalDateTime desde = agora.minusDays(dias);
            List<Ligacao> janela = ligacoes.stream().filter(l -> !l.data().isBefore(desde)).toList();
            Map<String, Object> j = new LinkedHashMap<>();
            j.put("recebidas", janela.stream().filter(Ligacao::recebida).count());
            j.put("sem_atendimento", janela.stream().filter(l -> l.recebida() && l.perdida()).count());
            j.put("efetuadas", janela.stream().filter(l -> !l.recebida()).count());
            r.put("ultimos_" + dias + "_dias", j);
        }
        Ligacao ultima = ligacoes.stream().filter(Ligacao::recebida).findFirst().orElse(null);
        r.put("ultima_recebida_ha_dias", ultima == null ? null : Duration.between(ultima.data(), agora).toDays());
        return r;
    }
}
