"""Histórico de conversas: todas as ligações atendidas, UM DIA POR VEZ, do mais antigo ao mais novo (o Native apaga
as gravações mais antigas primeiro). Para cada ligação: transcrição completa (Whisper local), regras do mudo e
análise da IA (só texto mascarado) em conversa_ligacao; o andamento por dia em conversa_dia (0/1/2).
O áudio é baixado para uma pasta temporária e apagado logo depois de transcrito — nada de áudio fica guardado.

Depois de terminar um dia passa sozinho para o próximo, até ontem. O n8n só chama POST /historico/avancar de
tempos em tempos: se o processo tiver parado (container reiniciado), ele volta de onde estava.

Ajudantes com GPU (app/gpu.py, numa máquina da Sebratel com placa NVIDIA) transcrevem o dia junto com o servidor, que
continua no ritmo dele. Quando o dia não tem mais o que transcrever (o servidor está na IA dele, ou terminando os
últimos lotes), os ajudantes já adiantam o dia seguinte: ele é registrado antes e o servidor o encontra meio pronto. Os dois tiram ligações da MESMA fila por reserva atômica no banco (dono + prazo): uma ligação
nunca fica com dois, e a entrega só vale para quem ainda tem a reserva. Erro numa ligação: ela volta para a fila (até
TENTATIVAS_MAX). Ajudante que some: a reserva vence e a ligação volta; o servidor segue sozinho."""
import datetime as dt
import decimal
import json
import logging
import multiprocessing as mp
import os
import random
import threading
import time
import urllib.error
import urllib.request
import uuid
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
TENTATIVAS_MAX = 3    # erros da própria ligação (download, áudio, worker que caiu nela) antes de desistir dela
CPU_PRAZO_MIN = 720   # reserva de um lote do servidor (pode esperar a análise diária)
GPU_TOKEN = os.environ.get("GPU_TOKEN", "")          # vazio = ajudantes com GPU desligados
GPU_PRAZO_MIN = int(os.environ.get("GPU_PRAZO_MIN", "20"))
GPU_SILENCIO = 180    # s sem notícia de nenhum ajudante -> não está mais ativo
LOTE_COM_GPU = 6      # com ajudante ativo o servidor pega lotes pequenos: o fim do dia não fica esperando um lote dele
GPU_LOTE_MAX = 16

_estado = {"rodando": False, "dia": None, "etapa": None, "erro": None}
_gpu = {}             # ajudante -> time.time() do último contato
_native_mes = {}      # "AAAA-MM" -> (time.time(), {dia: {ligacoes, seg}}): cache do volume do mês no db_native
_candidatos = [0.0, []]   # cache de dias_candidatos() para os pedidos dos ajudantes
ADIANTAR_DIAS = 3     # quantos dias à frente os ajudantes podem adiantar enquanto o servidor faz a IA
_adiantar_lock = threading.Lock()
_lock = threading.Lock()


def estado():
    agora = time.time()
    return dict(_estado, gpu={"ativa": gpu_ativa(), "ajudantes": {k: round(agora - t) for k, t in _gpu.items()}})


def gpu_ativa():
    agora = time.time()
    return any(agora - t < GPU_SILENCIO for t in _gpu.values())


def avancar():
    """Garante o processo rodando. Devolve o que está acontecendo (não bloqueia)."""
    if not ia.configurado():
        return {"status": "erro", "erro": "sem a chave do Gemini: o nó do n8n precisa usar a credencial do Gemini"}
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
        _estado.update(rodando=False, dia=None, etapa=None)


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


