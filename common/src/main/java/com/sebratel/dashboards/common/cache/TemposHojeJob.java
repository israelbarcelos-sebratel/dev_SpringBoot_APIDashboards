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

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Today's ("turno do dia") per-atendente averages for the Chrome extension widget
 * ({@link WidgetProperties}: TMA/TME…, plus the TMEA and the number of calls), recomputed every
 * minute and kept in memory — cheap to rebuild on startup, so no table. The widget is daily by
 * design: longer periods are the managers' business, served by the rest of the API (dashboards).
 *
 * <p>"Hoje" is the database's {@code CURDATE()}, so it follows the DB's (local) clock, same as the
 * ingestion. Windows compare against {@code DATE_FORMAT(..., '%Y-%m-%d')}, which works both for a
 * DATETIME column (db_native) and for db_matrix's {@code varchar 'YYYY-MM-DD HH:MM:SS'} dates.
 * Negative durations (clock glitches in the source) are ignored, like in the dashboards' histograms.
 */
@Component
public class TemposHojeJob {

    private static final Logger log = LoggerFactory.getLogger(TemposHojeJob.class);
    private static final String DOMINIO = "atendimentos";
    private static final String DIMENSAO_ATENDENTE = "atendente";
    /** Key of the TMEA ({@link WidgetProperties.Tmea}) next to the {@code app.widget.tempos} ones. */
    public static final String TMEA = "tmea";

    /** One metric's average over {@code amostras} valid rows. */
    public record Tempo(double segundosMedios, long amostras) {}

    /**
     * @param porAtendente   atendente -> metrica -> Tempo (only atendentes with rows in the window),
     *                       including {@link #TMEA}
     * @param atendimentos   atendente -> number of today's rows (calls), whatever their times
     * @param ultimoRegistro most recent date-column value in the window ("YYYY-MM-DD HH:MM:SS"), i.e.
     *                       how fresh the ingested data is; null if nothing arrived
     * @param calculadoEm    DB clock when this snapshot was computed
     */
    public record Snapshot(Map<String, Map<String, Tempo>> porAtendente, Map<String, Long> atendimentos,
                           String ultimoRegistro, String calculadoEm) {}

    private static final Snapshot VAZIO = new Snapshot(Map.of(), Map.of(), null, null);

    private final JdbcTemplate jdbcTemplate;
    private final SemanticDomainProperties props;
    private final WidgetProperties widget;
    private volatile Snapshot hoje = VAZIO;

    public TemposHojeJob(JdbcTemplate jdbcTemplate, SemanticDomainProperties props, WidgetProperties widget) {
        this.jdbcTemplate = jdbcTemplate;
        this.props = props;
        this.widget = widget;
    }

    public Snapshot hoje() {
        return hoje;
    }

    /**
     * Metrics of several atendente names combined as one person (a person can show up under more
     * than one name): each metric's average weighted by its number of rows.
     */
    public Map<String, Tempo> somar(Snapshot snapshot, List<String> nomes) {
        return somar(snapshot.porAtendente(), nomes);
    }

    /** Same as {@link #somar(Snapshot, List)} over any atendente -> metrica -> Tempo map. */
    public static Map<String, Tempo> somar(Map<String, Map<String, Tempo>> porAtendente, List<String> nomes) {
        Map<String, double[]> acc = new LinkedHashMap<>(); // metrica -> {soma dos segundos, amostras}
        for (String nome : nomes) {
            porAtendente.getOrDefault(nome, Map.of()).forEach((metrica, t) -> {
                double[] a = acc.computeIfAbsent(metrica, k -> new double[2]);
                a[0] += t.segundosMedios() * t.amostras();
                a[1] += t.amostras();
            });
        }
        Map<String, Tempo> resultado = new LinkedHashMap<>();
        acc.forEach((metrica, a) -> resultado.put(metrica, new Tempo(a[0] / a[1], (long) a[1])));
        return resultado;
    }

    /** Today's number of calls of several names combined as one person. */
    public long atendimentosHoje(Snapshot snapshot, List<String> nomes) {
        return nomes.stream().mapToLong(n -> snapshot.atendimentos().getOrDefault(n, 0L)).sum();
    }

    /**
     * TMEA per atendente over the rows matching {@code janela} (a WHERE condition on the source
     * table). Used for today (every minute, here) and for the sector reference over the last days
     * ({@link ReferenciaMensalJob}). Empty if TMEA isn't configured.
     */
    public Map<String, Tempo> tmeaPorAtendente(String janela) {
        Domain d = props.domain(DOMINIO);
        String atendenteCol = d == null ? null : d.getDimensoes().get(DIMENSAO_ATENDENTE);
        if (d == null || atendenteCol == null || !widget.getTmea().configurado()) {
            return Map.of();
        }
        String sql = "SELECT atendente, AVG(v) AS media, COUNT(v) AS n FROM ("
                + "SELECT atendente, " + intervaloValido("g") + " AS v FROM ("
                + "SELECT `" + atendenteCol + "` AS atendente, " + intervaloBruto(atendenteCol) + " AS g"
                + " FROM `" + d.getTabela() + "` WHERE " + janela + " AND " + atendenteValido(atendenteCol)
                + " AND (" + widget.getTmea().getInicio() + ") IS NOT NULL"
                + ") a) b GROUP BY atendente";
        Map<String, Tempo> resultado = new HashMap<>();
        jdbcTemplate.query(sql, rs -> {
            long n = rs.getLong("n");
            if (n > 0) {
                resultado.put(rs.getString("atendente"), new Tempo(rs.getDouble("media"), n));
            }
        });
        return resultado;
    }

