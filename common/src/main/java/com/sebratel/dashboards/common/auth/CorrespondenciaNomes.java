package com.sebratel.dashboards.common.auth;

import com.sebratel.dashboards.common.config.DataSourcesConfig;
import com.sebratel.dashboards.common.config.SemanticDomainProperties;
import com.sebratel.dashboards.common.config.SemanticDomainProperties.Domain;
import com.sebratel.dashboards.common.config.TableGroupProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.text.Normalizer;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Which Matrix atendente name is which Native one. The same person is often spelled differently in
 * the two systems ("Rafael da Silva - Suporte Técnico" vs "Rafael Silva - Suporte Técnico",
 * "Backoffice - Diego Barros" vs "Diego Barros - Backoffice", accents, double spaces), while the
 * e-mail binding ({@link VinculoMatrix}) only knows the Matrix names and the admin's "Ver dados de"
 * list comes from Native. Without this, whoever has a different spelling sees an empty section.
 *
 * <p>Automatic pairs: two names match when, ignoring accents, case, extra spaces and the particles
 * da/de/do/das/dos/e, the first name is the same and the words of one are all in the other. Among
 * several candidates the ones in the same sector win; several are kept only when they are the same
 * words in another format (one person with two cadastros), otherwise none. Rebuilt every 10 minutes
 * from the last 90 days of both tables (same database).
 *
 * <p>An admin corrects them on the "Relacionar nomes" screen ({@link CorrespondenciaController}),
 * saved in {@code correspondencia_nomes} (app-db, shared by both APIs, re-read every 30 s): a manual
 * pair replaces every automatic pair of its two names; a blocked pair removes one automatic pair.
 */
@Component
public class CorrespondenciaNomes {

    private static final Logger log = LoggerFactory.getLogger(CorrespondenciaNomes.class);
    private static final String DOMINIO = "atendimentos";
    private static final Set<String> PARTICULAS = Set.of("da", "de", "do", "das", "dos", "e");

    public static final String IGUAL = "igual";
    public static final String AUTO = "auto";
    public static final String MANUAL = "manual";
    public static final String BLOQUEADO = "bloqueado";

    /**
     * @param origem          {@link #IGUAL} (same spelling), {@link #AUTO}, {@link #MANUAL} (admin) or
     *                        {@link #BLOQUEADO} (automatic pair an admin removed — not in effect)
     * @param setorDiferente  the two names carry different sectors: probably a change of area, worth
     *                        a look
     */
    public record Par(String matrix, String nativo, String origem, boolean setorDiferente) {}

    private record Nomes(List<String> matrix, List<String> nativo, Set<String> setores) {}

    private final JdbcTemplate jdbcTemplate;
    private final JdbcTemplate appDb;
    private final SemanticDomainProperties props;
    private final boolean localMatrix;
    private final String dataColuna;
    private final String outraTabela;
    private final String outraColuna;
    private final String outraDataColuna;

    private volatile Nomes nomes = new Nomes(List.of(), List.of(), Set.of());
    /** Automatic pairs (including same-spelling ones), keyed matrix + "\0" + native. */
    private volatile Map<String, Par> automaticos = Map.of();
    /** Admin adjustments: key -> {@link #MANUAL} or {@link #BLOQUEADO}. */
    private volatile Map<String, String> ajustes = Map.of();
    /** Name of the other system -> this system's names, from the pairs in effect. */
    private volatile Map<String, List<String>> traducao = Map.of();

    public CorrespondenciaNomes(JdbcTemplate jdbcTemplate,
                                @Qualifier(DataSourcesConfig.APP) JdbcTemplate appDb,
                                SemanticDomainProperties props, TableGroupProperties groupProperties,
                                @Value("${app.widget.data-coluna:}") String dataColuna,
                                @Value("${app.vinculo.outro-sistema.tabela:}") String outraTabela,
                                @Value("${app.vinculo.outro-sistema.coluna:}") String outraColuna,
                                @Value("${app.vinculo.outro-sistema.data-coluna:}") String outraDataColuna) {
        this.jdbcTemplate = jdbcTemplate;
        this.appDb = appDb;
        this.props = props;
        this.localMatrix = "matrix".equals(groupProperties.groupName());
        this.dataColuna = dataColuna;
        this.outraTabela = outraTabela;
        this.outraColuna = outraColuna;
        this.outraDataColuna = outraDataColuna;
    }

