"""Processamento de um dia: lista as ligações atendidas, baixa as gravações adiantado (threads), transcreve em
N processos (um modelo Whisper cada) e depois aplica as regras. Tudo fica em analise_ligacao, com o status
0 = ainda não feito, 1 = trabalhando, 2 = pronto. Retoma sozinho: o que está em 2 não é refeito."""
import datetime as dt
import gc
import json
import logging
import multiprocessing as mp
import os
import re
import threading
import urllib.error
import urllib.request
from collections import deque
from concurrent.futures import FIRST_COMPLETED, ProcessPoolExecutor, ThreadPoolExecutor, wait
from concurrent.futures.process import BrokenProcessPool

from . import db, regras

log = logging.getLogger("analise")

SR = 16000
CURTA = 120      # s: ligação curta -> transcreve inteira
INICIO = 20.0    # s transcritos do começo de uma ligação longa
FIM = 45.0       # s transcritos do fim
WORKERS = int(os.environ.get("WORKERS", "2"))
THREADS = int(os.environ.get("THREADS", "3"))
MODELO = os.environ.get("MODELO", "medium")
RETENCAO_DIAS = int(os.environ.get("RETENCAO_DIAS", "90"))
TMP = os.environ.get("TMP_AUDIO", "/tmp/audios")
HREF = re.compile(r'href="([^"]+)"')
# O faster-whisper vai acumulando memória num mesmo processo: cada worker é trocado depois de N ligações
# (recarregar o modelo leva ~10 s). Sem isso o sistema mata o worker no meio do dia.
RECICLAR = int(os.environ.get("RECICLAR_A_CADA", "40"))
QUEDAS_MAX = 2   # a mesma ligação estava no worker em 2 quedas -> erro nela e segue o dia

_lock = threading.Lock()
_rodando = {"data": None}
# Um Whisper por vez no container (2 não cabem na memória). A análise diária tem prioridade: ela avisa em
# DIARIO_QUER e o histórico solta a trava no fim do lote em que estiver.
WHISPER = threading.Lock()
DIARIO_QUER = threading.Event()

def hms(v):
    """TIME do MariaDB chega como timedelta."""
    if isinstance(v, dt.timedelta):
        s = int(v.total_seconds())
        return f"{s // 3600:02d}:{s % 3600 // 60:02d}:{s % 60:02d}"
    return str(v)


# ---------------------------------------------------------------- worker (processo separado)
_modelo = None
_em_lote = None  # GPU: trechos de fala transcritos em lote (~2,5x mais rápido que um por vez, mesmo texto)
LOTE_GPU = int(os.environ.get("BATCH_GPU", "8"))


def _iniciar_worker(modelo, threads, dispositivo="cpu"):
    global _modelo, _em_lote
    from faster_whisper import BatchedInferencePipeline, WhisperModel
    _modelo = WhisperModel(modelo, device=dispositivo, cpu_threads=threads,
                           compute_type="int8" if dispositivo == "cpu" else os.environ.get("COMPUTE_GPU", "int8_float16"))
    if dispositivo != "cpu" and LOTE_GPU > 1:
        _em_lote = BatchedInferencePipeline(_modelo)


def transcrever(protocolo, caminho, completa=False):
    """VAD + Whisper de uma gravação; devolve só números e texto já mascarado. `completa`: a ligação inteira
    (histórico, para o resumo); senão, nas longas, só o começo e o fim."""
    from faster_whisper import decode_audio
    from faster_whisper.vad import VadOptions, get_speech_timestamps
    r = {"protocolo": protocolo, "worker": f"w{os.getpid()}"}
    audio = None
    try:
        audio = decode_audio(caminho, sampling_rate=SR)
        dur = len(audio) / SR
        fala = [(s["start"] / SR, s["end"] / SR)
                for s in get_speech_timestamps(audio, VadOptions(min_silence_duration_ms=1000, speech_pad_ms=200))]
        buracos, ultimo = [], 0.0
        for a, b in fala:
            if a - ultimo >= 3:
                buracos.append([round(ultimo, 1), round(a, 1)])
            ultimo = b
        if dur - ultimo >= 3:
            buracos.append([round(ultimo, 1), round(dur, 1)])
        # Curta: inteira. Longa: só o começo e o fim (onde o mudo / "linha com problema" aparece).
        partes = [(0.0, dur)] if completa or dur <= CURTA else [(0.0, INICIO), (dur - FIM, dur)]
        segs = []
        for a, b in partes:
            trecho = audio[int(a * SR):int(b * SR)]
            if _em_lote is not None:
                it, _ = _em_lote.transcribe(trecho, language="pt", batch_size=LOTE_GPU, beam_size=1,
                                            without_timestamps=False, vad_parameters={"min_silence_duration_ms": 1000})
            else:
                it, _ = _modelo.transcribe(trecho, language="pt", vad_filter=True, beam_size=1,
                                           vad_parameters={"min_silence_duration_ms": 1000})
            segs += [{"ini": round(a + s.start, 1), "fim": round(a + s.end, 1), "texto": regras.mascarar(s.text.strip())}
                     for s in it]
        r.update(duracao=round(dur, 1), falaSeg=round(sum(b - a for a, b in fala), 1),
                 inicioFala=round(fala[0][0], 1) if fala else None, buracos=buracos,
                 transcrito=[[round(a, 1), round(b, 1)] for a, b in partes], segmentos=segs)
    except Exception as e:  # noqa: BLE001 - uma gravação ruim não derruba o dia
        r["erro"] = f"transcrição: {e}"[:500]
    finally:
        audio = None
        gc.collect()
        if os.path.exists(caminho):
            os.remove(caminho)
    return r