def _registrar(con, data):
    """Grava as ligações do dia (do db_native) em conversa_ligacao; as que já estão ficam como estão. Devolve quantas
    tem, ou None se as gravações do dia já foram apagadas no Native (o dia fica como indisponível)."""
    with con.cursor() as cur:
        cur.execute("SELECT COUNT(*) n FROM conversa_ligacao WHERE data=%s", (data,))
        ja = cur.fetchone()["n"]
    linhas = pipeline.listar_dia(data)
    with con.cursor() as cur:
        if not ja:
            amostra = random.sample([l for l in linhas if l["gravacao"]], min(AMOSTRA, sum(1 for l in linhas if l["gravacao"])))
            if amostra and not any(_gravacao_existe(l["gravacao"]) for l in amostra):
                cur.execute("INSERT INTO conversa_dia (data, status, etapa, total, terminada, observacao)"
                            " VALUES (%s, 2, 'indisponivel', %s, NOW(), 'gravações já apagadas no Native')"
                            " ON DUPLICATE KEY UPDATE status=2, etapa='indisponivel', total=VALUES(total), terminada=NOW(),"
                            " observacao=VALUES(observacao)", (data, len(linhas)))
                log.info("%s: gravações indisponíveis no Native", data)
                return None
        cur.executemany(
            "INSERT IGNORE INTO conversa_ligacao (protocolo, data, data_hora, agente, fila, sentido, desconexao, espera_seg,"
            " atendimento_seg, cidade, religou_min, gravacao) VALUES (%s,%s,%s,%s,%s,%s,%s,%s,%s,%s,%s,%s)",
            [(l["protocolo"], data, l["data_hora"], l["agente"], l["fila"], l["sentido"], l["desconexao"], l["espera"],
              l["seg"], l["cidade"], l["religou"], l["gravacao"]) for l in linhas])
        cur.execute("UPDATE conversa_ligacao SET status=2, etapa='pronto', fim=NOW(), erro='sem gravação'"
                    " WHERE data=%s AND status=0 AND gravacao IS NULL", (data,))
        cur.execute("INSERT INTO conversa_dia (data, total) SELECT %s, COUNT(*) FROM conversa_ligacao WHERE data=%s"
                    " ON DUPLICATE KEY UPDATE total=VALUES(total)", (data, data))
    return len(linhas)


def _dias_adiantados(con, data, maximo=ADIANTAR_DIAS):
    """Os próximos dias depois de `data` que ainda não estão prontos, registrados na hora em que são pedidos (para os
    ajudantes adiantarem); no máximo `maximo` dias à frente. Dias prontos ou sem gravação não contam."""
    if time.time() - _candidatos[0] > 600:
        _candidatos[:] = [time.time(), dias_candidatos()]
    with con.cursor() as cur:
        cur.execute("SELECT data, status, total FROM conversa_dia WHERE data > %s", (data,))
        dias = {str(r["data"]): r for r in cur.fetchall()}
    for d in (d for d in _candidatos[1] if d > data):
        if maximo <= 0:
            return
        r = dias.get(d)
        if r and r["status"] == 2:
            continue
        if not (r and r["total"]):
            with _adiantar_lock:  # dois pedidos ao mesmo tempo não registram o mesmo dia duas vezes
                if _registrar(con, d) is None:
                    continue  # gravações apagadas: o dia fica indisponível
        maximo -= 1
        yield d


def _processar_dia(con, data):
    def sql(q, a=()):
        with con.cursor() as cur:
            cur.execute(q, a)
            return cur.fetchall()

    sql("INSERT INTO conversa_dia (data, status, etapa, iniciada) VALUES (%s, 1, 'transcrevendo', NOW())"
        " ON DUPLICATE KEY UPDATE status=1, etapa='transcrevendo', iniciada=IFNULL(iniciada, NOW()), terminada=NULL", (data,))
    n = _registrar(con, data)
    if n is None:
        return
    with con.cursor() as cur:
        # Retoma: o que o servidor estava baixando/transcrevendo volta para a fila (o lote morreu com o container);
        # o que está com um ajudante com GPU continua dele até a reserva vencer. O que estava na IA volta para "transcrita".
        cur.execute("UPDATE conversa_ligacao SET status=0, etapa=NULL, dono=NULL, prazo=NULL WHERE data=%s AND status=1"
                    " AND etapa IN ('baixando','transcrevendo') AND (dono IS NULL OR dono LIKE 'cpu-%%')", (data,))
        cur.execute("UPDATE conversa_ligacao SET etapa='transcrita' WHERE data=%s AND status=1 AND etapa='ia'", (data,))
    log.info("%s: %d ligações", data, n)

    _estado["etapa"] = "transcrevendo"
    _transcrever_dia(con, data)
    _estado["etapa"] = "ia"
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


def _faltam(con, data):
    """Ligações do dia ainda sem transcrição: na fila ou reservadas."""
    with con.cursor() as cur:
        cur.execute("SELECT COUNT(*) n FROM conversa_ligacao WHERE data=%s AND (status=0 OR (status=1 AND dono IS NOT NULL))",
                    (data,))
        return cur.fetchone()["n"]


