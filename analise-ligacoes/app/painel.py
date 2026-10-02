"""Painel do histórico no terminal: o mês em processamento, dia a dia, atualizado a cada PAINEL_SEG (2 min).

Roda dentro do container do ajudante (usa o mesmo SERVIDOR_URL e GPU_TOKEN; só lê, não reserva nada):
    docker exec -it historico-gpu python -m app.painel
Mostra também o uso desta máquina (GPU, CPU, RAM) e o ritmo medido entre as atualizações, com a previsão de quando a
transcrição do mês termina (a IA de cada dia roda logo depois da transcrição dele, no servidor)."""
import datetime as dt
import json
import os
import subprocess
import time
import urllib.request

URL = os.environ.get("SERVIDOR_URL", "https://n8n-staging.sebratel.net.br/webhook/historico-gpu")
TOKEN = os.environ.get("GPU_TOKEN", "")
INTERVALO = int(os.environ.get("PAINEL_SEG", "120"))
JANELA = 30 * 60      # ritmo: média dos últimos 30 min
SEMANA = ("seg", "ter", "qua", "qui", "sex", "sáb", "dom")
MESES = ("janeiro", "fevereiro", "março", "abril", "maio", "junho", "julho", "agosto", "setembro", "outubro",
         "novembro", "dezembro")
VERDE, AMARELO, AZUL, CINZA, VERMELHO, NEGRITO, FIM = ("\033[32m", "\033[33m", "\033[36m", "\033[90m", "\033[31m",
                                                       "\033[1m", "\033[0m")


def _andamento():
    req = urllib.request.Request(URL, data=json.dumps({"acao": "andamento", "worker": "painel"}).encode(), method="POST",
                                 headers={"Content-Type": "application/json", "X-Gpu-Token": TOKEN})
    with urllib.request.urlopen(req, timeout=90) as r:
        resp = json.load(r)
    if "detail" in resp:
        raise RuntimeError(f"servidor: {resp['detail']}")
    return resp


def _cpu():
    with open("/proc/stat") as f:
        v = [int(x) for x in f.readline().split()[1:]]
    return v[3] + v[4], sum(v)  # ocioso (idle + iowait), total


def _maquina(cpu_antes):
    partes = []
    try:
        g = subprocess.run(["nvidia-smi", "--query-gpu=utilization.gpu,memory.used,memory.total",
                            "--format=csv,noheader,nounits"], capture_output=True, text=True, timeout=10).stdout
        uso, usada, total = (float(x) for x in g.strip().split(","))
        partes.append(f"GPU {uso:.0f}% ({usada / 1024:.1f}/{total / 1024:.1f} GB)")
    except Exception:  # noqa: BLE001
        partes.append("GPU ?")
    ocioso, total = _cpu()
    if cpu_antes and total > cpu_antes[1]:
        partes.append(f"CPU {100 * (1 - (ocioso - cpu_antes[0]) / (total - cpu_antes[1])):.0f}%")
    with open("/proc/meminfo") as f:
        m = {l.split(":")[0]: int(l.split()[1]) for l in f}
    partes.append(f"RAM {(m['MemTotal'] - m['MemAvailable']) / 1048576:.1f}/{m['MemTotal'] / 1048576:.1f} GB")
    return " · ".join(partes).replace(".", ","), (ocioso, total)


def _pad(texto, n):
    """Completa com espaços contando só o que aparece (sem os códigos de cor)."""
    visivel = texto
    for cor in (VERDE, AMARELO, AZUL, CINZA, VERMELHO, NEGRITO, FIM):
        visivel = visivel.replace(cor, "")
    return texto + " " * max(0, n - len(visivel))


def _h(seg):
    return f"{seg / 3600:.1f}".replace(".", ",")


def _barra(frac, n=12):
    cheio = round(max(0.0, min(1.0, frac)) * n)
    return "█" * cheio + "░" * (n - cheio)


def _situacao(d):
    if d["status"] == 2:
        return (CINZA + "sem gravação" + FIM) if d["etapa"] == "indisponivel" else (VERDE + "pronto" + FIM)
    if d["status"] == 1:
        return (AMARELO + "IA" + FIM) if d["etapa"] == "ia" else (AZUL + "transcrevendo" + FIM)
    return CINZA + "na fila" + FIM