    /**
     * SQL for the gap (seconds) before each call: its start minus the latest end among the same
     * atendente's earlier calls that day — so overlapping calls (transfers, parallel chats) give 0 or
     * less. Raw value: {@link #intervaloValido} applies the TMEA rule. Needs {@code app.widget.tmea}.
     */
    private String intervaloBruto(String atendenteCol) {
        WidgetProperties.Tmea t = widget.getTmea();
        String inicio = "(" + t.getInicio() + ")";
        return inicio + " - MAX(" + t.getFim() + ") OVER (PARTITION BY `" + atendenteCol + "`, DATE(FROM_UNIXTIME("
                + inicio + ")) ORDER BY " + inicio + " ROWS BETWEEN UNBOUNDED PRECEDING AND 1 PRECEDING)";
    }

    /** The TMEA rule on a raw gap: overlap counts as 0; above the cap (break, end of shift) it's left out. */
    private String intervaloValido(String bruto) {
        long max = widget.getTmea().getMaxIntervaloMinutos() * 60L;
        return "CASE WHEN " + bruto + " < 0 THEN 0 WHEN " + bruto + " <= " + max + " THEN " + bruto + " END";
    }

    /**
     * Today's calls of the given atendente names, newest first, for the per-call table: the configured
     * {@code app.widget.detalhe} columns plus each metric in seconds as computed by the same expression
     * the averages use (null when missing or negative — those rows don't count in the average), and
     * the TMEA gap before the call, plus the customer's survey score ({@code csat}, 1–5) where
     * {@code app.widget.csat} links scores to calls. Read straight from the source on demand (someone
     * opened the table), capped at {@code app.widget.detalhe-limite} rows.
     */
    public List<Map<String, Object>> detalheHoje(List<String> nomes) {
        Domain d = props.domain(DOMINIO);
        String atendenteCol = d == null ? null : d.getDimensoes().get(DIMENSAO_ATENDENTE);
        String dataCol = widget.getDataColuna();
        if (d == null || atendenteCol == null || dataCol == null || dataCol.isBlank() || nomes.isEmpty()) {
            return List.of();
        }
        List<WidgetProperties.Coluna> colunas = widget.getDetalhe();
        List<String> metricas = List.copyOf(widget.getTempos().keySet());
        StringBuilder sql = new StringBuilder("SELECT CAST(`").append(atendenteCol).append("` AS CHAR) AS atendente");
        for (int i = 0; i < colunas.size(); i++) {
            sql.append(", CAST(`").append(colunas.get(i).getColuna()).append("` AS CHAR) AS c").append(i);
        }
        for (int i = 0; i < metricas.size(); i++) {
            String expr = widget.getTempos().get(metricas.get(i)).expressaoSegundos();
            sql.append(", CASE WHEN ").append(expr).append(" >= 0 THEN ").append(expr).append(" END AS m").append(i);
        }
        boolean tmea = widget.getTmea().configurado();
        if (tmea) {
            // Window over all of today's rows of these names — computed before ORDER BY/LIMIT, so the
            // gaps match the TMEA average even when the table is truncated.
            sql.append(", ").append(intervaloValido("(" + intervaloBruto(atendenteCol) + ")")).append(" AS tmea");
        }
        String notaSql = widget.getCsat().notaDoAtendimentoSql(d.getTabela());
        if (notaSql != null) {
            sql.append(", ").append(notaSql).append(" AS csat");
        }
        sql.append(" FROM `").append(d.getTabela()).append("` WHERE ").append(janelaHoje(dataCol))
           .append(" AND `").append(atendenteCol).append("` IN (")
           .append(String.join(",", Collections.nCopies(nomes.size(), "?"))).append(")")
           .append(" ORDER BY `").append(dataCol).append("` DESC LIMIT ").append(Math.max(1, widget.getDetalheLimite()));

        List<Map<String, Object>> linhas = new ArrayList<>();
        jdbcTemplate.query(sql.toString(), rs -> {
            Map<String, Object> linha = new LinkedHashMap<>();
            linha.put("atendente", rs.getString("atendente"));
            for (int i = 0; i < colunas.size(); i++) {
                linha.put(colunas.get(i).getChave(), rs.getString("c" + i));
            }
            Map<String, Object> tempos = new LinkedHashMap<>();
            for (int i = 0; i < metricas.size(); i++) {
                long v = rs.getLong("m" + i);
                tempos.put(metricas.get(i), rs.wasNull() ? null : v);
            }
            if (tmea) {
                long v = rs.getLong("tmea");
                tempos.put(TMEA, rs.wasNull() ? null : v);
            }
            linha.put("tempos", tempos);
            if (notaSql != null) {
                double v = rs.getDouble("csat");
                linha.put("csat", rs.wasNull() ? null : v);
            }
            linhas.add(linha);
        }, nomes.toArray());
        return linhas;
    }

