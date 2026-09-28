package com.sebratel.dashboards.common.config;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.jdbc.DataSourceBuilder;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.init.DatabasePopulatorUtils;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;

import javax.sql.DataSource;

/**
 * Two databases:
 * <ul>
 *   <li><b>Source</b> ({@code spring.datasource}, DB_*): the shared MariaDB with db_native/db_matrix,
 *       read-only for this app. {@link Primary}, so every unqualified {@code JdbcTemplate} reads here.</li>
 *   <li><b>App</b> ({@code APP_DB_*}): the app's own MariaDB (the {@code app-db} service in
 *       docker-compose.yml / portainer-stack.yml) holding what this app <em>writes</em> — the
 *       extension's users/roles and the per-atendente tempos cache. Its tables are created at startup
 *       from {@code app-schema.sql}, so a fresh volume needs no manual step.</li>
 * </ul>
 * Both JdbcTemplates are declared explicitly: defining any JdbcTemplate bean turns off Spring Boot's
 * auto-configured one, which would silently route source reads to the app database.
 */
@Configuration
public class DataSourcesConfig {

    public static final String APP = "appJdbcTemplate";

    @Bean
    @Primary
    public JdbcTemplate jdbcTemplate(DataSource dataSource) {
        return new JdbcTemplate(dataSource);
    }

    @Bean
    @Qualifier(APP)
    public JdbcTemplate appJdbcTemplate(
            @Value("${APP_DB_HOST:localhost}") String host,
            @Value("${APP_DB_PORT:3306}") String port,
            @Value("${APP_DB_NAME:sebratel_app}") String database,
            @Value("${APP_DB_USER:sebratel_app}") String user,
            @Value("${APP_DB_PASSWORD:}") String password) {
        DataSource dataSource = DataSourceBuilder.create()
                .driverClassName("org.mariadb.jdbc.Driver")
                .url("jdbc:mariadb://" + host + ":" + port + "/" + database)
                .username(user)
                .password(password)
                .build();
        DatabasePopulatorUtils.execute(new ResourceDatabasePopulator(new ClassPathResource("app-schema.sql")), dataSource);
        return new JdbcTemplate(dataSource);
    }
}