# ---------------------------------------------------------------- coordenador (thread do servidor)
def rodando():
    return _rodando["data"]


def iniciar(data, limite=None, reprocessar=False):
    """Dispara o processamento em segundo plano. False se já há outro dia rodando."""
    with _lock:
        if _rodando["data"] is not None:
            return False
        _rodando["data"] = data
    threading.Thread(target=_executar, args=(data, limite, reprocessar), daemon=True, name=f"analise-{data}").start()
    return True


def _executar(data, limite, reprocessar):
    con = db.app()
    try:
        with con.cursor() as cur:
            if reprocessar:
                cur.execute("DELETE FROM analise_ligacao WHERE data=%s", (data,))
            cur.execute("INSERT INTO analise_execucao (data, status, iniciada, modelo) VALUES (%s, 'processando', NOW(), %s)"
                        " ON DUPLICATE KEY UPDATE status='processando', iniciada=IFNULL(iniciada, NOW()), terminada=NULL,"
                        " erro=NULL, modelo=VALUES(modelo)", (data, MODELO))
        _carregar_lista(con, data, limite)
        _transcrever_pendentes(con, data)
        with con.cursor() as cur:
            cur.execute("UPDATE analise_execucao SET status='analisando' WHERE data=%s", (data,))
        analisar(con, data)
        with con.cursor() as cur:
            cur.execute("UPDATE analise_execucao SET status='pronto', terminada=NOW() WHERE data=%s", (data,))
            cur.execute("DELETE FROM analise_ligacao WHERE data < CURDATE() - INTERVAL %s DAY", (RETENCAO_DIAS,))
            cur.execute("DELETE FROM analise_execucao WHERE data < CURDATE() - INTERVAL %s DAY", (RETENCAO_DIAS,))
        log.info("%s pronto", data)
    except Exception as e:  # noqa: BLE001
        log.exception("%s falhou", data)
        try:
            with con.cursor() as cur:
                cur.execute("UPDATE analise_execucao SET status='erro', erro=%s, terminada=NOW() WHERE data=%s",
                            (str(e)[:2000], data))
        except Exception:  # noqa: BLE001
            pass
    finally:
        con.close()
        with _lock:
            _rodando["data"] = None


def listar_dia(data):
    """Ligações atendidas do dia no db_native, com o link da gravação e se o cliente ligou de novo (próxima
    chamada do mesmo número em até 2 h depois do fim desta). O número do cliente não sai daqui."""
    with db.origem() as org, org.cursor() as cur:
        cur.execute("SELECT protocolo, data_hora, agente, fila, TIME_TO_SEC(atendimento) seg, TIME_TO_SEC(espera) espera,"
                    " desconexao, sentido, cidade_cliente, gravacao, numero, perdida FROM db_native"
                    " WHERE data_hora >= %s AND data_hora < %s + INTERVAL 1 DAY ORDER BY data_hora", (data, data))
        todas = cur.fetchall()
    por_numero = {}
    for r in todas:
        if r["numero"]:
            por_numero.setdefault(r["numero"], []).append(r["data_hora"])
    out = []
    for r in todas:
        if r["perdida"] is not None:
            continue
        href = HREF.search(r["gravacao"] or "")
        fim = r["data_hora"] + dt.timedelta(seconds=(r["seg"] or 0) + (r["espera"] or 0))
        prox = [t for t in por_numero.get(r["numero"], []) if fim < t <= fim + dt.timedelta(hours=2)]
        out.append({"protocolo": r["protocolo"], "data_hora": r["data_hora"], "agente": r["agente"], "fila": r["fila"],
                    "seg": r["seg"], "espera": r["espera"], "desconexao": r["desconexao"], "sentido": r["sentido"],
                    "cidade": r["cidade_cliente"], "gravacao": href.group(1) if href else None,
                    "religou": max(0, round((min(prox) - fim).total_seconds() / 60)) if prox else None})
    return out


