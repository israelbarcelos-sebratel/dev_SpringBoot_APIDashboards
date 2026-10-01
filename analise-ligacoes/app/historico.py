"""Histórico de conversas: todas as ligações atendidas, UM DIA POR VEZ, do mais antigo ao mais novo (o Native apaga
as gravações mais antigas primeiro). Para cada ligação: transcrição completa (Whisper local), regras do mudo e
análise da IA (só texto mascarado) em conversa_ligacao; o andamento por dia em conversa_dia (0/1/2).
O áudio é baixado para uma pasta temporária e apagado logo depois de transcrito — nada de áudio fica guardado.

Depois de terminar um dia passa sozinho para o próximo, até ontem. O n8n só chama POST /historico/avancar de
tempos em tempos: se o processo tiver parado (container reiniciado), ele volta de onde estava."""
import datetime as dt
import json
import logging
import multiprocessing as mp
import os
import random
import threading
import time
import urllib.error
import urllib.request
from collections import deque
from concurrent.futures import FIRST_COMPLETED, ProcessPoolExecutor, ThreadPoolExecutor, wait
from concurrent.futures.process import BrokenProcessPool

from . import db, ia, pipeline, regras

log = logging.getLogger("historico")

DESDE = os.environ.get("HISTORICO_DESDE", "")      # AAAA-MM-DD; vazio = primeiro dia que o db_native tem
MODELO = os.environ.get("MODELO_HISTORICO", "small")
THREADS = int(os.environ.get("THREADS_HISTORICO", str(pipeline.THREADS)))
LOTE = int(os.environ.get("LOTE_HISTORICO", "40"))  # ligações por worker; entre lotes a análise diária pode entrar
IA_PARALELO = int(os.environ.get("IA_PARALELO", "4"))
AMOSTRA = 12          # gravações testadas para saber se o dia ainda existe no Native
TMP = os.path.join(pipeline.TMP, "historico")

_estado = {"rodando": False, "dia": None, "erro": None}
_lock = threading.Lock()


def estado():
    return dict(_estado)


def avancar():
    """Garante o processo rodando. Devolve o que está acontecendo (não bloqueia)."""
    if not ia.configurado():
        return {"status": "erro", "erro": "GEMINI_API_KEY não configurada na stack"}
    with _lock:
        if _estado["rodando"]:
            return {"status": "rodando", "dia": _estado["dia"]}
        _estado.update(rodando=True, erro=None)
    threading.Thread(target=_loop, daemon=True, name="historico").start()
    return {"status": "iniciado"}


def _loop():
    con = db.app()
    try:
        while True:
            data = _proximo_dia(con)
            if data is None:
                log.info("histórico em dia até ontem")
                break
            _estado["dia"] = data
            _processar_dia(con, data)
    except Exception as e:  # noqa: BLE001 - o n8n chama de novo e ele retoma
        log.exception("histórico parou")
        _estado["erro"] = str(e)[:500]
    finally:
        con.close()
        _estado.update(rodando=False, dia=None)


def dias_candidatos():
    """Dias com ligação atendida, do mais antigo até ontem."""
    with db.origem() as org, org.cursor() as cur:
        cur.execute("SELECT DATE(data_hora) d FROM db_native WHERE perdida IS NULL AND data_hora < CURDATE()"
                    + (" AND data_hora >= %s" if DESDE else "") + " GROUP BY DATE(data_hora) ORDER BY d",
                    (DESDE,) if DESDE else ())
        return [str(r["d"]) for r in cur.fetchall()]


def _proximo_dia(con):
    with con.cursor() as cur:
        cur.execute("SELECT data FROM conversa_dia WHERE status=2")
        prontos = {str(r["data"]) for r in cur.fetchall()}
    return next((d for d in dias_candidatos() if d not in prontos), None)


def _gravacao_existe(url):
    base, ext = os.path.splitext(url)
    for u in [url] + [base + e for e in (".mp3", ".wav") if e != ext]:
        try:
            req = urllib.request.Request(u, headers={"Range": "bytes=0-15"})
            with urllib.request.urlopen(req, timeout=20):
                return True
        except urllib.error.HTTPError as e:
            if e.code != 404:
                return True  # outro erro: na dúvida tenta o dia
        except urllib.error.URLError:
            return True
    return False


