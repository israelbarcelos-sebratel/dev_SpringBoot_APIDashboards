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