def _devolver_vencidas(con, data):
    """Reserva vencida (ajudante sumiu: desligou, perdeu a rede): a ligação volta para a fila sem contar tentativa."""
    with con.cursor() as cur:
        cur.execute("UPDATE conversa_ligacao SET status=0, etapa=NULL, dono=NULL, prazo=NULL"
                    " WHERE data=%s AND status=1 AND dono IS NOT NULL AND prazo < NOW()", (data,))
        if cur.rowcount:
            log.info("%s: %d reserva(s) vencida(s) voltaram para a fila", data, cur.rowcount)


def reservar(con, data, dono, n, minutos):
    """Reserva atômica: um UPDATE só pega linhas em status 0 (o InnoDB trava as linhas; dois pedidos ao mesmo tempo
    nunca levam a mesma ligação). Devolve as ligações reservadas por `dono`."""
    with con.cursor() as cur:
        cur.execute("UPDATE conversa_ligacao SET status=1, etapa='baixando', dono=%s, prazo=NOW() + INTERVAL %s MINUTE,"
                    " inicio=NOW() WHERE data=%s AND status=0 ORDER BY data_hora LIMIT %s", (dono, minutos, data, n))
        if not cur.rowcount:
            return []
        cur.execute("SELECT protocolo, gravacao, atendimento_seg FROM conversa_ligacao WHERE dono=%s AND status=1"
                    " ORDER BY data_hora", (dono,))
        return cur.fetchall()


def salvar_transcricao(cur, protocolo, dono, r, modelo):
    """Grava a transcrição se `dono` ainda tem a reserva (ou se ela venceu e ninguém pegou de novo). Devolve se gravou."""
    segs = [{"ini": s["ini"], "fim": s["fim"], "texto": regras.mascarar(s["texto"])} for s in r["segmentos"]]
    dur = r["duracao"] or 0
    cur.execute("UPDATE conversa_ligacao SET status=1, etapa='transcrita', dono=NULL, prazo=NULL, erro=NULL,"
                " duracao_audio=%s, fala_seg=%s, silencio_pct=%s, inicio_fala=%s, maior_silencio=%s, buracos=%s,"
                " transcricao=%s, palavras=%s, modelo_stt=%s WHERE protocolo=%s AND (dono=%s OR status=0)",
                (dur, r["falaSeg"], round(100 * (1 - r["falaSeg"] / dur), 1) if dur else None, r["inicioFala"],
                 max((b - a for a, b in r["buracos"]), default=0), json.dumps(r["buracos"]),
                 json.dumps(segs, ensure_ascii=False), sum(len(s["texto"].split()) for s in segs), modelo[:40],
                 protocolo, dono))
    return cur.rowcount > 0


def falhou(cur, protocolo, dono, erro, contar=True, definitivo=False):
    """Erro numa ligação reservada por `dono`. definitivo (ex.: gravação apagada no Native): erro e pronto. Senão volta
    para a fila; com `contar`, soma uma tentativa e na TENTATIVAS_MAX desiste dela (erro gravado). `contar=False` é
    para falha de quem processa (rede, GPU), não da ligação."""
    n = TENTATIVAS_MAX if definitivo else int(contar)
    # tentativas é atribuída por último: as expressões antes dela leem o valor antigo (vale em qualquer sql_mode).
    cur.execute("UPDATE conversa_ligacao SET status=IF(tentativas+%s>=%s, 2, 0), etapa=IF(tentativas+%s>=%s, 'pronto', NULL),"
                " erro=IF(tentativas+%s>=%s, %s, NULL), fim=IF(tentativas+%s>=%s, NOW(), NULL), dono=NULL, prazo=NULL,"
                " tentativas=LEAST(tentativas+%s, 100) WHERE protocolo=%s AND dono=%s AND status=1",
                (n, TENTATIVAS_MAX, n, TENTATIVAS_MAX, n, TENTATIVAS_MAX, (erro or "erro")[:500], n, TENTATIVAS_MAX,
                 n, protocolo, dono))
    return cur.rowcount > 0