def _carregar_lista(con, data, limite):
    """Ligações do dia (db_native) -> analise_ligacao com status 0 (as que já existem ficam como estão)."""
    linhas = [(data, r["protocolo"], r["data_hora"].time(), r["agente"], r["fila"], r["seg"], r["desconexao"],
               r["sentido"], r["gravacao"], r["religou"]) for r in listar_dia(data)]
    if limite:
        linhas = linhas[:limite]
    with con.cursor() as cur:
        cur.executemany("INSERT INTO analise_ligacao (data, protocolo, hora, agente, fila, seg, desconexao, sentido, gravacao,"
                        " religou_min) VALUES (%s,%s,%s,%s,%s,%s,%s,%s,%s,%s)"
                        " ON DUPLICATE KEY UPDATE religou_min=VALUES(religou_min)", linhas)
        cur.execute("UPDATE analise_ligacao SET status=0, etapa=NULL WHERE data=%s AND status=1", (data,))
        cur.execute("UPDATE analise_execucao SET total=(SELECT COUNT(*) FROM analise_ligacao WHERE data=%s) WHERE data=%s",
                    (data, data))
    log.info("%s: %d ligações atendidas", data, len(linhas))


def _baixar(protocolo, url, pasta=TMP):
    """O Native converte a gravação para .mp3 depois de um tempo, mas o link no banco continua .wav."""
    base, ext = os.path.splitext(url)
    tentativas = [url] + [base + e for e in (".mp3", ".wav") if e != ext]
    for n, u in enumerate(tentativas):
        caminho = os.path.join(pasta, protocolo + os.path.splitext(u)[1])
        try:
            urllib.request.urlretrieve(u, caminho)
            return caminho
        except urllib.error.HTTPError as e:
            if e.code != 404 or n == len(tentativas) - 1:
                raise


class _PoolCaiu(Exception):
    """Um worker morreu (quase sempre memória): leva as ligações que estavam em andamento."""

    def __init__(self, no_worker, baixando):
        super().__init__("worker do Whisper caiu")
        self.no_worker = no_worker
        self.baixando = baixando


def _transcrever_pendentes(con, data):
    os.makedirs(TMP, exist_ok=True)
    for f in os.listdir(TMP):
        os.remove(os.path.join(TMP, f))
    with con.cursor() as cur:
        cur.execute("SELECT protocolo, gravacao FROM analise_ligacao WHERE data=%s AND status=0 ORDER BY hora", (data,))
        pend = deque(cur.fetchall())
    por_id = {l["protocolo"]: l for l in pend}
    quedas = {}

    def marcar(sql, args):
        with con.cursor() as cur:
            cur.execute(sql, args)

    while True:
        try:
            DIARIO_QUER.set()  # o histórico solta o Whisper no fim do lote dele
            with WHISPER:
                DIARIO_QUER.clear()
                _rodar_pool(data, pend, marcar)
            return
        except _PoolCaiu as q:
            # Pool novo; quem estava em andamento volta para a fila (status 0), menos a ligação que já
            # derrubou o worker QUEDAS_MAX vezes.
            log.warning("%s: worker caiu com %s no Whisper; recriando", data, q.no_worker)
            for p in q.no_worker:
                quedas[p] = quedas.get(p, 0) + 1
            voltam = []
            for p in q.no_worker + q.baixando:
                if quedas.get(p, 0) >= QUEDAS_MAX:
                    marcar("UPDATE analise_ligacao SET status=2, etapa='pronto', fim=NOW(),"
                           " erro='o worker do Whisper caiu 2 vezes nesta ligação (memória?)' WHERE data=%s AND protocolo=%s",
                           (data, p))
                else:
                    marcar("UPDATE analise_ligacao SET status=0, etapa=NULL WHERE data=%s AND protocolo=%s", (data, p))
                    voltam.append(por_id[p])
            pend.extendleft(reversed(voltam))
            for f in os.listdir(TMP):
                os.remove(os.path.join(TMP, f))


