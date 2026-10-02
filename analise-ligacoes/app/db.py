"""Bancos: lê as ligações do MariaDB compartilhado (DB_*, tabela db_native) e grava a análise no app-db
(APP_DB_*), nas tabelas analise_execucao e analise_ligacao (criadas aqui no startup)."""
import os
import time

import pymysql

SCHEMA = [
    """CREATE TABLE IF NOT EXISTS analise_execucao (
        data DATE NOT NULL PRIMARY KEY,
        status VARCHAR(20) NOT NULL,            -- processando | analisando | pronto | erro
        iniciada DATETIME NULL,
        terminada DATETIME NULL,
        total INT NOT NULL DEFAULT 0,
        modelo VARCHAR(40) NULL,
        erro TEXT NULL
    ) DEFAULT CHARSET=utf8mb4""",
    """CREATE TABLE IF NOT EXISTS analise_ligacao (
        data DATE NOT NULL,
        protocolo VARCHAR(50) NOT NULL,
        hora TIME NOT NULL,
        agente VARCHAR(255) NULL,
        fila VARCHAR(100) NULL,
        seg INT NULL,                           -- tempo de atendimento (db_native.atendimento)
        desconexao VARCHAR(50) NULL,
        sentido VARCHAR(50) NULL,
        gravacao TEXT NULL,
        religou_min INT NULL,                   -- cliente ligou de novo N min depois
        status TINYINT NOT NULL DEFAULT 0,      -- 0 = ainda não feito, 1 = trabalhando, 2 = pronto
        etapa VARCHAR(20) NULL,                 -- baixando | transcrevendo | pronto
        worker VARCHAR(20) NULL,
        inicio DATETIME NULL,
        fim DATETIME NULL,
        erro TEXT NULL,
        duracao DOUBLE NULL,
        fala_seg DOUBLE NULL,
        inicio_fala DOUBLE NULL,
        buracos TEXT NULL,                      -- JSON [[ini, fim], ...] sem fala
        transcrito TEXT NULL,                   -- JSON trechos transcritos
        segmentos MEDIUMTEXT NULL,              -- JSON [{ini, fim, texto}] já mascarado
        marcas TEXT NULL,                       -- JSON
        peso INT NULL,
        suspeita TINYINT NOT NULL DEFAULT 0,
        cadeia TEXT NULL,                       -- JSON ["hh:mm:ss agente", ...] da transferência
        trecho MEDIUMTEXT NULL,                 -- JSON linhas do atendente (vai para a IA)
        ia_enquadra VARCHAR(20) NULL,           -- sim | nao | inconclusivo
        ia_confianca DOUBLE NULL,
        ia_sentimento VARCHAR(20) NULL,
        ia_motivo VARCHAR(255) NULL,
        ia_justificativa TEXT NULL,
        ia_json MEDIUMTEXT NULL,
        ia_em DATETIME NULL,
        PRIMARY KEY (data, protocolo),
        KEY ix_status (data, status),
        KEY ix_suspeita (data, suspeita)
    ) DEFAULT CHARSET=utf8mb4""",
    # Histórico: todas as ligações atendidas, transcrição completa e análise da IA (sem guardar áudio).
    """CREATE TABLE IF NOT EXISTS conversa_dia (
        data DATE NOT NULL PRIMARY KEY,
        status TINYINT NOT NULL DEFAULT 0,      -- 0 = ainda não feito, 1 = trabalhando, 2 = pronto
        etapa VARCHAR(20) NULL,                 -- transcrevendo | ia | pronto | indisponivel
        total INT NOT NULL DEFAULT 0,
        transcritas INT NOT NULL DEFAULT 0,
        analisadas INT NOT NULL DEFAULT 0,
        erros INT NOT NULL DEFAULT 0,
        iniciada DATETIME NULL,
        terminada DATETIME NULL,
        observacao TEXT NULL
    ) DEFAULT CHARSET=utf8mb4""",
    """CREATE TABLE IF NOT EXISTS conversa_ligacao (
        protocolo VARCHAR(50) NOT NULL PRIMARY KEY,
        data DATE NOT NULL,
        data_hora DATETIME NOT NULL,
        agente VARCHAR(255) NULL,
        fila VARCHAR(100) NULL,
        sentido VARCHAR(50) NULL,
        desconexao VARCHAR(50) NULL,           -- Origem = cliente desligou, Destino = atendente, Transferida
        espera_seg INT NULL,
        atendimento_seg INT NULL,
        cidade VARCHAR(255) NULL,
        religou_min INT NULL,                   -- cliente ligou de novo N min depois do fim desta
        gravacao TEXT NULL,
        status TINYINT NOT NULL DEFAULT 0,      -- 0 = ainda não feito, 1 = trabalhando, 2 = pronto
        etapa VARCHAR(20) NULL,                 -- baixando | transcrevendo | transcrita | ia | pronto
        erro TEXT NULL,
        inicio DATETIME NULL,
        fim DATETIME NULL,
        -- áudio (só números; o arquivo é apagado logo depois de transcrito)
        duracao_audio DOUBLE NULL,
        fala_seg DOUBLE NULL,
        silencio_pct DOUBLE NULL,
        inicio_fala DOUBLE NULL,
        maior_silencio DOUBLE NULL,
        buracos TEXT NULL,                      -- JSON [[ini, fim], ...] sem fala
        transcricao MEDIUMTEXT NULL,            -- JSON [{ini, fim, texto}], inteira e mascarada
        palavras INT NULL,
        modelo_stt VARCHAR(40) NULL,
        -- regras (mesmas da análise diária)
        marcas TEXT NULL,
        peso INT NULL,
        cadeia TEXT NULL,
        -- IA
        resumo TEXT NULL,
        motivo VARCHAR(255) NULL,
        categoria VARCHAR(40) NULL,
        resolvido VARCHAR(20) NULL,             -- sim | nao | parcial | encaminhado | indefinido
        sentimento_inicio VARCHAR(20) NULL,
        sentimento_fim VARCHAR(20) NULL,
        satisfacao_estimada TINYINT NULL,       -- 1 a 5
        risco_cancelamento TINYINT NULL,
        ligacao_interna TINYINT NULL,
        regra_mudo VARCHAR(20) NULL,            -- sim | nao | inconclusivo
        regra_mudo_justificativa TEXT NULL,
        roteiro TEXT NULL,                      -- JSON {saudacao, identificou_cliente, informou_protocolo, ...}
        pontos_atencao TEXT NULL,
        palavras_chave TEXT NULL,               -- JSON
        ia_confianca DOUBLE NULL,
        ia_json MEDIUMTEXT NULL,
        ia_modelo VARCHAR(60) NULL,
        ia_em DATETIME NULL,
        ia_erro TEXT NULL,
        KEY ix_dia (data, status),
        KEY ix_agente (agente, data),
        KEY ix_categoria (categoria, data),
        KEY ix_regra (regra_mudo, data)
    ) DEFAULT CHARSET=utf8mb4""",
    # Reserva: quem está com a ligação (servidor "cpu-…" ou ajudante "gpu-…") e até quando. Ninguém pega uma
    # ligação reservada; a entrega só vale para quem ainda tem a reserva. tentativas = erros da própria ligação.
    """ALTER TABLE conversa_ligacao
        ADD COLUMN IF NOT EXISTS dono VARCHAR(60) NULL,
        ADD COLUMN IF NOT EXISTS prazo DATETIME NULL,
        ADD COLUMN IF NOT EXISTS tentativas TINYINT NOT NULL DEFAULT 0,
        ADD INDEX IF NOT EXISTS ix_dono (dono)""",
]


def _conectar(prefixo, tentativas=1):
    for n in range(tentativas):
        try:
            return pymysql.connect(
                host=os.environ[f"{prefixo}HOST"], port=int(os.environ.get(f"{prefixo}PORT", "3306")),
                user=os.environ[f"{prefixo}USER"], password=os.environ[f"{prefixo}PASSWORD"],
                database=os.environ[f"{prefixo}NAME"], charset="utf8mb4", autocommit=True,
                cursorclass=pymysql.cursors.DictCursor, connect_timeout=10, read_timeout=300, write_timeout=300)
        except pymysql.err.OperationalError:
            if n == tentativas - 1:
                raise
            time.sleep(5)


def app(tentativas=1):
    return _conectar("APP_DB_", tentativas)


def origem():
    return _conectar("DB_")


def criar_schema():
    with app(tentativas=24) as c, c.cursor() as cur:
        for sql in SCHEMA:
            cur.execute(sql)