def _esperar_vez():
    """Solta o Whisper para a análise diária (prioridade) e espera ela terminar (os ajudantes seguem)."""
    while pipeline.DIARIO_QUER.is_set() or pipeline.rodando():
        time.sleep(30)


def _transcrever_dia(con, data):
    """Até não sobrar ligação sem transcrição: o servidor transcreve em lotes, junto com os ajudantes com GPU que
    estiverem ativos. Termina só quando ninguém mais tem reserva do dia."""
    while True:
        _devolver_vencidas(con, data)
        _contar(con, data)
        if not _faltam(con, data):
            return
        _esperar_vez()
        dono = f"cpu-{uuid.uuid4().hex[:8]}"
        lote = reservar(con, data, dono, LOTE_COM_GPU if gpu_ativa() else LOTE, CPU_PRAZO_MIN)
        if lote:
            _lote(con, data, dono, lote)
        else:
            time.sleep(15)  # o que falta está com a GPU: espera a entrega ou a reserva vencer


def _lote(con, data, dono, lote):
    """Transcreve um lote reservado com um worker novo (memória limpa)."""
    def marcar(q, a):
        with con.cursor() as cur:
            cur.execute(q, a)

    def erro(p, msg, contar=True, definitivo=False):
        with con.cursor() as cur:
            falhou(cur, p, dono, msg, contar, definitivo)

    os.makedirs(TMP, exist_ok=True)
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
                                if e.code == 404:
                                    erro(p, "gravação não existe mais no Native", definitivo=True)
                                else:
                                    erro(p, f"download: HTTP {e.code}")
                                continue
                            except Exception as e:  # noqa: BLE001
                                erro(p, f"download: {e}"[:500])
                                continue
                            marcar("UPDATE conversa_ligacao SET etapa='transcrevendo' WHERE protocolo=%s AND dono=%s",
                                   (p, dono))
                            em_voo[whisper.submit(pipeline.transcrever, p, caminho, True)] = ("transcrever", l)
                        else:
                            r = fut.result()
                            if r.get("erro"):
                                erro(p, r["erro"])
                            else:
                                with con.cursor() as cur:
                                    salvar_transcricao(cur, p, dono, r, MODELO)
            except BrokenProcessPool:
                # Worker morreu (memória): conta tentativa para quem estava no Whisper; o resto volta sem contar.
                log.warning("%s: worker caiu no histórico", data)
                for tipo, l in em_voo.values():
                    erro(l["protocolo"], "o worker do Whisper caiu nesta ligação (memória?)", contar=tipo == "transcrever")
                em_voo.clear()
            finally:
                for l in fila:  # não começaram (o worker caiu antes): voltam para a fila sem contar
                    erro(l["protocolo"], None, contar=False)
                for f in os.listdir(TMP):
                    os.remove(os.path.join(TMP, f))