    /**
     * This system's names for the given ones, order kept, duplicates dropped: a name of the other
     * system becomes its pair(s) here; anything else stays as it is.
     */
    public List<String> locais(List<String> entrada) {
        Map<String, List<String>> tr = traducao;
        Set<String> resultado = new LinkedHashSet<>();
        for (String n : entrada) {
            resultado.addAll(tr.getOrDefault(n, List.of(n)));
        }
        return List.copyOf(resultado);
    }

    /** Every pair (in effect or blocked), Matrix name order, for the admin screen. */
    public List<Par> pares() {
        Map<String, Par> todos = new LinkedHashMap<>();
        combinar(todos, true);
        List<Par> lista = new ArrayList<>(todos.values());
        lista.sort(Comparator.comparing(Par::matrix, String.CASE_INSENSITIVE_ORDER)
                .thenComparing(Par::nativo, String.CASE_INSENSITIVE_ORDER));
        return lista;
    }

    public List<String> nomesMatrix() {
        return nomes.matrix();
    }

    public List<String> nomesNative() {
        return nomes.nativo();
    }

    /** Admin: {@code matrix} and {@code nativo} are the same person (replaces their automatic pairs). */
    public void vincular(String matrix, String nativo, String email) {
        validar(matrix, nativo);
        salvar(matrix, nativo, MANUAL, email);
    }

    /**
     * Admin: remove this pair — a manual one is deleted (back to the automatic result, which may pair
     * the same names again); an automatic one is blocked.
     */
    public void desvincular(String matrix, String nativo, String email) {
        validar(matrix, nativo);
        String k = chave(matrix, nativo);
        if (!MANUAL.equals(ajustes.get(k)) && automaticos.containsKey(k)) {
            salvar(matrix, nativo, BLOQUEADO, email);
        } else {
            apagar(matrix, nativo);
        }
    }

    /** Admin: undo an adjustment, back to the automatic result. */
    public void restaurar(String matrix, String nativo) {
        apagar(matrix, nativo);
    }

    private void validar(String matrix, String nativo) {
        Nomes n = nomes;
        if (matrix == null || !n.matrix().contains(matrix)) {
            throw new IllegalArgumentException("Nome da Matrix inválido.");
        }
        if (nativo == null || !n.nativo().contains(nativo)) {
            throw new IllegalArgumentException("Nome da Native inválido.");
        }
    }

    private void salvar(String matrix, String nativo, String acao, String email) {
        appDb.update("""
                INSERT INTO correspondencia_nomes (nome_matrix, nome_native, acao, atualizado_por, atualizado_em)
                VALUES (?, ?, ?, ?, NOW())
                ON DUPLICATE KEY UPDATE acao = VALUES(acao), atualizado_por = VALUES(atualizado_por),
                                        atualizado_em = NOW()
                """, matrix, nativo, acao, email.toLowerCase(Locale.ROOT));
        refreshAjustes();
    }

    private void apagar(String matrix, String nativo) {
        appDb.update("DELETE FROM correspondencia_nomes WHERE nome_matrix = ? AND nome_native = ?", matrix, nativo);
        refreshAjustes();
    }

