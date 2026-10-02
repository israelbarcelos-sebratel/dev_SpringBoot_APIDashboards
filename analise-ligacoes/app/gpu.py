"""Ajudante com GPU do histórico: roda numa máquina da Sebratel com placa NVIDIA (docker compose em
analise-ligacoes/gpu/) e transcreve junto com o servidor o dia que o histórico está processando.

Usa a máquina inteira: GPU_PROCESSOS processos na placa (Whisper medium em lote; dois para a placa não ficar parada
enquanto um decodifica o áudio) e CPU_PROCESSOS processos na CPU (Whisper small), que só pegam ligações curtas — as
longas esperam a GPU. Pede ligações ao servidor (POST /historico/gpu pelo webhook do n8n), baixa cada gravação para uma
pasta temporária, transcreve e devolve só números e o texto já mascarado; o áudio é apagado logo depois de transcrito.
Quem distribui é o servidor, por reserva atômica no banco: uma ligação nunca fica com dois, e a entrega só vale se a
reserva ainda for deste ajudante (sem race condition, mesmo com o servidor ou outro ajudante no mesmo dia).

Erros — o processo sempre recomeça sozinho:
- erro de uma ligação (áudio ruim, worker que caiu nela): ela volta para a fila e conta tentativa (o servidor desiste
  dela na 3ª); gravação apagada no Native: erro definitivo;
- erro do ajudante (rede, GPU): as ligações voltam sem contar tentativa, ele espera e recomeça; 3 falhas de GPU seguidas:
  pausa de 10 min (o servidor segue sozinho) e tenta de novo;
- ajudante desligado ou travado: o sinal de vida (a cada minuto, renova as reservas que ele tem em mãos) para, a
  reserva vence em 20 min e a ligação volta para a fila (a de uma execução que caiu vence mesmo com ele de volta); transcrição presa por TRAVOU_MIN: os processos são mortos e ele recomeça."""
import json
import logging
import multiprocessing as mp
import os
import signal
import socket
import sys
import threading
import time
import urllib.error
import urllib.request
from collections import deque
from concurrent.futures import FIRST_COMPLETED, ProcessPoolExecutor, ThreadPoolExecutor, wait
from concurrent.futures.process import BrokenProcessPool

from . import pipeline

logging.basicConfig(level=logging.INFO, format="%(asctime)s %(levelname)s %(message)s")
log = logging.getLogger("gpu")