def _processar_dia(con, data):
    def sql(q, a=()):
        with con.cursor() as cur:
            cur.execute(q, a)
            return cur.fetchall()

    sql("INSERT INTO conversa_dia (data, status, etapa, iniciada) VALUES (%s, 1, 'transcrevendo', NOW())"
        " ON DUPLICATE KEY UPDATE status=1, etapa='transcrevendo', iniciada=IFNULL(iniciada, NOW()), terminada=NULL", (data,))
    ja = sql("SELECT COUNT(*) n FROM conversa_ligacao WHERE data=%s", (data,))[0]["n"]
    linhas = pipeline.listar_dia(data)
    if not ja:
        amostra = random.sample([l for l in linhas if l["gravacao"]], min(AMOSTRA, sum(1 for l in linhas if l["gravacao"])))
        if amostra and not any(_gravacao_existe(l["gravacao"]) for l in amostra):
            sql("UPDATE conversa_dia SET status=2, etapa='indisponivel', total=%s, terminada=NOW(),"
                " observacao='gravações já apagadas no Native' WHERE data=%s", (len(linhas), data))
            log.info("%s: gravações indisponíveis no Native", data)
            return
    with con.cursor() as cur:
        cur.executemany(
            "INSERT IGNORE INTO conversa_ligacao (protocolo, data, data_hora, agente, fila, sentido, desconexao, espera_seg,"
            " atendimento_seg, cidade, religou_min, gravacao) VALUES (%s,%s,%s,%s,%s,%s,%s,%s,%s,%s,%s,%s)",
            [(l["protocolo"], data, l["data_hora"], l["agente"], l["fila"], l["sentido"], l["desconexao"], l["espera"],
              l["seg"], l["cidade"], l["religou"], l["gravacao"]) for l in linhas])
        # Retoma: o que estava baixando/transcrevendo volta para a fila; o que estava na IA volta para "transcrita".
        cur.execute("UPDATE conversa_ligacao SET status=0, etapa=NULL WHERE data=%s AND status=1 AND etapa IN"
                    " ('baixando','transcrevendo')", (data,))
        cur.execute("UPDATE conversa_ligacao SET etapa='transcrita' WHERE data=%s AND status=1 AND etapa='ia'", (data,))
        cur.execute("UPDATE conversa_dia SET total=(SELECT COUNT(*) FROM conversa_ligacao WHERE data=%s) WHERE data=%s",
                    (data, data))
    log.info("%s: %d ligações", data, len(linhas))

    pend = deque(sql("SELECT protocolo, gravacao FROM conversa_ligacao WHERE data=%s AND status=0 ORDER BY data_hora",
                     (data,)))
    quedas = {}
    while pend:
        lote = [pend.popleft() for _ in range(min(LOTE, len(pend)))]
        voltam = _lote(con, data, lote, quedas)
        pend.extendleft(reversed(voltam))
        _contar(con, data)

    sql("UPDATE conversa_dia SET etapa='ia' WHERE data=%s", (data,))
    _regras(con, data)
    _ia(con, data)
    _contar(con, data)
    sql("UPDATE conversa_dia SET status=2, etapa='pronto', terminada=NOW() WHERE data=%s", (data,))
    log.info("%s pronto", data)


def _contar(con, data):
    with con.cursor() as cur:
        cur.execute("UPDATE conversa_dia d SET"
                    " transcritas=(SELECT COUNT(*) FROM conversa_ligacao WHERE data=%s AND transcricao IS NOT NULL),"
                    " analisadas=(SELECT COUNT(*) FROM conversa_ligacao WHERE data=%s AND ia_em IS NOT NULL),"
                    " erros=(SELECT COUNT(*) FROM conversa_ligacao WHERE data=%s AND (erro IS NOT NULL OR ia_erro IS NOT NULL))"
                    " WHERE d.data=%s", (data, data, data, data))


def _esperar_vez():
    """Solta o Whisper para a análise diária (prioridade) e espera ela terminar."""
    while pipeline.DIARIO_QUER.is_set() or pipeline.rodando():
        time.sleep(30)