    @Scheduled(initialDelay = 2_000, fixedRate = 600_000)
    public void refresh() {
        Domain d = props.domain(DOMINIO);
        String col = d == null ? null : d.getDimensoes().get("atendente");
        if (col == null || dataColuna.isBlank() || outraTabela.isBlank() || outraColuna.isBlank() || outraDataColuna.isBlank()) {
            return;
        }
        try {
            List<String> meus = carregar(d.getTabela(), col, dataColuna);
            List<String> outros = carregar(outraTabela, outraColuna, outraDataColuna);
            List<String> matrix = localMatrix ? meus : outros;
            List<String> nativo = localMatrix ? outros : meus;
            Set<String> setores = setores(matrix, nativo);

            Map<String, Par> auto = new HashMap<>();
            Set<String> nativoSet = Set.copyOf(nativo);
            Set<String> matrixSet = Set.copyOf(matrix);
            for (String m : matrix) {
                if (nativoSet.contains(m)) {
                    auto.put(chave(m, m), new Par(m, m, IGUAL, false));
                    continue;
                }
                for (String n : casar(m, nativo, setores)) {
                    auto.put(chave(m, n), new Par(m, n, AUTO, setorDiferente(m, n, setores)));
                }
            }
            // No sentido inverso aparecem pares a mais (ex.: dois cadastros Matrix da mesma pessoa).
            for (String n : nativo) {
                if (matrixSet.contains(n)) {
                    continue;
                }
                for (String m : casar(n, matrix, setores)) {
                    auto.putIfAbsent(chave(m, n), new Par(m, n, AUTO, setorDiferente(m, n, setores)));
                }
            }
            nomes = new Nomes(List.copyOf(matrix), List.copyOf(nativo), Set.copyOf(setores));
            automaticos = Map.copyOf(auto);
            recombinar();
            log.info("Correspondência de nomes Matrix/Native: {} pares automáticos ({} com grafia diferente).",
                    auto.size(), auto.values().stream().filter(p -> AUTO.equals(p.origem())).count());
        } catch (DataAccessException e) {
            log.warn("Não foi possível montar a correspondência de nomes com {}: {}", outraTabela, e.getMessage());
        }
    }

    @Scheduled(initialDelay = 3_000, fixedRate = 30_000)
    public void refreshAjustes() {
        try {
            Map<String, String> mapa = new HashMap<>();
            appDb.query("SELECT nome_matrix, nome_native, acao FROM correspondencia_nomes", rs -> {
                mapa.put(chave(rs.getString("nome_matrix"), rs.getString("nome_native")), rs.getString("acao"));
            });
            ajustes = Map.copyOf(mapa);
            recombinar();
        } catch (DataAccessException e) {
            log.warn("Não foi possível ler os ajustes de correspondência de nomes (app-db): {}", e.getMessage());
        }
    }

    /** Rebuilds {@link #traducao} from the automatic pairs and the admin's adjustments. */
    private void recombinar() {
        Map<String, Par> efetivos = new LinkedHashMap<>();
        combinar(efetivos, false);
        Map<String, List<String>> tr = new HashMap<>();
        for (Par p : efetivos.values()) {
            String de = localMatrix ? p.nativo() : p.matrix();
            String para = localMatrix ? p.matrix() : p.nativo();
            tr.computeIfAbsent(de, k -> new ArrayList<>()).add(para);
        }
        tr.replaceAll((k, v) -> List.copyOf(v));
        traducao = Map.copyOf(tr);
    }

    /**
     * Pairs after the adjustments: manual pairs, plus automatic ones that are neither blocked nor
     * about a name that has a manual pair. With {@code comBloqueados}, blocked ones too (for the screen).
     */
    private void combinar(Map<String, Par> destino, boolean comBloqueados) {
        Map<String, String> aj = ajustes;
        Nomes n = nomes;
        Set<String> comManualMatrix = new HashSet<>();
        Set<String> comManualNative = new HashSet<>();
        List<Par> manuais = new ArrayList<>();
        aj.forEach((k, acao) -> {
            if (MANUAL.equals(acao)) {
                String[] mn = k.split("\0", 2);
                comManualMatrix.add(mn[0]);
                comManualNative.add(mn[1]);
                manuais.add(new Par(mn[0], mn[1], MANUAL, setorDiferente(mn[0], mn[1], n.setores())));
            }
        });
        for (Map.Entry<String, Par> e : automaticos.entrySet()) {
            Par p = e.getValue();
            if (comManualMatrix.contains(p.matrix()) || comManualNative.contains(p.nativo())) {
                continue;
            }
            if (BLOQUEADO.equals(aj.get(e.getKey()))) {
                if (comBloqueados) {
                    destino.put(e.getKey(), new Par(p.matrix(), p.nativo(), BLOQUEADO, p.setorDiferente()));
                }
                continue;
            }
            destino.put(e.getKey(), p);
        }
        for (Par p : manuais) {
            destino.put(chave(p.matrix(), p.nativo()), p);
        }
    }

    private static String chave(String matrix, String nativo) {
        return matrix + "\0" + nativo;
    }

    private List<String> carregar(String tabela, String coluna, String data) {
        return jdbcTemplate.queryForList("SELECT DISTINCT `" + coluna + "` FROM `" + tabela + "` WHERE `" + data
                + "` >= DATE_FORMAT(CURDATE() - INTERVAL 90 DAY, '%Y-%m-%d') AND `" + coluna + "` IS NOT NULL AND `"
                + coluna + "` <> '' AND `" + coluna + "` <> 'null'", String.class);
    }

