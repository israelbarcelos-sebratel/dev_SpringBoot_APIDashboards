package com.sebratel.dashboards.common.auth;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * E-mail → atendente names, straight from the Matrix data: every db_matrix row carries the
 * agent's company e-mail next to the agent's name, so a Google-verified e-mail identifies its own
 * atendente(s) with no self-selection (which let anyone pick someone else's name).
 *
 * <p>One person can appear under several names (sector changes, "Backoffice - X" vs
 * "X - Backoffice"), so each e-mail maps to a list, most frequent first. Rebuilt every 10 minutes
 * (one grouped scan); both apps build it from db_matrix and use the same names for native.
 */
@Component
public class VinculoMatrix {

    private static final Logger log = LoggerFactory.getLogger(VinculoMatrix.class);

    private final JdbcTemplate jdbcTemplate;
    private final String tabela;
    private final String dominio;
    private volatile Map<String, List<String>> porEmail = Map.of();

    public VinculoMatrix(JdbcTemplate jdbcTemplate,
                         @Value("${app.vinculo.tabela:db_matrix}") String tabela,
                         @Value("${app.allowed-email-domain}") String dominio) {
        this.jdbcTemplate = jdbcTemplate;
        this.tabela = tabela;
        this.dominio = dominio;
    }

    /** Names for this e-mail, most frequent first; empty if the e-mail never attended in Matrix. */
    public List<String> nomes(String email) {
        return porEmail.getOrDefault(email.toLowerCase(Locale.ROOT), List.of());
    }

    @Scheduled(initialDelay = 1_000, fixedRate = 600_000)
    public void refresh() {
        String sql = "SELECT LOWER(TRIM(email)) AS email, atendente, COUNT(*) AS n FROM `" + tabela + "` "
                + "WHERE email LIKE ? AND atendente IS NOT NULL AND atendente <> '' "
                + "GROUP BY LOWER(TRIM(email)), atendente ORDER BY email, n DESC";
        try {
            Map<String, List<String>> mapa = new HashMap<>();
            jdbcTemplate.query(sql, rs -> {
                mapa.computeIfAbsent(rs.getString("email"), k -> new ArrayList<>()).add(rs.getString("atendente"));
            }, "%@" + dominio);
            mapa.replaceAll((k, v) -> List.copyOf(v));
            porEmail = Map.copyOf(mapa);
            log.info("Vínculo e-mail → atendente carregado de {}: {} e-mails.", tabela, porEmail.size());
        } catch (DataAccessException e) {
            // Mantém o último mapa bom; sem nenhum, ninguém fica vinculado e o popup oferece o suporte.
            log.warn("Não foi possível carregar o vínculo e-mail → atendente de {}: {}", tabela, e.getMessage());
        }
    }
}
