package com.sebratel.dashboards.common.cache;

import com.sebratel.dashboards.common.config.SemanticDomainProperties;
import com.sebratel.dashboards.common.config.SemanticDomainProperties.Domain;
import com.sebratel.dashboards.common.config.WidgetProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Admin tab "Pausas e comportamentos": per atendente, the pauses of the period (count, time, by type
 * and by typed reason, above the expected time, chained, "flash" pauses, without a recorded end,
 * generic reasons), login sessions and the call patterns linked to known call-center tricks (very short
 * calls ended by the atendente, calls far above the TMA limit, transfers, calls to internal
 * extensions) — see {@code app.widget.comportamento}. Today every minute and the last
 * {@code dias-referencia} days every 10 minutes; long idle stretches come from {@link TmeaNovaRegra}.
 * Also the time logged into the platform ({@link TempoLogado}) of everyone with a session in the period,
 * not only those who attended — the screen pairs it with the other system's by name.
 */
@Component
public class PausasComportamento {

    private static final Logger log = LoggerFactory.getLogger(PausasComportamento.class);
    private static final String DOMINIO = "atendimentos";
    private static final String DIMENSAO_ATENDENTE = "atendente";
    /** A pause starting this soon after the previous one ended counts as "encadeada". */
    public static final long ENCADEADA_SEGUNDOS = 60;
    /** A pause shorter than this is a "pausa relâmpago" (status shuffling). */
    public static final long RELAMPAGO_SEGUNDOS = 60;
    /** A call longer than this multiple of the TMA limit is "muito longa" (line held open). */
    public static final int LONGA_FATOR = 3;
    /** Typed reason with no content (".", ",", "..", "a", "ll"…). */
    private static final Pattern MOTIVO_GENERICO = Pattern.compile("^([\\p{Punct}\\s]+|\\p{L}{1,2})$");
    /** No reason at all: the pause type doesn't ask for one (the Native writes "null"). */
    private static final Pattern SEM_MOTIVO = Pattern.compile("^(|null|undefined)$");

    /** Pauses of one type: count, seconds (only those with a recorded end), above the expected time. */
    public static final class PorTipo {
        public long qtd;
        public long segundos;
        public long excedidas;
        public Long previsto;
    }

    public static final class Atendente {
        public long atendimentos;
        public long dias;
        public long pausas;
        public long pausaSegundos;
        public long excedidas;
        public long excedidoSegundos;
        public long encadeadas;
        public long relampago;
        public long semFim;
        public long motivoGenerico;
        public long semMotivo;
        public long sessoes;
        public long curtos;
        public long longos;
        public long transferidas;
        public long internas;
        public long internasSegundos;
        public final Map<String, PorTipo> porTipo = new LinkedHashMap<>();
        /** Typed reasons (lower-case, trimmed) -> count; only the meaningful ones. */
        public final Map<String, Long> motivos = new LinkedHashMap<>();
    }

    public record Calculo(Map<String, Atendente> porAtendente, Map<String, PorTipo> porTipo, boolean temPrevisto,
                          boolean temMotivo, Map<String, Boolean> indicadores, String curtosRotulo, Integer tmaLimite,
                          String calculadoEm, Map<String, TempoLogado.Logado> logados, boolean logadoEstimado) {}

    private record Pausa(long ini, Long fim, String tipo, Long previsto, String motivo) {}

    private final JdbcTemplate jdbcTemplate;
    private final SemanticDomainProperties props;
    private final WidgetProperties widget;
    private volatile Calculo hoje;
    private volatile Calculo ultimosDias;

    public PausasComportamento(JdbcTemplate jdbcTemplate, SemanticDomainProperties props, WidgetProperties widget) {
        this.jdbcTemplate = jdbcTemplate;
        this.props = props;
        this.widget = widget;
    }

    public Calculo hoje() {
        return hoje;
    }

    public Calculo ultimosDias() {
        return ultimosDias;
    }

    public int dias() {
        return Math.max(1, widget.getTmea().getDiasReferencia());
    }

    @Scheduled(initialDelay = 15_000, fixedRate = 60_000)
    public void refreshHoje() {
        Calculo c = calcular("CURDATE()");
        if (c != null) {
            hoje = c;
        }
    }

    @Scheduled(initialDelay = 25_000, fixedRate = 600_000)
    public void refreshUltimosDias() {
        Calculo c = calcular("CURDATE() - INTERVAL " + dias() + " DAY");
        if (c != null) {
            ultimosDias = c;
        }
    }

