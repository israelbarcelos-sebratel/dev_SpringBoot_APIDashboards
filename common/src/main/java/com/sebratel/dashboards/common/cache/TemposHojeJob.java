package com.sebratel.dashboards.common.cache;

import com.sebratel.dashboards.common.config.SemanticDomainProperties;
import com.sebratel.dashboards.common.config.SemanticDomainProperties.Domain;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Today's ("turno do dia") per-atendente averages for the Chrome extension widget, recomputed once a
 * minute and kept in memory — unlike {@link TemposAtendenteCache} (6-week window, persisted), today's
 * numbers are cheap to rebuild on startup and don't need a table.
 *
 * <p>"Hoje" is the database's {@code CURDATE()}, so it follows the DB's (local) clock, same as the
 * ingestion. The date column is configured per app ({@code app.widget.data-coluna}) because db_matrix
 * keeps its dates as {@code varchar 'YYYY-MM-DD HH:MM:SS'}; comparing against
 * {@code DATE_FORMAT(CURDATE(), '%Y-%m-%d')} works for both that and a real DATETIME column.
 */
@Component
public class TemposHojeJob {

    private static final Logger log = LoggerFactory.getLogger(TemposHojeJob.class);
    private static final String DOMINIO = "atendimentos";
    private static final String DIMENSAO_ATENDENTE = "atendente";

    /** One metric's average today, over {@code amostras} rows with a non-null value. */
    public record Tempo(double segundosMedios, long amostras) {}

    /**
     * @param porAtendente   atendente -> metrica -> Tempo (only atendentes with rows today)
     * @param ultimoRegistro most recent date-column value in the table today ("YYYY-MM-DD HH:MM:SS"),
     *                       i.e. how fresh the ingested data is; null if nothing arrived today
     * @param calculadoEm    DB clock when this snapshot was computed
     */
    public record Snapshot(Map<String, Map<String, Tempo>> porAtendente, String ultimoRegistro, String calculadoEm) {}

    private final JdbcTemplate jdbcTemplate;
    private final SemanticDomainProperties props;
    private final String dataColuna;
    private volatile Snapshot snapshot = new Snapshot(Map.of(), null, null);

    public TemposHojeJob(JdbcTemplate jdbcTemplate,
                         SemanticDomainProperties props,
                         @Value("${app.widget.data-coluna:}") String dataColuna) {
        this.jdbcTemplate = jdbcTemplate;
        this.props = props;
        this.dataColuna = dataColuna;
    }

    public Snapshot snapshot() {
        return snapshot;
    }

    @Scheduled(initialDelay = 3_000, fixedRate = 60_000)
    public void refresh() {
        Domain d = props.domain(DOMINIO);
        String atendenteCol = d == null ? null : d.getDimensoes().get(DIMENSAO_ATENDENTE);
        if (d == null || d.getTempos().isEmpty() || atendenteCol == null || dataColuna.isBlank()) {
            return;
        }
        String tabela = d.getTabela();
        String hoje = "`" + dataColuna + "` >= DATE_FORMAT(CURDATE(), '%Y-%m-%d')";

        StringBuilder sql = new StringBuilder("SELECT `").append(atendenteCol).append("` AS atendente");
        List<String> metricas = List.copyOf(d.getTempos().keySet());
        for (int i = 0; i < metricas.size(); i++) {
            String col = d.getTempos().get(metricas.get(i));
            sql.append(", AVG(TIME_TO_SEC(`").append(col).append("`)) AS m").append(i)
               .append(", COUNT(`").append(col).append("`) AS n").append(i);
        }
        sql.append(" FROM `").append(tabela).append("` WHERE ").append(hoje)
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
                    "SELECT CAST(MAX(`" + dataColuna + "`) AS CHAR) AS ultimo, CAST(NOW() AS CHAR) AS agora FROM `"
                            + tabela + "` WHERE " + hoje);
            snapshot = new Snapshot(Map.copyOf(porAtendente), (String) meta.get("ultimo"), (String) meta.get("agora"));
        } catch (DataAccessException e) {
            // Mantém o último snapshot bom; o widget mostra "calculadoEm" e dá para ver que parou.
            log.warn("Não foi possível calcular os tempos de hoje ({}): {}", tabela, e.getMessage());
        }
    }
}
