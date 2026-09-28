package com.sebratel.dashboards.common.cache;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Read/write access to {@code agg_tempos_atendente} — the pre-computed per-atendente TMA/TME (and
 * equivalents) cache that {@link TemposAtendenteRefreshJob} refreshes every minute. Reading this
 * table is a single indexed lookup by (sistema, atendente); the alternative — scanning and parsing
 * every "HH:MM:SS" row in the window on every request — is what this exists to avoid. See
 * {@code sql/agg_tempos_atendente.sql} for the DDL (run once manually; this repo has no migrations).
 */
@Component
public class TemposAtendenteCache {

    private static final Logger log = LoggerFactory.getLogger(TemposAtendenteCache.class);

    private final JdbcTemplate jdbcTemplate;
    private final JdbcTemplate writerJdbcTemplate;

    public TemposAtendenteCache(JdbcTemplate jdbcTemplate,
                                 @Qualifier("cacheWriterJdbcTemplate") JdbcTemplate writerJdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
        this.writerJdbcTemplate = writerJdbcTemplate;
    }

    /** metrica -> segundosMedios for one (sistema, atendente), or an empty map if never refreshed. */
    public Map<String, Double> get(String sistema, String atendente) {
        Map<String, Double> resultado = new LinkedHashMap<>();
        try {
            jdbcTemplate.query(
                    "SELECT metrica, segundos_medios FROM agg_tempos_atendente WHERE sistema = ? AND atendente = ?",
                    rs -> {
                        resultado.put(rs.getString("metrica"), (Double) rs.getObject("segundos_medios"));
                    },
                    sistema, atendente);
        } catch (DataAccessException e) {
            // A tabela ainda não foi criada (ver sql/agg_tempos_atendente.sql) — cai no fallback ao
            // vivo do chamador em vez de derrubar o endpoint.
            return Map.of();
        }
        return resultado;
    }

    /** True once at least one metric has been cached for this (sistema, atendente) pair. */
    public boolean disponivel(String sistema, String atendente) {
        return !get(sistema, atendente).isEmpty();
    }

    /** Upserts one row per (atendente, metrica) for this refresh cycle. */
    public void upsertAll(String sistema, String metrica, List<Object[]> linhas) {
        if (linhas.isEmpty()) {
            return;
        }
        Timestamp agora = Timestamp.valueOf(LocalDateTime.now());
        List<Object[]> params = linhas.stream()
                .map(l -> new Object[] { sistema, l[0], metrica, l[1], l[2], agora })
                .toList();
        try {
            writerJdbcTemplate.batchUpdate(
                    """
                    INSERT INTO agg_tempos_atendente (sistema, atendente, metrica, segundos_medios, amostras, atualizado_em)
                    VALUES (?, ?, ?, ?, ?, ?)
                    ON DUPLICATE KEY UPDATE
                        segundos_medios = VALUES(segundos_medios),
                        amostras = VALUES(amostras),
                        atualizado_em = VALUES(atualizado_em)
                    """,
                    params);
        } catch (DataAccessException e) {
            log.warn("Não foi possível gravar em agg_tempos_atendente — a tabela existe? "
                    + "(ver sql/agg_tempos_atendente.sql). Causa: {}", e.getMessage());
        }
    }
}
