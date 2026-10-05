package com.sebratel.dashboards.common.auth;

import com.sebratel.dashboards.common.config.DataSourcesConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.ConnectionCallback;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.sql.PreparedStatement;
import java.sql.Statement;
import java.sql.Timestamp;
import java.text.Normalizer;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Support team sheet ("Agentes e horários Suporte"): shift, working hours and supervisor of each
 * person. n8n reads the sheet once a day and replaces the whole table ({@link EquipeController}); both
 * APIs read it from app-db ({@code equipe_planilha}) every 10 minutes.
 *
 * <p>The sheet has full names ("Bruno Diaz Braga") and the systems short ones with the sector
 * ("Bruno Braga - Suporte Técnico"). Same rule as {@link CorrespondenciaNomes}: ignoring accents,
 * case and the particles da/de/do/das/dos/e, the first name is the same and every word of the system
 * name is in the sheet name (or abbreviated there: "L." for "Lacerda"). Several candidates: the one
 * with fewest extra words; a tie, none. The sheet is the support team, so a system name from another
 * sector ("Daniel Silva - Backoffice") never matches.
 * A first name alone ("Fernando - Suporte Técnico", as the Matrix has some) only matches when the
 * system name is from support (the sheet is the support team) and one person in the sheet has it.
 */
@Component
public class EquipePlanilha {

    private static final Logger log = LoggerFactory.getLogger(EquipePlanilha.class);
    private static final Set<String> PARTICULAS = Set.of("da", "de", "do", "das", "dos", "e");

    public record Pessoa(String nome, String inicio, String fim, String supervisor, String turno) {}

    private record Carga(List<Pessoa> pessoas, LocalDateTime atualizadoEm) {}

    private final JdbcTemplate db;
    private volatile Carga carga = new Carga(List.of(), null);
    /** System name -> person (or {@link #NINGUEM}), memoized until the next reload. */
    private final Map<String, Pessoa> memo = new ConcurrentHashMap<>();
    private static final Pessoa NINGUEM = new Pessoa("", null, null, null, null);

    public EquipePlanilha(@Qualifier(DataSourcesConfig.APP) JdbcTemplate db) {
        this.db = db;
    }

    @Scheduled(initialDelay = 5_000, fixedRate = 600_000)
    public void recarregar() {
        try {
            List<Pessoa> pessoas = db.query("SELECT nome, inicio, fim, supervisor, turno FROM equipe_planilha",
                    (rs, i) -> new Pessoa(rs.getString(1), rs.getString(2), rs.getString(3), rs.getString(4), rs.getString(5)));
            Timestamp em = db.queryForObject("SELECT MAX(atualizado_em) FROM equipe_planilha", Timestamp.class);
            carga = new Carga(List.copyOf(pessoas), em == null ? null : em.toLocalDateTime());
            memo.clear();
        } catch (DataAccessException e) {
            log.warn("Não foi possível ler a equipe da planilha: {}", e.getMessage());
        }
    }

    /** Replaces the whole sheet in one transaction (an error keeps the previous content). */
    public int substituir(List<Pessoa> pessoas) {
        LocalDateTime agora = LocalDateTime.now();
        db.execute((ConnectionCallback<Void>) con -> {
            boolean auto = con.getAutoCommit();
            con.setAutoCommit(false);
            try (Statement st = con.createStatement();
                 PreparedStatement ps = con.prepareStatement("INSERT INTO equipe_planilha (nome, inicio, fim, supervisor, turno,"
                         + " atualizado_em) VALUES (?, ?, ?, ?, ?, ?) ON DUPLICATE KEY UPDATE inicio = VALUES(inicio),"
                         + " fim = VALUES(fim), supervisor = VALUES(supervisor), turno = VALUES(turno),"
                         + " atualizado_em = VALUES(atualizado_em)")) {
                st.executeUpdate("DELETE FROM equipe_planilha");
                for (Pessoa p : pessoas) {
                    ps.setString(1, p.nome());
                    ps.setString(2, p.inicio());
                    ps.setString(3, p.fim());
                    ps.setString(4, p.supervisor());
                    ps.setString(5, p.turno());
                    ps.setTimestamp(6, Timestamp.valueOf(agora));
                    ps.addBatch();
                }
                ps.executeBatch();
                con.commit();
            } catch (Exception e) {
                con.rollback();
                throw e;
            } finally {
                con.setAutoCommit(auto);
            }
            return null;
        });
        recarregar();
        return pessoas.size();
    }