    private static boolean tem(String s) {
        return s != null && !s.isBlank();
    }

    private static String soma(String condicao, String valor) {
        return tem(condicao) ? "SUM(CASE WHEN " + condicao + " THEN " + valor + " ELSE 0 END)" : "0";
    }

    /** @param desde DATE expression of the window's start */
    private Calculo calcular(String desde) {
        Domain d = props.domain(DOMINIO);
        String atendenteCol = d == null ? null : d.getDimensoes().get(DIMENSAO_ATENDENTE);
        String dataCol = widget.getDataColuna();
        WidgetProperties.Comportamento cfg = widget.getComportamento();
        if (d == null || atendenteCol == null || dataCol == null || dataCol.isBlank() || !cfg.configurado()) {
            return null;
        }
        long t0 = System.currentTimeMillis();
        try {
            Map<String, Atendente> out = new HashMap<>();
            String desdeTexto = "DATE_FORMAT(" + desde + ", '%Y-%m-%d')";
            String valido = "`" + atendenteCol + "` IS NOT NULL AND `" + atendenteCol + "` <> ''";
            WidgetProperties.Metrica tma = widget.getTempos().get("tma");
            Integer tmaLimite = tma == null ? null : tma.getMetaSegundos();
            String longa = tma == null || tmaLimite == null || !cfg.isLongasAtivo() ? null
                    : tma.expressaoSegundos() + " > " + (long) tmaLimite * LONGA_FATOR;
            jdbcTemplate.query("SELECT `" + atendenteCol + "` AS a, COUNT(*) AS n, COUNT(DISTINCT LEFT(`" + dataCol
                            + "`, 10)) AS dias, " + soma(cfg.getCurtosCondicao(), "1") + " AS curtos, "
                            + soma(longa, "1") + " AS longos, " + soma(cfg.getTransferidasCondicao(), "1") + " AS transf, "
                            + soma(cfg.getInternasCondicao(), "1") + " AS internas, "
                            + soma(cfg.getInternasCondicao(), tem(cfg.getInternasSegundos()) ? cfg.getInternasSegundos() : "0")
                            + " AS internas_s FROM `" + d.getTabela() + "` WHERE `" + dataCol + "` >= " + desdeTexto
                            + " AND " + valido + " GROUP BY `" + atendenteCol + "`",
                    rs -> {
                        Atendente a = out.computeIfAbsent(rs.getString("a"), k -> new Atendente());
                        a.atendimentos = rs.getLong("n");
                        a.dias = rs.getLong("dias");
                        a.curtos = rs.getLong("curtos");
                        a.longos = rs.getLong("longos");
                        a.transferidas = rs.getLong("transf");
                        a.internas = rs.getLong("internas");
                        a.internasSegundos = rs.getLong("internas_s");
                    });

            WidgetProperties.Registros sess = widget.getTmea().getSessoes();
            if (sess != null && sess.configurado()) {
                String filtro = tem(sess.getFiltro()) ? " AND (" + sess.getFiltro() + ")" : "";
                jdbcTemplate.query("SELECT `" + sess.getAgente() + "` AS a, COUNT(*) AS n FROM `" + sess.getTabela()
                                + "` WHERE `" + sess.getInicio() + "` >= " + desde + filtro + " GROUP BY `" + sess.getAgente() + "`",
                        rs -> {
                            Atendente a = out.get(rs.getString("a"));
                            if (a != null) {
                                a.sessoes = rs.getLong("n");
                            }
                        });
            }

            // Tipo da pausa vindo de outra tabela (Native): casado por atendente + início.
            Map<String, String> tipos = new HashMap<>();
            if (tem(cfg.getTiposSql())) {
                jdbcTemplate.query(cfg.getTiposSql().replace("{desde}", desde), rs -> {
                    long ini = rs.getLong("ini");
                    if (!rs.wasNull()) {
                        tipos.putIfAbsent(rs.getString("agente") + "|" + ini, rs.getString("tipo"));
                    }
                });
            }

            Pattern prefixo = tem(cfg.getTipoPrefixo()) ? Pattern.compile(cfg.getTipoPrefixo()) : null;
            boolean[] temMotivo = {false};
            Map<String, List<Pausa>> pausas = new HashMap<>();
            jdbcTemplate.query(cfg.getPausasSql().replace("{desde}", desde), rs -> {
                long ini = rs.getLong("ini");
                if (rs.wasNull() || ini <= 0) {
                    return;
                }
                String agente = rs.getString("agente");
                long f = rs.getLong("fim");
                Long fim = rs.wasNull() || f < ini ? null : f;
                long p = rs.getLong("previsto");
                Long previsto = rs.wasNull() || p <= 0 ? null : p;
                String tipo = rs.getString("tipo");
                if (!tem(tipo)) {
                    tipo = tipos.get(agente + "|" + ini);
                }
                if (!tem(tipo) || "undefined".equalsIgnoreCase(tipo)) {
                    tipo = "(sem tipo)";
                } else if (prefixo != null) {
                    tipo = prefixo.matcher(tipo.trim()).replaceFirst("");
                }
                String motivo = rs.getString("motivo");
                if (motivo != null) {
                    temMotivo[0] = true;
                }
                pausas.computeIfAbsent(agente, k -> new ArrayList<>()).add(new Pausa(ini, fim, tipo.trim(), previsto, motivo));
            });

            Map<String, PorTipo> geral = new LinkedHashMap<>();
            boolean[] temPrevisto = {false};
            pausas.forEach((agente, lista) -> {
                Atendente a = out.get(agente);
                if (a == null) {
                    return; // pausas de quem não atendeu no período (supervisores, testes…)
                }
                lista.sort(Comparator.comparingLong(Pausa::ini));
                Long fimAnterior = null;
                for (Pausa p : lista) {
                    a.pausas++;
                    PorTipo t = a.porTipo.computeIfAbsent(p.tipo(), k -> new PorTipo());
                    PorTipo g = geral.computeIfAbsent(p.tipo(), k -> new PorTipo());
                    t.qtd++;
                    g.qtd++;
                    if (p.previsto() != null) {
                        temPrevisto[0] = true;
                        t.previsto = p.previsto();
                        g.previsto = p.previsto();
                    }
                    if (temMotivo[0]) {
                        String m = p.motivo() == null ? "" : p.motivo().trim().toLowerCase(Locale.ROOT);
                        if (SEM_MOTIVO.matcher(m).matches()) {
                            a.semMotivo++;
                        } else if (MOTIVO_GENERICO.matcher(m).matches()) {
                            a.motivoGenerico++;
                        } else {
                            a.motivos.merge(m, 1L, Long::sum);
                        }
                    }
                    if (fimAnterior != null && p.ini() >= fimAnterior && p.ini() - fimAnterior <= ENCADEADA_SEGUNDOS) {
                        a.encadeadas++;
                    }
                    if (p.fim() == null) {
                        a.semFim++;
                        fimAnterior = null;
                        continue;
                    }
                    long dur = p.fim() - p.ini();
                    a.pausaSegundos += dur;
                    t.segundos += dur;
                    g.segundos += dur;
                    if (dur < RELAMPAGO_SEGUNDOS) {
                        a.relampago++;
                    }
                    if (p.previsto() != null && dur > p.previsto()) {
                        a.excedidas++;
                        a.excedidoSegundos += dur - p.previsto();
                        t.excedidas++;
                        g.excedidas++;
                    }
                    fimAnterior = p.fim();
                }
            });
            Map<String, Boolean> indicadores = new LinkedHashMap<>();
            indicadores.put("previsto", temPrevisto[0]);
            indicadores.put("motivo", temMotivo[0]);
            indicadores.put("curtos", tem(cfg.getCurtosCondicao()));
            indicadores.put("longos", longa != null);
            indicadores.put("transferidas", tem(cfg.getTransferidasCondicao()));
            indicadores.put("internas", tem(cfg.getInternasCondicao()));
            indicadores.put("sessoes", sess != null && sess.configurado());
            Map<String, TempoLogado.Logado> logados = tempoLogado(sess, desde, cfg.isEstimarSessaoAberta(), pausas, d,
                    atendenteCol, dataCol, valido);
            String agora = jdbcTemplate.queryForObject("SELECT CAST(NOW() AS CHAR)", String.class);
            log.debug("Pausas e comportamentos ({}, desde {}): {} atendentes, {} tipos de pausa em {} ms.", d.getTabela(),
                    desde, out.size(), geral.size(), System.currentTimeMillis() - t0);
            return new Calculo(Map.copyOf(out), geral, temPrevisto[0], temMotivo[0], indicadores, cfg.getCurtosRotulo(),
                    tmaLimite, agora, logados, cfg.isEstimarSessaoAberta());
        } catch (DataAccessException e) {
            log.warn("Não foi possível calcular pausas e comportamentos ({}): {}", d.getTabela(), e.getMessage());
            return null;
        }
    }

