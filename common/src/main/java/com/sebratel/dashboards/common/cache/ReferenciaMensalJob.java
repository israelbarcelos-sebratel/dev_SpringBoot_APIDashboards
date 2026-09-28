package com.sebratel.dashboards.common.cache;

import com.sebratel.dashboards.common.cache.TemposHojeJob.Tempo;
import com.sebratel.dashboards.common.config.SemanticDomainProperties;
import com.sebratel.dashboards.common.config.SemanticDomainProperties.Domain;
import com.sebratel.dashboards.common.config.WidgetProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.text.Normalizer;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * The widget's longer-period numbers, which change slowly and so are refreshed every 10 minutes
 * (and right after midnight) instead of every minute like {@link TemposHojeJob}:
 * <ul>
 *   <li>each atendente's TMEA over the last {@code app.widget.tmea.dias-referencia} days, from which
 *       {@link #tmeaSetor} averages the other atendentes of the same sector;</li>
 *   <li>each atendente's calls from the 1st of the month until yesterday — the widget adds today's
 *       live count to get the month-to-date total.</li>
 * </ul>
 *
 * <p>The sector is not a column: it's part of the atendente's name ("Ana Pires - Vendas Interno",
 * sometimes "Backoffice - Eduarda Keller"), see {@link #setor}.
 */
@Component
public class ReferenciaMensalJob {

    private static final Logger log = LoggerFactory.getLogger(ReferenciaMensalJob.class);
    private static final String DOMINIO = "atendimentos";
    private static final String DIMENSAO_ATENDENTE = "atendente";
    /** Atendentes with fewer gaps than this in the period don't weigh in the sector average. */
    private static final int MIN_INTERVALOS = 10;

    /**
     * @param setor           sector label ("Financeiro"), or null when the name has none — then the
     *                        reference is the whole operation of this system
     * @param segundosMedios  average of the other atendentes' TMEA (each atendente weighs the same)
     * @param atendentes      how many atendentes went into it
     * @param dias            length of the period, in days
     */
    public record TmeaReferencia(String setor, double segundosMedios, int atendentes, int dias) {}

    /** @param referenteA the DB's CURDATE() ("YYYY-MM-DD") this was computed for */
    private record Dados(Map<String, Tempo> tmea, Map<String, String> setores, Map<String, Long> mesAteOntem,
                         String referenteA) {}

    private final JdbcTemplate jdbcTemplate;
    private final SemanticDomainProperties props;
    private final WidgetProperties widget;
    private final TemposHojeJob temposHoje;
    private volatile Dados dados = new Dados(Map.of(), Map.of(), Map.of(), null);

    public ReferenciaMensalJob(JdbcTemplate jdbcTemplate, SemanticDomainProperties props, WidgetProperties widget,
                               TemposHojeJob temposHoje) {
        this.jdbcTemplate = jdbcTemplate;
        this.props = props;
        this.widget = widget;
        this.temposHoje = temposHoje;
    }

    /**
     * Average TMEA of the other atendentes of the caller's sector (the sector of the first of
     * {@code nomes} that has one) over the last days; the caller's own names are left out. Null
     * while not computed yet or when nobody else has enough data.
     */
    public TmeaReferencia tmeaSetor(List<String> nomes) {
        Dados d = dados;
        String setor = nomes.stream().map(n -> d.setores().get(n)).filter(s -> s != null).findFirst().orElse(null);
        double soma = 0;
        int atendentes = 0;
        for (Map.Entry<String, Tempo> e : d.tmea().entrySet()) {
            if (nomes.contains(e.getKey()) || e.getValue().amostras() < MIN_INTERVALOS) {
                continue;
            }
            if (setor != null && !setor.equals(d.setores().get(e.getKey()))) {
                continue;
            }
            soma += e.getValue().segundosMedios();
            atendentes++;
        }
        if (atendentes == 0) {
            return null;
        }
        return new TmeaReferencia(setor == null ? null : rotulo(d, setor), soma / atendentes, atendentes,
                widget.getTmea().getDiasReferencia());
    }

    /**
     * Calls from the 1st of the month until yesterday, for these names combined; null if the numbers
     * aren't for {@code hoje} ("YYYY-MM-DD", the DB date of today's snapshot) — right after midnight,
     * until the next refresh, rather than showing a wrong month total.
     */
    public Long atendimentosMesAteOntem(List<String> nomes, String hoje) {
        Dados d = dados;
        if (d.referenteA() == null || hoje == null || !hoje.startsWith(d.referenteA())) {
            return null;
        }
        return nomes.stream().mapToLong(n -> d.mesAteOntem().getOrDefault(n, 0L)).sum();
    }

    @Scheduled(initialDelay = 8_000, fixedRate = 600_000)
    public void refresh() {
        Domain d = props.domain(DOMINIO);
        String atendenteCol = d == null ? null : d.getDimensoes().get(DIMENSAO_ATENDENTE);
        String dataCol = widget.getDataColuna();
        if (d == null || atendenteCol == null || dataCol == null || dataCol.isBlank()) {
            return;
        }
        try {
            String hoje = jdbcTemplate.queryForObject("SELECT CAST(CURDATE() AS CHAR)", String.class);
            int dias = Math.max(1, widget.getTmea().getDiasReferencia());
            Map<String, Tempo> tmea = temposHoje.tmeaPorAtendente(
                    "`" + dataCol + "` >= DATE_FORMAT(CURDATE() - INTERVAL " + dias + " DAY, '%Y-%m-%d')");

            Map<String, Long> mes = new HashMap<>();
            jdbcTemplate.query("SELECT `" + atendenteCol + "` AS atendente, COUNT(*) AS n FROM `" + d.getTabela()
                    + "` WHERE `" + dataCol + "` >= DATE_FORMAT(CURDATE(), '%Y-%m-01')"
                    + " AND `" + dataCol + "` < DATE_FORMAT(CURDATE(), '%Y-%m-%d')"
                    + " AND `" + atendenteCol + "` IS NOT NULL AND `" + atendenteCol + "` <> ''"
                    + " GROUP BY `" + atendenteCol + "`",
                    rs -> {
                        mes.put(rs.getString("atendente"), rs.getLong("n"));
                    });

            dados = new Dados(Map.copyOf(tmea), setores(tmea.keySet()), Map.copyOf(mes), hoje);
            log.debug("Referência mensal do widget ({}): {} atendentes com TMEA, {} com atendimentos no mês.",
                    d.getTabela(), tmea.size(), mes.size());
        } catch (DataAccessException e) {
            log.warn("Não foi possível calcular a referência mensal do widget ({}): {}", d.getTabela(), e.getMessage());
        }
    }

    /** Right after midnight, so the month total doesn't stay blank for up to 10 minutes. */
    @Scheduled(cron = "20 0 0 * * *")
    public void refreshAposMeiaNoite() {
        refresh();
    }

    /**
     * atendente -> normalized sector key, plus "#"+key -> label. A sector is a name part (split on
     * "-") that ends at least two different names ("... - Financeiro"); a name's sector is its last
     * part if that's a sector, else its first ("Backoffice - Eduarda Keller"), else none.
     */
    static Map<String, String> setores(Iterable<String> nomes) {
        Map<String, Integer> finais = new HashMap<>();
        Map<String, String> rotulos = new HashMap<>();
        for (String nome : nomes) {
            String[] partes = partes(nome);
            if (partes.length > 1) {
                String ultima = partes[partes.length - 1];
                finais.merge(chave(ultima), 1, Integer::sum);
                // Rótulo: a grafia que começa com maiúscula ("Financeiro", não "financeiro").
                rotulos.merge(chave(ultima), ultima, (a, b) -> Character.isUpperCase(a.charAt(0)) ? a : b);
            }
        }
        Map<String, String> resultado = new HashMap<>();
        for (String nome : nomes) {
            String[] partes = partes(nome);
            if (partes.length < 2) {
                continue;
            }
            String ultima = chave(partes[partes.length - 1]);
            String primeira = chave(partes[0]);
            String setor = finais.getOrDefault(ultima, 0) >= 2 ? ultima
                    : finais.getOrDefault(primeira, 0) >= 2 ? primeira : null;
            if (setor != null) {
                resultado.put(nome, setor);
                resultado.put("#" + setor, rotulos.get(setor));
            }
        }
        return Map.copyOf(resultado);
    }

    private static String rotulo(Dados d, String setor) {
        return d.setores().getOrDefault("#" + setor, setor);
    }

    private static String[] partes(String nome) {
        return nome == null ? new String[0] : nome.trim().split("\\s*-\\s*");
    }

    private static String chave(String parte) {
        String semAcento = Normalizer.normalize(parte, Normalizer.Form.NFD).replaceAll("\\p{M}", "");
        return semAcento.trim().replaceAll("\\s+", " ").toLowerCase(Locale.ROOT);
    }
}
