package com.sebratel.dashboards.common.cache;

import com.sebratel.dashboards.common.config.SemanticDomainProperties;
import com.sebratel.dashboards.common.config.SemanticDomainProperties.Domain;
import com.sebratel.dashboards.common.config.TableGroupProperties;
import com.sebratel.dashboards.common.schema.ColumnMetadata;
import com.sebratel.dashboards.common.schema.ColumnType;
import com.sebratel.dashboards.common.schema.SchemaIntrospector;
import com.sebratel.dashboards.common.schema.TableSchema;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Recomputes {@code agg_tempos_atendente} (see {@link TemposAtendenteCache}) once a minute, per
 * atendente, straight in SQL ({@code AVG(TIME_TO_SEC(...)) ... GROUP BY <atendente>}) — the same
 * "HH:MM:SS" columns {@code SemanticService#tempos} would otherwise pull row-by-row into Java and
 * parse on every request. One instance runs per process (matrix-api / native-api), each tagging its
 * rows with its own {@link TableGroupProperties#groupName()} ("matrix"/"native"), since both write to
 * the same {@code agg_tempos_atendente} table in the app's own database (app-db). The aggregation
 * query itself runs on the source database.
 *
 * <p>Only the "atendimentos" domain is refreshed — it's the one the TMA/TME Chrome extension polls
 * per-atendente every 15s; other domains still compute their (whole-filter, not per-agent) aggregates
 * live, which is cheap enough since nothing calls them per-agent in a tight loop today.
 */
@Component
public class TemposAtendenteRefreshJob {

    private static final Logger log = LoggerFactory.getLogger(TemposAtendenteRefreshJob.class);
    private static final String DOMINIO = "atendimentos";
    private static final String DIMENSAO_ATENDENTE = "atendente";

    private final JdbcTemplate jdbcTemplate;
    private final SchemaIntrospector introspector;
    private final SemanticDomainProperties props;
    private final TableGroupProperties groupProperties;
    private final TemposAtendenteCache cache;
    private final String dbSchema;

    public TemposAtendenteRefreshJob(JdbcTemplate jdbcTemplate,
                                      SchemaIntrospector introspector,
                                      SemanticDomainProperties props,
                                      TableGroupProperties groupProperties,
                                      TemposAtendenteCache cache,
                                      @Value("${app.db-schema}") String dbSchema) {
        this.jdbcTemplate = jdbcTemplate;
        this.introspector = introspector;
        this.props = props;
        this.groupProperties = groupProperties;
        this.cache = cache;
        this.dbSchema = dbSchema;
    }

    @Scheduled(initialDelay = 5_000, fixedRate = 60_000)
    public void refresh() {
        Domain d = props.domain(DOMINIO);
        if (d == null || d.getTempos().isEmpty()) {
            return;
        }
        String atendenteCol = d.getDimensoes().get(DIMENSAO_ATENDENTE);
        if (atendenteCol == null) {
            log.warn("Domínio '{}' não tem a dimensão '{}' configurada — cache de tempos por atendente desativado.",
                    DOMINIO, DIMENSAO_ATENDENTE);
            return;
        }

        String tabela = d.getTabela();
        TableSchema schema = introspector.introspect(dbSchema, tabela);
        String dateCol = schema.columns().stream()
                .filter(c -> c.type() == ColumnType.DATETIME)
                .map(ColumnMetadata::name)
                .findFirst()
                .orElse(null);

        // "atendimentos" nunca configura defaultMeses (só domínios esparsos como respostaCliente
        // fazem isso), então a janela padrão do framework (6 semanas) já é a mesma que o endpoint
        // /atendimentos/tempos usaria sem overrides — sem precisar ler Domain#getDefaultMeses().
        String janela = "6 WEEK";
        String windowClause = dateCol == null
                ? ""
                : " AND `" + dateCol + "` > (SELECT DATE_SUB(MAX(`" + dateCol + "`), INTERVAL " + janela
                        + ") FROM `" + tabela + "`)";

        String sistema = groupProperties.groupName();
        int totalAtendentes = 0;

        for (Map.Entry<String, String> tempo : d.getTempos().entrySet()) {
            String metrica = tempo.getKey();
            String coluna = tempo.getValue();

            String sql = "SELECT `" + atendenteCol + "` AS atendente, "
                    + "AVG(TIME_TO_SEC(`" + coluna + "`)) AS segundos_medios, "
                    + "COUNT(*) AS amostras "
                    + "FROM `" + tabela + "` "
                    + "WHERE `" + atendenteCol + "` IS NOT NULL AND `" + atendenteCol + "` <> '' "
                    + "AND `" + coluna + "` IS NOT NULL"
                    + windowClause
                    + " GROUP BY `" + atendenteCol + "`";

            List<Object[]> linhas = new ArrayList<>();
            jdbcTemplate.query(sql, rs -> {
                linhas.add(new Object[] {
                        rs.getString("atendente"),
                        rs.getObject("segundos_medios"),
                        rs.getLong("amostras"),
                });
            });

            cache.upsertAll(sistema, metrica, linhas);
            totalAtendentes = Math.max(totalAtendentes, linhas.size());
        }

        log.debug("agg_tempos_atendente atualizado ({}): {} atendentes, métricas {}.",
                sistema, totalAtendentes, d.getTempos().keySet());
    }
}