    /** atendente -> time logged in the window; empty when the sessions table isn't configured. */
    private Map<String, TempoLogado.Logado> tempoLogado(WidgetProperties.Registros sess, String desde, boolean estimar,
                                                        Map<String, List<Pausa>> pausas, Domain d, String atendenteCol,
                                                        String dataCol, String valido) {
        if (sess == null || !sess.configurado()) {
            return Map.of();
        }
        Map<String, Object> relogio = jdbcTemplate.queryForMap("SELECT UNIX_TIMESTAMP(" + desde + ") AS inicio,"
                + " UNIX_TIMESTAMP(CURDATE()) AS hoje, UNIX_TIMESTAMP(NOW()) AS agora");
        long inicio = ((Number) relogio.get("inicio")).longValue();
        long hojeInicio = ((Number) relogio.get("hoje")).longValue();
        long agora = ((Number) relogio.get("agora")).longValue();

        String filtro = tem(sess.getFiltro()) ? " AND (" + sess.getFiltro() + ")" : "";
        Map<String, List<long[]>> sessoes = new HashMap<>();
        // Um dia antes: sessão que começou antes da janela e ainda estava aberta nela.
        // NULLIF(..., 0): "0000-00-00 00:00:00" quando o logout não foi registrado.
        jdbcTemplate.query("SELECT `" + sess.getAgente() + "` AS a, NULLIF(UNIX_TIMESTAMP(`" + sess.getInicio() + "`), 0) AS i,"
                        + " NULLIF(UNIX_TIMESTAMP(`" + sess.getFim() + "`), 0) AS f FROM `" + sess.getTabela() + "`"
                        + " WHERE `" + sess.getInicio() + "` >= " + desde + " - INTERVAL 1 DAY AND `" + sess.getInicio()
                        + "` <= NOW()" + filtro,
                rs -> {
                    long i = rs.getLong("i");
                    if (rs.wasNull()) {
                        return;
                    }
                    long f = rs.getLong("f");
                    long fim = rs.wasNull() ? TempoLogado.SEM_FIM : f;
                    sessoes.computeIfAbsent(rs.getString("a"), k -> new ArrayList<>()).add(new long[] {i, fim});
                });

        // Native: a sessão só é gravada no logoff — a de agora sai da atividade de hoje (ligações e pausas).
        Map<String, List<long[]>> atividades = new HashMap<>();
        WidgetProperties.Tmea t = widget.getTmea();
        if (estimar && tem(t.getInicio()) && tem(t.getFim())) {
            String ini = "(" + t.getInicio() + ")";
            jdbcTemplate.query("SELECT `" + atendenteCol + "` AS a, " + ini + " AS ini, (" + t.getFim() + ") AS fim FROM `"
                            + d.getTabela() + "` WHERE `" + dataCol + "` >= DATE_FORMAT(CURDATE(), '%Y-%m-%d') AND " + valido
                            + " AND " + ini + " IS NOT NULL",
                    rs -> {
                        long i = rs.getLong("ini");
                        long f = rs.getLong("fim");
                        long fim = rs.wasNull() ? i : f;
                        atividades.computeIfAbsent(rs.getString("a"), k -> new ArrayList<>()).add(new long[] {i, fim});
                    });
            pausas.forEach((agente, lista) -> {
                for (Pausa p : lista) {
                    if (p.ini() >= hojeInicio) {
                        atividades.computeIfAbsent(agente, k -> new ArrayList<>())
                                .add(new long[] {p.ini(), p.fim() == null ? p.ini() : p.fim()});
                    }
                }
            });
        }

        ZoneId zona = ZoneId.systemDefault();
        Map<String, TempoLogado.Logado> out = new HashMap<>();
        Set<String> nomes = new HashSet<>(sessoes.keySet());
        nomes.addAll(atividades.keySet());
        for (String nome : nomes) {
            if (nome == null || nome.isBlank() || "null".equalsIgnoreCase(nome.trim())) {
                continue;
            }
            TempoLogado.Logado l = TempoLogado.calcular(sessoes.getOrDefault(nome, List.of()), atividades.get(nome),
                    inicio, hojeInicio, agora, estimar, zona);
            if (l != null) {
                out.put(nome, l);
            }
        }
        return Map.copyOf(out);
    }
}