def _tela(r, maquina, historico):
    agora = dt.datetime.fromisoformat(r["agora"])
    ano, mes = (int(x) for x in r["mes"].split("-"))
    est = r["estado"]
    linhas = [f"{NEGRITO}Histórico de conversas — {MESES[mes - 1]}/{ano}{FIM}"
              f"   atualizado {agora:%d/%m %H:%M:%S} (a cada {INTERVALO // 60} min, Ctrl+C sai)", ""]
    ajud = est.get("gpu", {}).get("ajudantes", {})
    vivos = [f"{k} há {v}s" for k, v in ajud.items() if v < 180]
    if est.get("rodando") and est.get("dia"):
        servidor = f"dia {dt.date.fromisoformat(est['dia']):%d/%m} · {est.get('etapa') or 'começando'}"
    else:
        servidor = "começando" if est.get("rodando") else f"{AMARELO}parado — o n8n retoma em até 15 min{FIM}"
    linhas.append(f"Servidor: {servidor}"
                  f" · ajudante: {', '.join(vivos) if vivos else VERMELHO + 'nenhum ativo' + FIM}"
                  + (f" · {VERMELHO}erro: {est['erro']}{FIM}" if est.get("erro") else ""))
    linhas.append(f"Esta máquina: {maquina}")
    linhas.append("")
    linhas.append(f"{'Dia':<10} {'Transcrição':<18} {'Situação':<13} {'Transcritas':>13} {'IA':>11} {'Erros':>6}"
                  f" {'Áudio (h)':>13}  Com quem agora")
    tot = {"seg": 0, "seg_t": 0, "lig": 0, "trans": 0, "ia": 0, "prontos": 0, "dias": 0}
    # Dias sem gravação no Native (já apagadas) foram pulados: uma linha só para eles.
    sem = [d["data"] for d in r["dias"] if d["etapa"] == "indisponivel"]
    if sem:
        a, b = (dt.date.fromisoformat(x) for x in (sem[0], sem[-1]))
        linhas.append(f"{CINZA}{a:%d/%m}–{b:%d/%m}  {len(sem)} dia(s) sem gravação no Native (pulados){FIM}")
    for d in r["dias"]:
        if d["etapa"] == "indisponivel":
            continue
        data = dt.date.fromisoformat(d["data"])
        frac = 1.0 if d["status"] == 2 else (d["seg_transcrito"] / d["seg"] if d["seg"] else 0.0)
        sit_pad = _pad(_situacao(d), 13)
        quem = []
        if d["com_ajudante"]:
            quem.append(f"ajudante {d['com_ajudante']}")
        if d["com_servidor"]:
            quem.append(f"servidor {d['com_servidor']}")
        linhas.append(f"{data:%d/%m} {SEMANA[data.weekday()]}  {_barra(frac)} {100 * frac:>4.0f}% {sit_pad}"
                      f" {d['transcritas']:>6}/{d['ligacoes']:<6} {d['analisadas']:>5}/{d['ligacoes']:<5}"
                      f" {d['erros']:>6} {_h(d['seg_transcrito'] if d['status'] != 2 else d['seg']):>6}/{_h(d['seg']):<6}"
                      f"  {', '.join(quem)}")
        tot["dias"] += 1
        tot["seg"] += d["seg"]
        tot["seg_t"] += d["seg"] if d["status"] == 2 else d["seg_transcrito"]
        tot["lig"] += d["ligacoes"]
        tot["trans"] += d["transcritas"]
        tot["ia"] += d["analisadas"]
        tot["prontos"] += d["status"] == 2
    linhas.append("")
    frac = tot["seg_t"] / tot["seg"] if tot["seg"] else 0
    linhas.append(f"{NEGRITO}Mês:{FIM} {_barra(frac, 30)} {100 * frac:.0f}% do áudio transcrito"
                  f" ({_h(tot['seg_t'])} de {_h(tot['seg'])} h) · dias prontos {tot['prontos']}/{tot['dias']}"
                  f" · IA {tot['ia']}/{tot['lig']} ligações")

    # Ritmo: horas de áudio transcritas por hora, medido entre as atualizações deste painel.
    historico.append((time.time(), r["mes"], tot["seg_t"]))
    while historico and (historico[0][1] != r["mes"] or time.time() - historico[0][0] > JANELA):
        historico.pop(0)
    if len(historico) >= 2 and historico[-1][0] > historico[0][0]:
        ritmo = (historico[-1][2] - historico[0][2]) / (historico[-1][0] - historico[0][0])  # s de áudio por s
        falta = tot["seg"] - tot["seg_t"]
        if ritmo > 0:
            fim = agora + dt.timedelta(seconds=falta / ritmo)
            linhas.append(f"Ritmo: {ritmo:.0f}x o tempo real ({_h(ritmo * 3600)} h de áudio por hora)"
                          f" · transcrição do mês termina em ~{_h(falta / ritmo)} h ({fim:%d/%m %H:%M})")
        else:
            linhas.append("Ritmo: nada transcrito desde a última atualização (IA do dia ou pausa)")
    else:
        linhas.append(f"Ritmo: medindo… (aparece na próxima atualização, em {INTERVALO // 60} min)")

    quem = sorted(r["modelos"].items(), key=lambda kv: -kv[1]["seg"])
    nomes = {"medium gpu": "GPU (medium)", "small cpu ajudante": "CPU do ajudante (small)", "small": "servidor (small)"}
    linhas.append("Quem transcreveu no mês: " + " · ".join(
        f"{nomes.get(k, k)} {v['ligacoes']} ({_h(v['seg'])} h)" for k, v in quem) if quem else
        "Quem transcreveu no mês: ainda ninguém")
    return "\n".join(linhas)


def main():
    if not TOKEN:
        raise SystemExit("defina GPU_TOKEN (rode dentro do container do ajudante: docker exec -it historico-gpu python -m app.painel)")
    historico, cpu, ultima = [], _cpu(), None
    while True:
        maquina, cpu = _maquina(cpu)
        try:
            ultima = _tela(_andamento(), maquina, historico)
            aviso = ""
        except Exception as e:  # noqa: BLE001 - rede/servidor: mostra o último quadro e tenta de novo
            aviso = f"\n{VERMELHO}não deu para atualizar ({type(e).__name__}: {e}); tento de novo em {INTERVALO // 60} min{FIM}"
        print("\033[2J\033[H" + (ultima or "Histórico de conversas: carregando…") + aviso, flush=True)
        time.sleep(INTERVALO)


if __name__ == "__main__":
    try:
        main()
    except KeyboardInterrupt:
        pass