def andamento_mes():
    """Para o painel do ajudante: o mês do dia em processamento, dia a dia (só números, nada de texto)."""
    def txt(v):
        return str(v) if isinstance(v, (dt.date, dt.datetime)) else v
    with db.app() as con, con.cursor() as cur:
        dia = _estado["dia"]
        if not dia:
            cur.execute("SELECT MAX(data) d FROM conversa_dia WHERE status>0")
            dia = txt(cur.fetchone()["d"]) or dt.date.today().isoformat()
        mes = dia[:7]
        ini = dt.date.fromisoformat(mes + "-01")
        fim = (ini + dt.timedelta(days=32)).replace(day=1)
        cache = _native_mes.get(mes)
        if not cache or time.time() - cache[0] > 1800:
            with db.origem() as org, org.cursor() as c2:
                # Um protocolo por ligação (o db_native repete alguns), como no registro do dia (INSERT IGNORE).
                c2.execute("SELECT d, COUNT(*) n, SUM(s) s FROM (SELECT DATE(MIN(data_hora)) d,"
                           " MAX(TIME_TO_SEC(atendimento)) s FROM db_native WHERE perdida IS NULL AND data_hora >= %s"
                           " AND data_hora < %s AND data_hora < CURDATE()" + (" AND data_hora >= %s" if DESDE else "")
                           + " GROUP BY protocolo) x GROUP BY d", (ini, fim, DESDE) if DESDE else (ini, fim))
                cache = _native_mes[mes] = (time.time(), {str(r["d"]): {"ligacoes": r["n"], "seg": int(r["s"] or 0)}
                                                          for r in c2.fetchall()})
        cur.execute("SELECT * FROM conversa_dia WHERE data >= %s AND data < %s", (ini, fim))
        dias = {str(r["data"]): r for r in cur.fetchall()}
        cur.execute("SELECT data, COUNT(*) total, SUM(transcricao IS NOT NULL) transcritas, SUM(ia_em IS NOT NULL) analisadas,"
                    " SUM(erro IS NOT NULL OR ia_erro IS NOT NULL) erros, SUM(erro IS NOT NULL) erros_transcricao,"
                    " SUM(ia_erro IS NOT NULL) erros_ia, SUM(atendimento_seg) seg,"
                    " SUM(IF(transcricao IS NOT NULL, atendimento_seg, 0)) seg_transcrito,"
                    " SUM(IF(erro IS NOT NULL, atendimento_seg, 0)) seg_erro,"
                    " SUM(status=1 AND dono LIKE 'gpu-%%') com_ajudante, SUM(status=1 AND dono LIKE 'cpu-%%') com_servidor"
                    " FROM conversa_ligacao WHERE data >= %s AND data < %s GROUP BY data", (ini, fim))
        lig = {str(r["data"]): r for r in cur.fetchall()}
        cur.execute("SELECT modelo_stt, COUNT(*) n, SUM(atendimento_seg) seg FROM conversa_ligacao"
                    " WHERE data >= %s AND data < %s AND transcricao IS NOT NULL GROUP BY modelo_stt", (ini, fim))
        modelos = {r["modelo_stt"] or "?": {"ligacoes": r["n"], "seg": int(r["seg"] or 0)} for r in cur.fetchall()}
        cur.execute("SELECT NOW() agora")
        agora = txt(cur.fetchone()["agora"])
    out = []
    for d in sorted(set(cache[1]) | set(dias)):
        r, l, n = dias.get(d) or {}, lig.get(d) or {}, cache[1].get(d) or {}
        out.append({"data": d, "status": r.get("status", 0), "etapa": r.get("etapa"),
                    "iniciada": txt(r.get("iniciada")), "terminada": txt(r.get("terminada")),
                    "ligacoes": l.get("total") or n.get("ligacoes", 0), "seg": int(l.get("seg") or n.get("seg", 0)),
                    "registrado": bool(l), "native": n,
                    **{k: int(l.get(k) or 0) for k in ("transcritas", "analisadas", "erros", "erros_transcricao",
                                                         "erros_ia", "seg_transcrito", "seg_erro", "com_ajudante",
                                                         "com_servidor")}})
    return {"agora": agora, "mes": mes, "estado": estado(), "dias": out, "modelos": modelos}