URL = os.environ.get("SERVIDOR_URL", "https://n8n-staging.sebratel.net.br/webhook/historico-gpu")
TOKEN = os.environ.get("GPU_TOKEN", "")
NOME = os.environ.get("NOME_AJUDANTE") or socket.gethostname()
MODELO = os.environ.get("MODELO_GPU", "medium")
GPU_PROCESSOS = int(os.environ.get("GPU_PROCESSOS", "2"))
MODELO_CPU = os.environ.get("MODELO_CPU", "small")
CPU_THREADS = int(os.environ.get("CPU_THREADS", "4"))
# A CPU inteira: os processos da CPU rodam com prioridade mínima (ver _iniciar), então não tiram a CPU de quem alimenta
# a GPU. Medido num Core 7 240H (16 threads) + RTX 3050 6 GB, 5 min cada:
#   2 GPU + 4 CPU x4 threads: GPU ~90%, CPU ~99%, ~63x o tempo real  <- padrão
#   2 GPU + 2 CPU x4: GPU ~89%, CPU ~61%, ~58-62x · 2 GPU + 2 CPU x8: ~47x (threads demais)
#   3 GPU: a placa marca 93-99%, mas a memória dela (6 GB) estoura para a RAM e cai para 21-29x — não use mais de 2.
CPU_PROCESSOS = int(os.environ.get("CPU_PROCESSOS") or max(0, (os.cpu_count() or 4) // CPU_THREADS))
CPU_ATE_SEG = int(os.environ.get("CPU_ATE_SEG", "600"))  # ligação mais longa que isso só vai para a GPU
DOWNLOADS = int(os.environ.get("DOWNLOADS", "4"))
EM_VOO = int(os.environ.get("EM_VOO", str(2 * (GPU_PROCESSOS + CPU_PROCESSOS) + 2)))  # baixando + esperando + rodando
RECICLAR = int(os.environ.get("RECICLAR_GPU", "300"))
TMP = os.path.join(pipeline.TMP, "gpu")
FALHA_GPU = ("cuda", "cublas", "cudnn", "out of memory", "device")
PAUSA_GPU = 600
TRAVOU_MIN = int(os.environ.get("TRAVOU_MIN", "30"))  # uma transcrição passando disso = travou
# O laço principal marca a cada volta (parado = o sinal de vida para) e diz quais reservas tem em mãos.
_progresso = {"t": time.time(), "reservas": []}


class _FalhaDoAjudante(Exception):
    """Rede ou GPU: não é culpa da ligação."""


def _chamar(acao, tentativas=3, **campos):
    corpo = json.dumps({"acao": acao, "worker": NOME, **campos}, ensure_ascii=False).encode()
    for n in range(tentativas):
        req = urllib.request.Request(URL, data=corpo, method="POST",
                                     headers={"Content-Type": "application/json", "X-Gpu-Token": TOKEN})
        try:
            with urllib.request.urlopen(req, timeout=120) as r:
                resp = json.load(r)
            if "detail" in resp:  # erro do servidor repassado pelo n8n (token, validação)
                raise _FalhaDoAjudante(f"servidor: {resp['detail']}")
            return resp
        except (urllib.error.URLError, TimeoutError, ConnectionError, ValueError) as e:
            if n == tentativas - 1:
                raise _FalhaDoAjudante(f"{acao}: {type(e).__name__}: {e}") from None
            time.sleep(5 * (n + 1))


def _sinal_de_vida():
    """A cada minuto, enquanto o laço principal anda: o servidor sabe que o ajudante está vivo (e pega lotes menores) e
    as reservas deste ajudante não vencem no meio de uma ligação longa."""
    while True:
        time.sleep(60)
        if time.time() - _progresso["t"] < 300:
            try:
                _chamar("vivo", tentativas=1, reservas=_progresso["reservas"])
            except Exception as e:  # noqa: BLE001
                log.warning("sinal de vida: %s", e)


def _soltar(l, contar, erro, definitivo=False):
    try:
        _chamar("falhou", reserva=l["reserva"], protocolo=l["protocolo"], erro=erro[:2000], contar=contar,
                definitivo=definitivo)
    except _FalhaDoAjudante as e:
        log.warning("%s: não deu para devolver (%s); volta sozinha quando a reserva vencer", l["protocolo"], e)


def _limpar():
    os.makedirs(TMP, exist_ok=True)
    for f in os.listdir(TMP):
        os.remove(os.path.join(TMP, f))


def _iniciar(modelo, threads, dispositivo):
    """Processos da CPU com prioridade mínima: os que alimentam a GPU (decodificar o áudio, VAD) pegam a CPU primeiro
    e a placa nunca espera; os da CPU ficam só com o que sobra — GPU e CPU cheias ao mesmo tempo."""
    if dispositivo == "cpu":
        os.nice(19)
    pipeline._iniciar_worker(modelo, threads, dispositivo)


def _pool(n, modelo, threads, dispositivo):
    return ProcessPoolExecutor(n, mp_context=mp.get_context("spawn"), initializer=_iniciar,
                               initargs=(modelo, threads, dispositivo), max_tasks_per_child=RECICLAR)


def _rodar():
    """Um ciclo: pools novos, pede/baixa/transcreve/entrega até dar erro do ajudante (aí devolve tudo e sai)."""
    _limpar()
    em_voo = {}       # future -> (tipo baixar|gpu|cpu, ligação)
    prontos = deque() # baixadas, esperando um processo livre: (ligação, caminho)
    comecou = {}      # future de transcrição -> quando começou
    ocupados = {"gpu": 0, "cpu": 0}
    vagas = {"gpu": GPU_PROCESSOS, "cpu": CPU_PROCESSOS}
    falhas_gpu = 0
    feitas, inicio = {"gpu": 0, "cpu": 0}, time.time()
    pools = {"gpu": _pool(GPU_PROCESSOS, MODELO, 2, "cuda")}
    if CPU_PROCESSOS:
        pools["cpu"] = _pool(CPU_PROCESSOS, MODELO_CPU, CPU_THREADS, "cpu")
    modelo = {"gpu": f"{MODELO} gpu", "cpu": f"{MODELO_CPU} cpu ajudante"}
    # Sem `with`: ao parar, as reservas são devolvidas na hora, sem esperar download em andamento.
    downloads = ThreadPoolExecutor(DOWNLOADS)
    try:
        espera_ate = 0.0
        while True:
            _progresso["t"] = time.time()
            _progresso["reservas"] = sorted({l["reserva"] for _, l in em_voo.values()} | {l["reserva"] for l, _ in prontos})
            presa = next((f for f, t in comecou.items() if time.time() - t > TRAVOU_MIN * 60), None)
            if presa:
                l = em_voo.pop(presa)[1]
                comecou.pop(presa)
                _soltar(l, True, f"transcrição presa por mais de {TRAVOU_MIN} min")
                for pool in pools.values():
                    for proc in list(getattr(pool, "_processes", {}).values()):
                        proc.kill()
                raise _FalhaDoAjudante("transcrição presa; processos reiniciados")

            # Distribui as baixadas: GPU primeiro; a CPU só pega as curtas.
            for _ in range(len(prontos)):
                l, caminho = prontos.popleft()
                curta = (l.get("seg") or 0) <= CPU_ATE_SEG
                destino = "gpu" if ocupados["gpu"] < vagas["gpu"] else (
                    "cpu" if curta and ocupados["cpu"] < vagas["cpu"] else None)
                if not destino:
                    prontos.append((l, caminho))
                    continue
                f = pools[destino].submit(pipeline.transcrever, l["protocolo"], caminho, True)
                em_voo[f] = (destino, l)
                comecou[f] = time.time()
                ocupados[destino] += 1

            # Pede mais quando metade das vagas está livre (menos chamadas ao servidor).
            if len(em_voo) + len(prontos) <= EM_VOO // 2 and time.time() >= espera_ate:
                r = _chamar("pegar", n=EM_VOO - len(em_voo) - len(prontos))
                for i in r.get("itens", []):
                    l = dict(i, reserva=r["reserva"])
                    em_voo[downloads.submit(pipeline._baixar, l["protocolo"], l["gravacao"], TMP)] = ("baixar", l)
                if not r.get("itens"):
                    espera_ate = time.time() + r.get("esperar", 30)
                    if not em_voo and not prontos:
                        log.info("sem ligação agora (%s); de novo em %ss", r.get("motivo") or r.get("data"),
                                 r.get("esperar", 30))
            if not em_voo:
                time.sleep(1 if prontos else max(1.0, espera_ate - time.time()))
                continue
            terminados, _ = wait(list(em_voo), timeout=5, return_when=FIRST_COMPLETED)
            for fut in terminados:
                tipo, l = em_voo.pop(fut)
                p = l["protocolo"]
                if tipo == "baixar":
                    try:
                        prontos.append((l, fut.result()))
                    except urllib.error.HTTPError as e:
                        if e.code == 404:
                            _soltar(l, True, "gravação não existe mais no Native", definitivo=True)
                        else:
                            _soltar(l, True, f"download: HTTP {e.code}")
                    except Exception as e:  # noqa: BLE001 - rede desta máquina, não da ligação
                        _soltar(l, False, f"download: {e}")
                        espera_ate = time.time() + 60
                    continue
                comecou.pop(fut, None)
                ocupados[tipo] -= 1
                r = fut.result()  # BrokenProcessPool sobe daqui
                if r.get("erro"):
                    if tipo == "gpu" and any(x in r["erro"].lower() for x in FALHA_GPU):
                        falhas_gpu += 1
                        _soltar(l, False, r["erro"])
                        if falhas_gpu >= 3:
                            raise _FalhaDoAjudante(f"GPU falhou {falhas_gpu} vezes seguidas: {r['erro']}")
                    else:
                        _soltar(l, True, r["erro"])
                    continue
                if tipo == "gpu":
                    falhas_gpu = 0
                ok = _chamar("entregar", reserva=l["reserva"], protocolo=p, modelo=modelo[tipo],
                             resultado={k: r[k] for k in ("duracao", "falaSeg", "inicioFala", "buracos", "segmentos")})
                feitas[tipo] += 1
                if not ok.get("aceita"):
                    log.warning("%s: entrega recusada (a reserva venceu e outro pegou)", p)
                if sum(feitas.values()) % 50 == 0:
                    log.info("%d ligações em %.0f min (GPU %d, CPU %d)", sum(feitas.values()),
                             (time.time() - inicio) / 60, feitas["gpu"], feitas["cpu"])
    except BaseException as e:
        # Devolve tudo o que estava com este ajudante; só conta tentativa para quem estava num processo que caiu.
        caiu = isinstance(e, BrokenProcessPool)
        for tipo, l in em_voo.values():
            _soltar(l, caiu and tipo != "baixar", f"ajudante: {type(e).__name__}: {e}")
        for l, _ in prontos:
            _soltar(l, False, f"ajudante: {type(e).__name__}: {e}")
        log.info("%d ligação(ões) devolvida(s) ao servidor", len(em_voo) + len(prontos))
        for fut in em_voo:
            fut.cancel()
        raise
    finally:
        _progresso["reservas"] = []
        downloads.shutdown(wait=False, cancel_futures=True)
        for pool in pools.values():
            pool.shutdown(wait=False, cancel_futures=True)
        _limpar()


def main():
    if not TOKEN:
        sys.exit("defina GPU_TOKEN (o mesmo da stack no Portainer)")
    signal.signal(signal.SIGTERM, lambda *_: sys.exit(0))  # docker stop: devolve as reservas antes de sair
    socket.setdefaulttimeout(180)  # download da gravação sem resposta não prende a ligação para sempre
    threading.Thread(target=_sinal_de_vida, daemon=True, name="sinal-de-vida").start()
    log.info("ajudante %s: GPU %d x %s, CPU %d x %s (%d threads), servidor %s", NOME, GPU_PROCESSOS, MODELO,
             CPU_PROCESSOS, MODELO_CPU, CPU_THREADS, URL)
    while True:
        try:
            _rodar()
        except SystemExit:
            # docker stop: as reservas já foram devolvidas; sai na hora, sem esperar download ou processo em andamento.
            logging.shutdown()
            os._exit(0)
        except BrokenProcessPool:
            log.warning("um processo de transcrição caiu; recomeçando")
            time.sleep(5)
        except _FalhaDoAjudante as e:
            pausa = PAUSA_GPU if "GPU falhou" in str(e) else 60
            log.warning("%s; recomeçando em %d s", e, pausa)
            if pausa == PAUSA_GPU:
                _progresso["t"] = 0  # sem sinal de vida na pausa: as reservas que sobraram vencem e voltam para a fila
            time.sleep(pausa)
        except Exception:  # noqa: BLE001
            log.exception("erro inesperado; recomeçando em 60 s")
            time.sleep(60)


if __name__ == "__main__":
    main()