def _rodar_pool(data, pend, marcar):
    em_voo = {}
    ctx = mp.get_context("spawn")
    with ThreadPoolExecutor(WORKERS) as downloads,             ProcessPoolExecutor(WORKERS, mp_context=ctx, initializer=_iniciar_worker, initargs=(MODELO, THREADS),
                                max_tasks_per_child=RECICLAR) as whisper:
        while pend or em_voo:
            while pend and len(em_voo) < WORKERS * 2:  # download adiantado: o worker nunca espera
                l = pend.popleft()
                if not l["gravacao"]:
                    marcar("UPDATE analise_ligacao SET status=2, etapa='pronto', fim=NOW(), erro='sem gravação'"
                           " WHERE data=%s AND protocolo=%s", (data, l["protocolo"]))
                    continue
                marcar("UPDATE analise_ligacao SET status=1, etapa='baixando', inicio=NOW() WHERE data=%s AND protocolo=%s",
                       (data, l["protocolo"]))
                em_voo[downloads.submit(_baixar, l["protocolo"], l["gravacao"])] = ("baixar", l["protocolo"])
            if not em_voo:
                break
            feitos, _ = wait(list(em_voo), return_when=FIRST_COMPLETED)
            for fut in feitos:
                tipo, p = em_voo.pop(fut)
                if tipo == "baixar":
                    try:
                        caminho = fut.result()
                    except Exception as e:  # noqa: BLE001
                        marcar("UPDATE analise_ligacao SET status=2, etapa='pronto', fim=NOW(), erro=%s"
                               " WHERE data=%s AND protocolo=%s", (f"download: {e}"[:500], data, p))
                        continue
                    marcar("UPDATE analise_ligacao SET etapa='transcrevendo' WHERE data=%s AND protocolo=%s", (data, p))
                    try:
                        em_voo[whisper.submit(transcrever, p, caminho)] = ("transcrever", p)
                    except BrokenProcessPool:
                        em_voo[fut] = ("transcrever", p)  # entra na conta das que estavam no worker
                        raise _PoolCaiu(*_em_andamento(em_voo)) from None
                else:
                    try:
                        r = fut.result()
                    except BrokenProcessPool:
                        em_voo[fut] = (tipo, p)
                        raise _PoolCaiu(*_em_andamento(em_voo)) from None
                    marcar("UPDATE analise_ligacao SET status=2, etapa='pronto', worker=%s, fim=NOW(), erro=%s, duracao=%s,"
                           " fala_seg=%s, inicio_fala=%s, buracos=%s, transcrito=%s, segmentos=%s"
                           " WHERE data=%s AND protocolo=%s",
                           (r["worker"], r.get("erro"), r.get("duracao"), r.get("falaSeg"), r.get("inicioFala"),
                            json.dumps(r.get("buracos")), json.dumps(r.get("transcrito")),
                            json.dumps(r.get("segmentos"), ensure_ascii=False), data, p))


def _em_andamento(em_voo):
    no_worker = [p for t, p in em_voo.values() if t == "transcrever"]
    baixando = [p for t, p in em_voo.values() if t == "baixar"]
    return no_worker, baixando


def analisar(con, data):
    """Regras + cadeias de transferência sobre o que já foi transcrito (pode rodar de novo à vontade)."""
    with con.cursor() as cur:
        cur.execute("SELECT protocolo, hora, agente, fila, seg, desconexao, religou_min, duracao, inicio_fala, buracos,"
                    " segmentos FROM analise_ligacao WHERE data=%s AND status=2 AND erro IS NULL AND duracao IS NOT NULL",
                    (data,))
        lig = [{"protocolo": r["protocolo"], "hora": hms(r["hora"]), "agente": r["agente"], "fila": r["fila"],
                "seg": r["seg"], "desconexao": r["desconexao"], "religouMin": r["religou_min"], "duracao": r["duracao"],
                "inicioFala": r["inicio_fala"], "buracos": json.loads(r["buracos"] or "[]"),
                "segmentos": json.loads(r["segmentos"] or "[]")} for r in cur.fetchall()]
    regras.montar_cadeias(lig)
    linhas = []
    for x in lig:
        marcas, peso, segs = regras.avaliar(x)
        suspeita = peso >= regras.PESO_SUSPEITA
        linhas.append((json.dumps(marcas, ensure_ascii=False), peso, int(suspeita),
                       json.dumps(x.get("cadeia"), ensure_ascii=False) if x.get("cadeia") else None,
                       json.dumps(regras.trecho(segs), ensure_ascii=False) if suspeita else None, data, x["protocolo"]))
    with con.cursor() as cur:
        cur.executemany("UPDATE analise_ligacao SET marcas=%s, peso=%s, suspeita=%s, cadeia=%s, trecho=%s"
                        " WHERE data=%s AND protocolo=%s", linhas)
    log.info("%s: %d ligações analisadas, %d suspeitas", data, len(linhas), sum(l[2] for l in linhas))