    /** Everyone in the sheet (support team), as last received. */
    public List<Pessoa> pessoas() {
        return carga.pessoas();
    }

    public LocalDateTime atualizadoEm() {
        return carga.atualizadoEm();
    }

    /** The sheet row of this system name ("Bruno Braga - Suporte Técnico"), or null. */
    public Pessoa de(String nomeSistema) {
        if (nomeSistema == null || carga.pessoas().isEmpty()) {
            return null;
        }
        Pessoa p = memo.computeIfAbsent(nomeSistema, this::procurar);
        return p == NINGUEM ? null : p;
    }

    private Pessoa procurar(String nomeSistema) {
        Pessoa melhor = null;
        int menosSobra = Integer.MAX_VALUE;
        boolean empate = false;
        String[] partes = nomeSistema.trim().split("\\s*-\\s*");
        if (partes.length > 1 && !doSuporte(partes)) {
            return NINGUEM; // "Daniel Silva - Backoffice" não é o Daniel Da Silva Lopes do suporte
        }
        for (String pessoa : partes) {
            List<String> curto = palavras(pessoa);
            if (curto.size() < 2) {
                continue; // a parte do setor ("Suporte Técnico" também tem 2 palavras, mas não casa com ninguém)
            }
            for (Pessoa p : carga.pessoas()) {
                List<String> longo = palavras(p.nome());
                List<String> c = inicialGrudada(longo, curto);
                if (longo.isEmpty() || !longo.get(0).equals(c.get(0)) || !contem(longo, c)) {
                    continue;
                }
                int sobra = longo.size() - c.size();
                if (sobra < menosSobra) {
                    melhor = p;
                    menosSobra = sobra;
                    empate = false;
                } else if (sobra == menosSobra && !p.equals(melhor)) {
                    empate = true;
                }
            }
        }
        if (melhor == null) {
            melhor = soPrimeiroNome(partes);
        }
        return melhor == null || empate ? NINGUEM : melhor;
    }

    /**
     * Every word of the system name is in the sheet name, or abbreviated there by its initial
     * ("Pedro Lacerda" in "Pedro Henrique L. Barbosa"); each sheet word is used once.
     */
    static boolean contem(List<String> longo, List<String> curto) {
        List<String> resto = new ArrayList<>(longo);
        for (String w : curto) {
            if (!resto.remove(w) && !resto.remove(w.substring(0, 1))) {
                return false;
            }
        }
        return true;
    }

    /**
     * "Pedroh Pires" for "Pedro Henrique Araújo Pires": the system's first name is the sheet's first name
     * with the initial of the second one glued to it; read it as both names. Otherwise unchanged.
     */
    static List<String> inicialGrudada(List<String> longo, List<String> curto) {
        if (longo.size() < 2 || longo.get(0).equals(curto.get(0))
                || !curto.get(0).equals(longo.get(0) + longo.get(1).charAt(0))) {
            return curto;
        }
        List<String> c = new ArrayList<>(List.of(longo.get(0), longo.get(1)));
        c.addAll(curto.subList(1, curto.size()));
        return c;
    }

    /** The sheet is the support team: some part of the system name ("Suporte Técnico") says so. */
    private static boolean doSuporte(String[] partes) {
        for (String parte : partes) {
            if (palavras(parte).contains("suporte")) {
                return true;
            }
        }
        return false;
    }

    /** "Fernando - Suporte Técnico": the only person in the sheet with that first name, or null. */
    private Pessoa soPrimeiroNome(String[] partes) {
        List<String> nome = palavras(partes[0]);
        if (nome.size() != 1 || !doSuporte(partes)) {
            return null;
        }
        Pessoa unica = null;
        for (Pessoa p : carga.pessoas()) {
            List<String> longo = palavras(p.nome());
            if (!longo.isEmpty() && longo.get(0).equals(nome.get(0))) {
                if (unica != null) {
                    return null; // dois com o mesmo primeiro nome: não dá para saber quem é
                }
                unica = p;
            }
        }
        return unica;
    }

    static List<String> palavras(String nome) {
        String s = Normalizer.normalize(nome, Normalizer.Form.NFD).replaceAll("\\p{M}", "")
                .toLowerCase(Locale.ROOT).replace('.', ' ');
        List<String> r = new ArrayList<>();
        for (String t : s.trim().split("\\s+")) {
            if (!t.isBlank() && !PARTICULAS.contains(t)) {
                r.add(t);
            }
        }
        return r;
    }
}
