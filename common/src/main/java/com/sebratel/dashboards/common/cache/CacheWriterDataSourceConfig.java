package com.sebratel.dashboards.common.cache;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.jdbc.DataSourceBuilder;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;

import javax.sql.DataSource;

/**
 * A second, write-capable connection to the same MariaDB schema, used only by
 * {@link TemposAtendenteCache} to upsert {@code agg_tempos_atendente}. The app's main datasource
 * (and its {@code JdbcTemplate}, wired everywhere else) runs as {@code dev_reading} — a read-only
 * account — so writing the cache needs its own credentials. Falls back to the main {@code DB_USER}/
 * {@code DB_PASSWORD} when {@code CACHE_DB_USER}/{@code CACHE_DB_PASSWORD} aren't set, which simply
 * keeps failing with the same "command denied" error {@link TemposAtendenteCache} already logs and
 * swallows — nothing crashes, the cache just never gets past its first refresh until a writable user
 * is configured (see {@code sql/agg_tempos_atendente.sql} for the grant this account needs).
 */
@Configuration
public class CacheWriterDataSourceConfig {

    @Bean
    @Qualifier("cacheWriterJdbcTemplate")
    public JdbcTemplate cacheWriterJdbcTemplate(
            @Value("${DB_HOST:10.0.11.171}") String host,
            @Value("${DB_PORT:3306}") String port,
            @Value("${DB_NAME:API_WebDeveloper}") String database,
            @Value("${CACHE_DB_USER:${DB_USER:}}") String user,
            @Value("${CACHE_DB_PASSWORD:${DB_PASSWORD:}}") String password) {
        DataSource dataSource = DataSourceBuilder.create()
                .driverClassName("org.mariadb.jdbc.Driver")
                .url("jdbc:mariadb://" + host + ":" + port + "/" + database)
                .username(user)
                .password(password)
                .build();
        return new JdbcTemplate(dataSource);
    }
}
