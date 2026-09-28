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

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Today's ("turno do dia") per-atendente averages for the Chrome extension widget
 * ({@link WidgetProperties}: TMA/TME…), recomputed every minute and kept in memory — cheap to rebuild
 * on startup, so no table. The widget is daily by design: longer periods are the managers' business,
 * served by the rest of the API (dashboards).
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

    /** One metric's average over {@code amostras} valid rows. */
    public record Tempo(double segundosMedios, long amostras) {}

    /**
     * @param porAtendente   atendente -> metrica -> Tempo (only atendentes with rows in the window)
     * @param ultimoRegistro most recent date-column value in the window ("YYYY-MM-DD HH:MM:SS"), i.e.
     *                       how fresh the ingested data is; null if nothing arrived
     * @param calculadoEm    DB clock when this snapshot was computed
     */
    public record Snapshot(Map<String, Map<String, Tempo>> porAtendente, String ultimoRegistro, String calculadoEm) {}

    private static final Snapshot VAZIO = new Snapshot(Map.of(), null, null);

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

    @Scheduled(initialDelay = 3_000, fixedRate = 60_000)
    public void refreshHoje() {
        Snapshot s = calcularHoje();
        if (s != null) {
            hoje = s;
        }
    }

    /** Snapshot for today's rows; null if not configured or on error (the last good one is kept). */
    private Snapshot calcularHoje() {
        Domain d = props.domain(DOMINIO);
        String atendenteCol = d == null ? null : d.getDimensoes().get(DIMENSAO_ATENDENTE);
        String dataCol = widget.getDataColuna();
        if (d == null || atendenteCol == null || dataCol == null || dataCol.isBlank() || widget.getTempos().isEmpty()) {
            return null;
        }
        String tabela = d.getTabela();
        String janela = "`" + dataCol + "` >= DATE_FORMAT(CURDATE(), '%Y-%m-%d')";

        List<String> metricas = List.copyOf(widget.getTempos().keySet());
        StringBuilder sql = new StringBuilder("SELECT `").append(atendenteCol).append("` AS atendente");
        for (int i = 0; i < metricas.size(); i++) {
            String expr = widget.getTempos().get(metricas.get(i)).expressaoSegundos();
            String valido = "CASE WHEN " + expr + " >= 0 THEN " + expr + " END";
            sql.append(", AVG(").append(valido).append(") AS m").append(i)
               .append(", COUNT(").append(valido).append(") AS n").append(i);
        }
        sql.append(" FROM `").append(tabela).append("` WHERE ").append(janela)
           .append(" AND `").append(atendenteCol).append("` IS NOT NULL AND `").append(atendenteCol).append("` <> ''")
           .append(" GROUP BY `").append(atendenteCol).append("`");

        try {
            Map<String, Map<String, Tempo>> porAtendente = new HashMap<>();
            jdbcTemplate.query(sql.toString(), rs -> {
                Map<String, Tempo> tempos = new LinkedHashMap<>();
                for (int i = 0; i < metricas.size(); i++) {
                    long n = rs.getLong("n" + i);
                    if (n > 0) {
                        tempos.put(metricas.get(i), new Tempo(rs.getDouble("m" + i), n));
                    }
                }
                porAtendente.put(rs.getString("atendente"), tempos);
            });
            Map<String, Object> meta = jdbcTemplate.queryForMap(
                    "SELECT CAST(MAX(`" + dataCol + "`) AS CHAR) AS ultimo, CAST(NOW() AS CHAR) AS agora FROM `"
                            + tabela + "` WHERE " + janela);
            return new Snapshot(Map.copyOf(porAtendente), (String) meta.get("ultimo"), (String) meta.get("agora"));
        } catch (DataAccessException e) {
            // Mantém o último snapshot bom; o widget mostra "dados até" e dá para ver que parou.
            log.warn("Não foi possível calcular os tempos de hoje do widget ({}): {}", tabela, e.getMessage());
            return null;
        }
    }
}