    static String janelaHoje(String dataCol) {
        return "`" + dataCol + "` >= DATE_FORMAT(CURDATE(), '%Y-%m-%d')";
    }

    private static String atendenteValido(String atendenteCol) {
        return "`" + atendenteCol + "` IS NOT NULL AND `" + atendenteCol + "` <> ''";
    }

    @Scheduled(initialDelay = 3_000, fixedRate = 60_000)
    public void refreshHoje() {
        Snapshot s = calcularHoje();
        if (s != null) {
            hoje = s;
        }
    }

    /** Per-atendente averages (with TMEA) and number of rows over {@code janela}. */
    public record Periodo(Map<String, Map<String, Tempo>> porAtendente, Map<String, Long> atendimentos) {}

    /**
     * Same numbers as today's snapshot over any window (a WHERE condition on the source table) — used
     * for the admin ranking over the last days ({@link ReferenciaMensalJob}). Null if not configured.
     */
    public Periodo temposPorAtendente(String janela) {
        Domain d = props.domain(DOMINIO);
        String atendenteCol = d == null ? null : d.getDimensoes().get(DIMENSAO_ATENDENTE);
        if (d == null || atendenteCol == null || widget.getTempos().isEmpty()) {
            return null;
        }
        List<String> metricas = List.copyOf(widget.getTempos().keySet());
        StringBuilder sql = new StringBuilder("SELECT `").append(atendenteCol).append("` AS atendente");
        for (int i = 0; i < metricas.size(); i++) {
            String expr = widget.getTempos().get(metricas.get(i)).expressaoSegundos();
            String valido = "CASE WHEN " + expr + " >= 0 THEN " + expr + " END";
            sql.append(", AVG(").append(valido).append(") AS m").append(i)
               .append(", COUNT(").append(valido).append(") AS n").append(i);
        }
        sql.append(", COUNT(*) AS total");
        sql.append(" FROM `").append(d.getTabela()).append("` WHERE ").append(janela)
           .append(" AND ").append(atendenteValido(atendenteCol))
           .append(" GROUP BY `").append(atendenteCol).append("`");

        Map<String, Map<String, Tempo>> porAtendente = new HashMap<>();
        Map<String, Long> atendimentos = new HashMap<>();
        jdbcTemplate.query(sql.toString(), rs -> {
            atendimentos.put(rs.getString("atendente"), rs.getLong("total"));
            Map<String, Tempo> tempos = new LinkedHashMap<>();
            for (int i = 0; i < metricas.size(); i++) {
                long n = rs.getLong("n" + i);
                if (n > 0) {
                    tempos.put(metricas.get(i), new Tempo(rs.getDouble("m" + i), n));
                }
            }
            porAtendente.put(rs.getString("atendente"), tempos);
        });
        tmeaPorAtendente(janela).forEach((atendente, t) ->
                porAtendente.computeIfAbsent(atendente, k -> new LinkedHashMap<>()).put(TMEA, t));
        return new Periodo(Map.copyOf(porAtendente), Map.copyOf(atendimentos));
    }

    /** Snapshot for today's rows; null if not configured or on error (the last good one is kept). */
    private Snapshot calcularHoje() {
        Domain d = props.domain(DOMINIO);
        String dataCol = widget.getDataColuna();
        if (d == null || dataCol == null || dataCol.isBlank()) {
            return null;
        }
        String tabela = d.getTabela();
        String janela = janelaHoje(dataCol);
        try {
            Periodo periodo = temposPorAtendente(janela);
            if (periodo == null) {
                return null;
            }
            Map<String, Object> meta = jdbcTemplate.queryForMap(
                    "SELECT CAST(MAX(`" + dataCol + "`) AS CHAR) AS ultimo, CAST(NOW() AS CHAR) AS agora FROM `"
                            + tabela + "` WHERE " + janela);
            return new Snapshot(periodo.porAtendente(), periodo.atendimentos(),
                    (String) meta.get("ultimo"), (String) meta.get("agora"));
        } catch (DataAccessException e) {
            // Mantém o último snapshot bom; o widget mostra "dados até" e dá para ver que parou.
            log.warn("Não foi possível calcular os tempos de hoje do widget ({}): {}", tabela, e.getMessage());
            return null;
        }
    }
}