def _lote(con, data, lote, quedas):
    """Transcreve um lote com um worker novo (memória limpa). Devolve as ligações que voltam para a fila."""
    def marcar(q, a):
        with con.cursor() as cur:
            cur.execute(q, a)

    os.makedirs(TMP, exist_ok=True)
    _esperar_vez()
    fila = deque(lote)
    em_voo = {}
    with pipeline.WHISPER:
        with ThreadPoolExecutor(2) as downloads, \
                ProcessPoolExecutor(1, mp_context=mp.get_context("spawn"), initializer=pipeline._iniciar_worker,
                                    initargs=(MODELO, THREADS)) as whisper:
            try:
                while fila or em_voo:
                    while fila and len(em_voo) < 3:
                        l = fila.popleft()
                        if not l["gravacao"]:
                            marcar("UPDATE conversa_ligacao SET status=2, etapa='pronto', fim=NOW(), erro='sem gravação'"
                                   " WHERE protocolo=%s", (l["protocolo"],))
                            continue
                        marcar("UPDATE conversa_ligacao SET status=1, etapa='baixando', inicio=NOW() WHERE protocolo=%s",
                               (l["protocolo"],))
                        em_voo[downloads.submit(pipeline._baixar, l["protocolo"], l["gravacao"], TMP)] = ("baixar", l)
                    if not em_voo:
                        break
                    feitos, _ = wait(list(em_voo), return_when=FIRST_COMPLETED)
                    for fut in feitos:
                        tipo, l = em_voo.pop(fut)
                        p = l["protocolo"]
                        if tipo == "baixar":
                            try:
                                caminho = fut.result()
                            except urllib.error.HTTPError as e:
                                msg = "gravação não existe mais no Native" if e.code == 404 else f"download: HTTP {e.code}"
                                marcar("UPDATE conversa_ligacao SET status=2, etapa='pronto', fim=NOW(), erro=%s"
                                       " WHERE protocolo=%s", (msg, p))
                                continue
                            except Exception as e:  # noqa: BLE001
                                marcar("UPDATE conversa_ligacao SET status=2, etapa='pronto', fim=NOW(), erro=%s"
                                       " WHERE protocolo=%s", (f"download: {e}"[:500], p))
                                continue
                            marcar("UPDATE conversa_ligacao SET etapa='transcrevendo' WHERE protocolo=%s", (p,))
                            em_voo[whisper.submit(pipeline.transcrever, p, caminho, True)] = ("transcrever", l)
                        else:
                            r = fut.result()
                            if r.get("erro"):
                                marcar("UPDATE conversa_ligacao SET status=2, etapa='pronto', fim=NOW(), erro=%s"
                                       " WHERE protocolo=%s", (r["erro"], p))
                                continue
                            segs = r["segmentos"]
                            dur = r["duracao"] or 0
                            marcar("UPDATE conversa_ligacao SET etapa='transcrita', duracao_audio=%s, fala_seg=%s,"
                                   " silencio_pct=%s, inicio_fala=%s, maior_silencio=%s, buracos=%s, transcricao=%s,"
                                   " palavras=%s, modelo_stt=%s WHERE protocolo=%s",
                                   (dur, r["falaSeg"], round(100 * (1 - r["falaSeg"] / dur), 1) if dur else None,
                                    r["inicioFala"], max((b - a for a, b in r["buracos"]), default=0),
                                    json.dumps(r["buracos"]), json.dumps(segs, ensure_ascii=False),
                                    sum(len(s["texto"].split()) for s in segs), MODELO, p))
            except BrokenProcessPool:
                # Worker morreu (memória): quem estava em andamento volta para a fila; 2 quedas na mesma -> erro.
                log.warning("%s: worker caiu no histórico", data)
                voltam = []
                for tipo, l in list(em_voo.values()) + [(None, x) for x in fila]:
                    p = l["protocolo"]
                    if tipo == "transcrever":
                        quedas[p] = quedas.get(p, 0) + 1
                    if quedas.get(p, 0) >= pipeline.QUEDAS_MAX:
                        marcar("UPDATE conversa_ligacao SET status=2, etapa='pronto', fim=NOW(),"
                               " erro='o worker do Whisper caiu 2 vezes nesta ligação (memória?)' WHERE protocolo=%s", (p,))
                    else:
                        marcar("UPDATE conversa_ligacao SET status=0, etapa=NULL WHERE protocolo=%s", (p,))
                        voltam.append(l)
                return voltam
            finally:
                for f in os.listdir(TMP):
                    os.remove(os.path.join(TMP, f))
    return []


