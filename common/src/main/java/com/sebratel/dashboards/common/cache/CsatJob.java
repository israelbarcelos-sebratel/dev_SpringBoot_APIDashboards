package com.sebratel.dashboards.common.cache;

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
 * The widget's CSAT ({@link WidgetProperties.Csat}): each atendente's average satisfaction score
 * today (every minute, like {@link TemposHojeJob}) and over the last {@code dias} days (every 10
 * minutes, like {@link ReferenciaMensalJob}) — few customers answer the survey, so a day alone is
 * often 0–5 answers and the longer average is what gives it meaning. Kept in memory; nothing is
 * computed unless {@code app.widget.csat} is configured (only where the survey data is reliable).
 */
@Component
public class CsatJob {

    private static final Logger log = LoggerFactory.getLogger(CsatJob.class);

    /** Average score over {@code amostras} answers, {@code satisfeitos} of them at or above the minimum. */
    public record Nota(double media, long amostras, long satisfeitos) {}

    private final JdbcTemplate jdbcTemplate;
    private final WidgetProperties widget;
    private volatile Map<String, Nota> hoje = Map.of();
    private volatile Map<String, Nota> periodo = Map.of();

    public CsatJob(JdbcTemplate jdbcTemplate, WidgetProperties widget) {
        this.jdbcTemplate = jdbcTemplate;
        this.widget = widget;
    }

    public boolean configurado() {
        return widget.getCsat().configurado();
    }

    /**
     * {@code {hoje, periodo, dias, satisfeitoMinimo, formula}} for several names combined as one
     * person; {@code hoje}/{@code periodo} null when there's no answer. Null if CSAT isn't configured.
     */
    public Map<String, Object> resumo(List<String> nomes) {
        if (!configurado()) {
            return null;
        }
        WidgetProperties.Csat c = widget.getCsat();
        Map<String, Object> r = new LinkedHashMap<>();
        r.put("hoje", somar(hoje, nomes));
        r.put("periodo", somar(periodo, nomes));
        r.put("dias", dias());
        r.put("satisfeitoMinimo", c.getSatisfeitoMinimo());
        r.put("formula", c.getFormula());
        return r;
    }

    /** Each answer weighs the same, so the names' averages are weighted by their number of answers. */
    static Nota somar(Map<String, Nota> porAtendente, List<String> nomes) {
        double soma = 0;
        long amostras = 0;
        long satisfeitos = 0;
        for (String nome : nomes) {
            Nota n = porAtendente.get(nome);
            if (n != null) {
                soma += n.media() * n.amostras();
                amostras += n.amostras();
                satisfeitos += n.satisfeitos();
            }
        }
        return amostras == 0 ? null : new Nota(soma / amostras, amostras, satisfeitos);
    }

    private int dias() {
        return Math.max(1, widget.getCsat().getDias());
    }

    @Scheduled(initialDelay = 4_000, fixedRate = 60_000)
    public void refreshHoje() {
        Map<String, Nota> n = calcular("CURDATE()");
        if (n != null) {
            hoje = n;
        }
    }

    @Scheduled(initialDelay = 9_000, fixedRate = 600_000)
    public void refreshPeriodo() {
        Map<String, Nota> n = calcular("CURDATE() - INTERVAL " + dias() + " DAY");
        if (n != null) {
            periodo = n;
        }
    }

    /** atendente -> Nota over the answers since {@code desde} (a DATE expression); null if off or on error. */
    private Map<String, Nota> calcular(String desde) {
        WidgetProperties.Csat c = widget.getCsat();
        if (!c.configurado()) {
            return null;
        }
        // Column names and the filter come only from application.yml. Scores outside 1–5 don't count.
        String nota = c.notaSql("");
        String sql = "SELECT `" + c.getAgente() + "` AS atendente, AVG(" + nota + ") AS media, COUNT(*) AS n,"
                + " SUM(" + nota + " >= " + c.getSatisfeitoMinimo() + ") AS satisfeitos"
                + " FROM `" + c.getTabela() + "` WHERE `" + c.getData() + "` >= " + desde
                + " AND `" + c.getAgente() + "` IS NOT NULL AND `" + c.getAgente() + "` NOT IN ('', 'null')"
                + " AND " + nota + " BETWEEN 1 AND 5"
                + c.filtroSql()
                + " GROUP BY `" + c.getAgente() + "`";
        try {
            Map<String, Nota> resultado = new HashMap<>();
            jdbcTemplate.query(sql, rs -> {
                resultado.put(rs.getString("atendente"),
                        new Nota(rs.getDouble("media"), rs.getLong("n"), rs.getLong("satisfeitos")));
            });
            return Map.copyOf(resultado);
        } catch (DataAccessException e) {
            // Mantém o último cálculo bom.
            log.warn("Não foi possível calcular o CSAT do widget ({}): {}", c.getTabela(), e.getMessage());
            return null;
        }
    }
}