    private static boolean setorDiferente(String a, String b, Set<String> setores) {
        return !Objects.equals(pessoaSetor(a, setores)[1], pessoaSetor(b, setores)[1]);
    }

    /**
     * The cadastro(s) among {@code candidatosDe} of the person {@code nome}, preferring the same
     * sector; several only if they are the same person (same words, same sector). Empty if none or
     * ambiguous.
     */
    static List<String> casar(String nome, List<String> candidatosDe, Set<String> setores) {
        List<String[]> cands = candidatos(nome, candidatosDe, setores);
        String setor = pessoaSetor(nome, setores)[1];
        List<String[]> mesmoSetor = cands.stream().filter(c -> setor != null && setor.equals(c[1])).toList();
        List<String[]> escolha = mesmoSetor.isEmpty() ? cands : mesmoSetor;
        if (escolha.isEmpty()) {
            return List.of();
        }
        Set<List<String>> pessoas = new HashSet<>();
        Set<String> setoresEscolha = new HashSet<>();
        for (String[] c : escolha) {
            pessoas.add(palavras(pessoaSetor(c[0], setores)[0]).stream().sorted().toList());
            setoresEscolha.add(String.valueOf(c[1]));
        }
        return pessoas.size() == 1 && setoresEscolha.size() == 1
                ? escolha.stream().map(c -> c[0]).toList()
                : List.of();
    }

    /** Names that match {@code nome} as a person: {name, sector}. */
    private static List<String[]> candidatos(String nome, List<String> candidatosDe, Set<String> setores) {
        List<String> tp = palavras(pessoaSetor(nome, setores)[0]);
        List<String[]> cands = new ArrayList<>();
        if (tp.isEmpty()) {
            return cands;
        }
        for (String outro : candidatosDe) {
            String[] ps = pessoaSetor(outro, setores);
            List<String> tn = palavras(ps[0]);
            if (tn.isEmpty() || !tn.get(0).equals(tp.get(0))) {
                continue;
            }
            if (tp.containsAll(tn) || tn.containsAll(tp)) {
                cands.add(new String[] { outro, ps[1] });
            }
        }
        return cands;
    }

    /** Sectors: normalized last parts ("... - Financeiro") shared by at least two names. */
    static Set<String> setores(List<String> a, List<String> b) {
        Map<String, Integer> finais = new HashMap<>();
        for (List<String> lista : List.of(a, b)) {
            for (String nome : lista) {
                String[] partes = partes(nome);
                if (partes.length > 1) {
                    finais.merge(norm(partes[partes.length - 1]), 1, Integer::sum);
                }
            }
        }
        Set<String> s = new HashSet<>();
        finais.forEach((k, v) -> {
            if (v >= 2) {
                s.add(k);
            }
        });
        return s;
    }

    /** {normalized person, normalized sector or null}: "Backoffice - X" and "X - Backoffice" alike. */
    private static String[] pessoaSetor(String nome, Set<String> setores) {
        String[] partes = partes(nome);
        if (partes.length > 1 && setores.contains(norm(partes[partes.length - 1]))) {
            return new String[] { norm(String.join(" ", List.of(partes).subList(0, partes.length - 1))),
                    norm(partes[partes.length - 1]) };
        }
        if (partes.length > 1 && setores.contains(norm(partes[0]))) {
            return new String[] { norm(String.join(" ", List.of(partes).subList(1, partes.length))), norm(partes[0]) };
        }
        return new String[] { norm(nome), null };
    }

    private static List<String> palavras(String pessoa) {
        List<String> r = new ArrayList<>();
        for (String t : pessoa.replace(".", " ").split(" ")) {
            if (!t.isBlank() && !PARTICULAS.contains(t)) {
                r.add(t);
            }
        }
        return r;
    }

    private static String[] partes(String nome) {
        return nome.trim().split("\\s*-\\s*");
    }

    private static String norm(String s) {
        String semAcento = Normalizer.normalize(s, Normalizer.Form.NFD).replaceAll("\\p{M}", "");
        return semAcento.trim().replaceAll("\\s+", " ").toLowerCase(Locale.ROOT);
    }
}