def _regras(con, data):
    """Marcas do mudo e cadeias de transferência sobre as transcrições completas do dia."""
    with con.cursor() as cur:
        cur.execute("SELECT protocolo, data_hora, agente, fila, atendimento_seg, desconexao, religou_min, duracao_audio,"
                    " inicio_fala, buracos, transcricao FROM conversa_ligacao WHERE data=%s AND transcricao IS NOT NULL",
                    (data,))
        lig = [{"protocolo": r["protocolo"], "hora": r["data_hora"].strftime("%H:%M:%S"), "agente": r["agente"],
                "fila": r["fila"], "seg": r["atendimento_seg"], "desconexao": r["desconexao"],
                "religouMin": r["religou_min"], "duracao": r["duracao_audio"], "inicioFala": r["inicio_fala"],
                "buracos": json.loads(r["buracos"] or "[]"), "segmentos": json.loads(r["transcricao"] or "[]")}
               for r in cur.fetchall()]
    regras.montar_cadeias(lig)
    linhas = []
    for x in lig:
        marcas, peso, _ = regras.avaliar(x)
        linhas.append((json.dumps(marcas, ensure_ascii=False), peso,
                       json.dumps(x.get("cadeia"), ensure_ascii=False) if x.get("cadeia") else None, x["protocolo"]))
    with con.cursor() as cur:
        cur.executemany("UPDATE conversa_ligacao SET marcas=%s, peso=%s, cadeia=%s WHERE protocolo=%s", linhas)


def _ia(con, data):
    """Análise da IA de cada ligação transcrita do dia (em paralelo, com espera quando o Gemini sobrecarrega)."""
    with con.cursor() as cur:
        cur.execute("SELECT protocolo, data_hora, agente, fila, espera_seg, atendimento_seg, duracao_audio, desconexao,"
                    " religou_min, marcas, cadeia, transcricao FROM conversa_ligacao"
                    " WHERE data=%s AND etapa='transcrita'", (data,))
        itens = cur.fetchall()
    trava = threading.Lock()

    def um(r):
        l = dict(r, segmentos=json.loads(r["transcricao"] or "[]"), marcas=json.loads(r["marcas"] or "[]"),
                 cadeia=json.loads(r["cadeia"]) if r["cadeia"] else None)
        try:
            x, modelo = ia.analisar(l)
            x = regras.mascarar_obj(x)  # o Gemini pode copiar números da transcrição
            rot = x.get("roteiro") or {}
            args = (x.get("resumo"), (x.get("motivo_contato") or "")[:255], x.get("categoria"), x.get("resolvido"),
                    x.get("sentimento_inicio"), x.get("sentimento_fim"), x.get("satisfacao_estimada"),
                    int(bool(x.get("risco_cancelamento"))), int(bool(x.get("ligacao_interna"))), x.get("regra_mudo"),
                    x.get("regra_mudo_justificativa"), json.dumps(rot, ensure_ascii=False), x.get("pontos_atencao"),
                    json.dumps(x.get("palavras_chave") or [], ensure_ascii=False), x.get("confianca"),
                    json.dumps(x, ensure_ascii=False), modelo, r["protocolo"])
            q = ("UPDATE conversa_ligacao SET resumo=%s, motivo=%s, categoria=%s, resolvido=%s, sentimento_inicio=%s,"
                 " sentimento_fim=%s, satisfacao_estimada=%s, risco_cancelamento=%s, ligacao_interna=%s, regra_mudo=%s,"
                 " regra_mudo_justificativa=%s, roteiro=%s, pontos_atencao=%s, palavras_chave=%s, ia_confianca=%s,"
                 " ia_json=%s, ia_modelo=%s, ia_em=NOW(), ia_erro=NULL, status=2, etapa='pronto', fim=NOW()"
                 " WHERE protocolo=%s")
        except Exception as e:  # noqa: BLE001 - fica registrado; POST /historico/reavaliar tenta de novo
            q, args = ("UPDATE conversa_ligacao SET ia_erro=%s, status=2, etapa='pronto', fim=NOW() WHERE protocolo=%s",
                       (str(e)[:500], r["protocolo"]))
        with trava, con.cursor() as cur:  # uma conexão só: as threads se revezam
            cur.execute(q, args)

    with ThreadPoolExecutor(IA_PARALELO) as ex:
        list(ex.map(um, itens))


def reavaliar(data=None):
    """Volta para a IA as ligações em que ela falhou (de um dia ou de todos) — roda em segundo plano."""
    def rodar():
        con = db.app()
        try:
            with con.cursor() as cur:
                cur.execute("UPDATE conversa_ligacao SET etapa='transcrita' WHERE ia_erro IS NOT NULL AND transcricao IS NOT NULL"
                            + (" AND data=%s" if data else ""), (data,) if data else ())
                cur.execute("SELECT DISTINCT data FROM conversa_ligacao WHERE etapa='transcrita'")
                dias = [str(r["data"]) for r in cur.fetchall()]
            for d in dias:
                _ia(con, d)
                _contar(con, d)
        finally:
            con.close()
    threading.Thread(target=rodar, daemon=True, name="historico-reavaliar").start()
