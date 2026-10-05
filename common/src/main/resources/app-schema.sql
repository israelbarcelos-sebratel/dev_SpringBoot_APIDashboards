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

-- Papel e exceções de vínculo dos usuários da extensão de Chrome (ver UsuarioRepository). O vínculo
-- e-mail -> atendente normal é automático, pela db_matrix; aqui só ficam o papel e, com manual = 1,
-- o atendente definido por um administrador.
CREATE TABLE IF NOT EXISTS usuarios_extensao (
    email         VARCHAR(255) NOT NULL,
    atendente     VARCHAR(255) NULL,
    role          VARCHAR(16)  NOT NULL DEFAULT 'user',
    manual        TINYINT(1)   NOT NULL DEFAULT 0,
    atualizado_em DATETIME     NOT NULL,
    PRIMARY KEY (email)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- Pseudônimos dos clientes (ver ClienteAlias): a tabela de atendimentos da extensão só mostra o
-- alias; a relação alias -> nome fica só aqui. nome_hash = SHA-256 do nome normalizado (mesmo
-- cliente, mesmo alias, nos dois sistemas).
CREATE TABLE IF NOT EXISTS cliente_alias (
    nome_hash CHAR(64)     NOT NULL,
    alias     VARCHAR(16)  NOT NULL,
    nome      VARCHAR(300) NOT NULL,
    criado_em DATETIME     NOT NULL,
    PRIMARY KEY (nome_hash),
    UNIQUE KEY uk_cliente_alias (alias)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- Bancos criados antes do vínculo automático: atendente era obrigatório e escolhido pelo próprio
-- usuário. Essas linhas antigas ficam com manual = 0 (o atendente escolhido deixa de valer).
ALTER TABLE usuarios_extensao MODIFY atendente VARCHAR(255) NULL;
ALTER TABLE usuarios_extensao ADD COLUMN IF NOT EXISTS manual TINYINT(1) NOT NULL DEFAULT 0;
-- Quem tem o e-mail em mais de um cadastro (trocou de área) escolhe qual usar; guardamos a última
-- escolha (NULL = todos os cadastros somados). Ver UsuarioRepository.salvarPreferido.
ALTER TABLE usuarios_extensao ADD COLUMN IF NOT EXISTS preferido VARCHAR(255) NULL;

-- Correspondência de nomes Matrix <-> Native ajustada pelo administrador (ver CorrespondenciaNomes -
-- os pares automáticos não ficam aqui, só os ajustes). acao = 'manual' (mesma pessoa; substitui os
-- pares automáticos dos dois nomes) ou 'bloqueado' (par automático errado). Nomes comparados exatos.
CREATE TABLE IF NOT EXISTS correspondencia_nomes (
    nome_matrix    VARCHAR(255) COLLATE utf8mb4_bin NOT NULL,
    nome_native    VARCHAR(255) COLLATE utf8mb4_bin NOT NULL,
    acao           VARCHAR(16)  NOT NULL,
    atualizado_por VARCHAR(255) NOT NULL,
    atualizado_em  DATETIME     NOT NULL,
    PRIMARY KEY (nome_matrix, nome_native)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- Equipe do suporte (planilha "Agentes e horários Suporte", ver EquipePlanilha): turno, horário e
-- supervisor(a) de cada pessoa. O n8n lê a planilha uma vez por dia e substitui o conteúdo inteiro
-- (PUT /interno/equipe); os nomes são os da planilha (completos), ligados aos do sistema na leitura.
CREATE TABLE IF NOT EXISTS equipe_planilha (
    nome          VARCHAR(255) NOT NULL,
    inicio        VARCHAR(255) NULL,
    fim           VARCHAR(255) NULL,
    supervisor    VARCHAR(255) NULL,
    turno         VARCHAR(255) NULL,
    atualizado_em DATETIME     NOT NULL,
    PRIMARY KEY (nome)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