def resumo_dia(data, fila=None, agente=None, categoria=None, ligacoes=False):
    """Resultado de um dia do histórico, agregado (para o resumo pedido pelo ajudante): volumes, categorias, resultado,
    satisfação, roteiro, regra do mudo (casos), por atendente, fila e hora. Texto só o já mascarado (justificativas).
    fila/agente/categoria (LIKE, juntos com OU) recortam o dia — ex.: o suporte; `ligacoes` devolve também cada
    ligação do recorte com o que a IA escreveu (sem a transcrição)."""
    partes, fp = [], []
    for campo, valor in (("fila", fila), ("agente", agente), ("categoria", categoria)):
        if valor:
            partes.append(f"{campo} LIKE %s")
            fp.append(valor)
    filtro = f" AND ({' OR '.join(partes)})" if partes else ""

    def q(sql, a=()):
        if "FROM conversa_ligacao WHERE data=%s" in sql:
            sql = sql.replace("FROM conversa_ligacao WHERE data=%s", "FROM conversa_ligacao WHERE data=%s" + filtro, 1)
            cur.execute(sql, (data, *fp, *a))
        else:
            cur.execute(sql, (data, *a))
        return [{k: float(v) if isinstance(v, decimal.Decimal) else str(v) if isinstance(v, (dt.date, dt.timedelta))
                 else v for k, v in r.items()} for r in cur.fetchall()]

    cliente = " AND IFNULL(ligacao_interna, 0)=0 AND ia_em IS NOT NULL"
    with db.app() as con, con.cursor() as cur:
        dia = q("SELECT * FROM conversa_dia WHERE data=%s")
        tot = q("SELECT COUNT(*) ligacoes, SUM(transcricao IS NOT NULL) transcritas, SUM(ia_em IS NOT NULL) analisadas,"
                " SUM(erro IS NOT NULL) erros_transcricao, SUM(ia_erro IS NOT NULL) erros_ia,"
                " SUM(IFNULL(ligacao_interna, 0)) internas, SUM(atendimento_seg) seg, AVG(atendimento_seg) tma_seg,"
                " AVG(espera_seg) espera_media_seg, AVG(silencio_pct) silencio_medio_pct, AVG(satisfacao_estimada) satisfacao,"
                " SUM(risco_cancelamento) risco_cancelamento, SUM(religou_min IS NOT NULL) religou_2h,"
                " SUM(desconexao='Origem') cliente_desligou, SUM(desconexao='Destino') atendente_desligou,"
                " SUM(desconexao='Transferida') transferidas FROM conversa_ligacao WHERE data=%s")[0]
        por = {}
        for campo in ("categoria", "resolvido", "sentimento_inicio", "sentimento_fim", "satisfacao_estimada", "regra_mudo",
                      "modelo_stt"):
            por[campo] = q(f"SELECT {campo} valor, COUNT(*) n FROM conversa_ligacao WHERE data=%s AND ia_em IS NOT NULL"
                           f" GROUP BY {campo} ORDER BY n DESC")
        por["categoria_detalhe"] = q("SELECT categoria, COUNT(*) n, AVG(satisfacao_estimada) satisfacao,"
                                     " SUM(resolvido='sim') resolvidas, AVG(atendimento_seg) tma_seg,"
                                     " SUM(risco_cancelamento) risco FROM conversa_ligacao WHERE data=%s" + cliente
                                     + " GROUP BY categoria ORDER BY n DESC")
        por["sentimento"] = q("SELECT sentimento_inicio inicio, sentimento_fim fim, COUNT(*) n FROM conversa_ligacao"
                              " WHERE data=%s" + cliente + " GROUP BY inicio, fim ORDER BY n DESC")
        por["motivos"] = q("SELECT motivo, COUNT(*) n FROM conversa_ligacao WHERE data=%s" + cliente
                           + " GROUP BY motivo ORDER BY n DESC LIMIT 20")
        por["agente"] = q("SELECT agente, COUNT(*) n, AVG(atendimento_seg) tma_seg, AVG(satisfacao_estimada) satisfacao,"
                          " SUM(resolvido='sim') resolvidas, SUM(resolvido='nao') nao_resolvidas,"
                          " SUM(sentimento_fim='negativo') terminou_negativo, SUM(risco_cancelamento) risco,"
                          " SUM(regra_mudo='sim') mudo_sim, SUM(regra_mudo='inconclusivo') mudo_inconclusivo,"
                          " SUM(religou_min IS NOT NULL) religou_2h, AVG(silencio_pct) silencio_pct"
                          " FROM conversa_ligacao WHERE data=%s" + cliente + " GROUP BY agente ORDER BY n DESC")
        por["fila"] = q("SELECT fila, COUNT(*) n, AVG(atendimento_seg) tma_seg, AVG(satisfacao_estimada) satisfacao,"
                        " SUM(resolvido='sim') resolvidas FROM conversa_ligacao WHERE data=%s" + cliente
                        + " GROUP BY fila ORDER BY n DESC")
        por["hora"] = q("SELECT HOUR(data_hora) hora, COUNT(*) n, AVG(satisfacao_estimada) satisfacao"
                        " FROM conversa_ligacao WHERE data=%s" + cliente + " GROUP BY hora ORDER BY hora")
        mudo = q("SELECT protocolo, TIME(data_hora) hora, agente, fila, atendimento_seg seg, desconexao, religou_min,"
                 " peso, regra_mudo, regra_mudo_justificativa justificativa, ia_confianca FROM conversa_ligacao"
                 " WHERE data=%s AND regra_mudo IN ('sim','inconclusivo') ORDER BY regra_mudo DESC, peso DESC")
        roteiro = {}
        for r in q("SELECT roteiro FROM conversa_ligacao WHERE data=%s" + cliente + " AND roteiro IS NOT NULL"):
            for k, v in (json.loads(r["roteiro"]) or {}).items():
                c = roteiro.setdefault(k, {"sim": 0, "total": 0})
                c["total"] += 1
                c["sim"] += bool(v)
        lista = q("SELECT protocolo, TIME(data_hora) hora, agente, fila, sentido, espera_seg, atendimento_seg seg,"
                  " desconexao, religou_min, silencio_pct, maior_silencio, ligacao_interna, categoria, motivo, resolvido,"
                  " sentimento_inicio, sentimento_fim, satisfacao_estimada, risco_cancelamento, regra_mudo, resumo,"
                  " pontos_atencao, palavras_chave, roteiro, cadeia, ia_confianca FROM conversa_ligacao"
                  " WHERE data=%s AND ia_em IS NOT NULL ORDER BY data_hora") if ligacoes and filtro else None
    return {"data": data, "filtro": {"fila": fila, "agente": agente, "categoria": categoria}, "dia": dia[0] if dia else None,
            "totais": tot, "por": por, "roteiro": roteiro, "mudo": mudo, "ligacoes": lista}


