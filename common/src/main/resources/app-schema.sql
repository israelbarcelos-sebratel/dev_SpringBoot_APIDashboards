-- Schema do banco próprio da aplicação (serviço app-db). Executado em todo startup por
-- DataSourcesConfig; por isso só CREATE ... IF NOT EXISTS.

-- Cache pré-calculado de TMA/TME (e equivalentes) por atendente, janela de 6 semanas, atualizado a
-- cada minuto por TemposAtendenteRefreshJob. matrix-api e native-api escrevem na mesma tabela,
-- cada um com seu `sistema` (app.group.name: "matrix"/"native").
CREATE TABLE IF NOT EXISTS agg_tempos_atendente (
    sistema         VARCHAR(16)  NOT NULL,
    atendente       VARCHAR(255) NOT NULL,
    metrica         VARCHAR(32)  NOT NULL,
    segundos_medios DOUBLE       NULL,
    amostras        BIGINT       NOT NULL DEFAULT 0,
    atualizado_em   DATETIME     NOT NULL,
    PRIMARY KEY (sistema, atendente, metrica)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- Vínculo e-mail Google -> atendente -> papel da extensão de Chrome (ver UsuarioRepository).
CREATE TABLE IF NOT EXISTS usuarios_extensao (
    email         VARCHAR(255) NOT NULL,
    atendente     VARCHAR(255) NOT NULL,
    role          VARCHAR(16)  NOT NULL DEFAULT 'user',
    atualizado_em DATETIME     NOT NULL,
    PRIMARY KEY (email)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
