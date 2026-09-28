-- Cache pré-calculado de TMA/TME (e equivalentes) por atendente, atualizado a cada minuto por
-- TemposAtendenteRefreshJob (common/.../cache). Compartilhada pelo matrix-api e native-api — cada
-- processo escreve suas próprias linhas, identificadas por `sistema` (app.group.name: "matrix"/"native").
--
-- Sem isso, GET /atendimentos/tempos?atendente=X faria um full scan + parse em Java de "HH:MM:SS"
-- sobre toda a janela (até 6 semanas de linhas) a cada chamada — caro demais para um endpoint que a
-- extensão de Chrome do time de suporte consulta a cada 15s por atendente.
--
-- Rodar uma vez manualmente no MariaDB (schema API_WebDeveloper); este repo não usa Flyway/Liquibase.
--
-- O usuário configurado em DB_USER (dev_reading) é somente leitura e não pode escrever aqui. O job
-- de refresh usa um usuário separado (CACHE_DB_USER/CACHE_DB_PASSWORD no .env — ver .env.example),
-- que precisa deste GRANT mínimo (troque '<usuario_escrita>' pelo usuário real):
--   GRANT INSERT, UPDATE ON API_WebDeveloper.agg_tempos_atendente TO '<usuario_escrita>'@'%';
--   FLUSH PRIVILEGES;
CREATE TABLE IF NOT EXISTS agg_tempos_atendente (
    sistema         VARCHAR(16)  NOT NULL,
    atendente       VARCHAR(255) NOT NULL,
    metrica         VARCHAR(32)  NOT NULL,
    segundos_medios DOUBLE       NULL,
    amostras        BIGINT       NOT NULL DEFAULT 0,
    atualizado_em   DATETIME     NOT NULL,
    PRIMARY KEY (sistema, atendente, metrica)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