def gpu_pedido(p):
    """Pedidos do ajudante com GPU (via POST /historico/gpu). Cada pedido conta como sinal de vida dele (menos o
    andamento, que é só leitura para o painel)."""
    if p["acao"] == "andamento":
        return andamento_mes()
    if p["acao"] == "resumo":
        return resumo_dia(p["data"], p.get("fila"), p.get("agente"), p.get("categoria"), bool(p.get("ligacoes")))
    _gpu[p["worker"]] = time.time()
    with db.app() as con, con.cursor() as cur:
        if p["acao"] == "vivo":
            # A cada minuto, mesmo no meio de uma ligação longa: renova só as reservas que ele diz ter em mãos (as de
            # uma execução anterior que caiu, mesmo com o mesmo nome, vencem e voltam para a fila).
            prefixo = f"gpu-{p['worker'][:24]}-"
            minhas = [r for r in p.get("reservas") or [] if r.startswith(prefixo)]
            if not minhas:
                return {"renovadas": 0}
            cur.execute("UPDATE conversa_ligacao SET prazo=NOW() + INTERVAL %s MINUTE WHERE status=1 AND dono IN ("
                        + ",".join(["%s"] * len(minhas)) + ")", (GPU_PRAZO_MIN, *minhas))
            return {"renovadas": cur.rowcount}
        if p["acao"] == "pegar":
            # O ajudante não para: com o servidor parado (ex.: logo depois de um redeploy, até o n8n chamar) ele
            # adianta os primeiros dias que faltam; o servidor os encontra meio prontos quando voltar.
            rodando = _estado["rodando"] and _estado["dia"]
            data = _estado["dia"] if rodando else "0000-00-00"
            dono = f"gpu-{p['worker'][:24]}-{uuid.uuid4().hex[:8]}"
            n = max(1, min(p.get("n") or 4, GPU_LOTE_MAX))
            itens = []
            if rodando and _estado["etapa"] == "transcrevendo":
                _devolver_vencidas(con, data)
                itens = reservar(con, data, dono, n, GPU_PRAZO_MIN)
            if not itens:  # o dia não tem mais o que transcrever (IA ou últimos lotes do servidor): adianta os próximos
                for proximo in _dias_adiantados(con, data):
                    _devolver_vencidas(con, proximo)
                    itens = reservar(con, proximo, dono, n, GPU_PRAZO_MIN)
                    if itens:
                        data = proximo
                        break
            return {"data": data, "reserva": dono, "prazoMin": GPU_PRAZO_MIN, "esperar": 0 if itens else 30,
                    "itens": [{"protocolo": i["protocolo"], "gravacao": i["gravacao"], "seg": i["atendimento_seg"]}
                              for i in itens]}
        if p["acao"] == "entregar":
            return {"aceita": salvar_transcricao(cur, p["protocolo"], p["reserva"], p["resultado"], p["modelo"] or "gpu")}
        if p["acao"] == "falhou":
            return {"aceita": falhou(cur, p["protocolo"], p["reserva"], p.get("erro"), p.get("contar", True),
                                     p.get("definitivo", False))}
    raise ValueError(f"ação desconhecida: {p['acao']}")


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
