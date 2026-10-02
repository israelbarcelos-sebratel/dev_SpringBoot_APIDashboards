"""Painel do histórico no terminal: o mês em processamento, todos os dias, atualizado a cada PAINEL_SEG (10 s).

Roda dentro do container do ajudante (usa o mesmo SERVIDOR_URL e GPU_TOKEN; só lê, não reserva nada):
    docker exec -it historico-gpu python -m app.painel

Contas (todas por ligação; as horas são a duração das ligações no Native):
- processadas = transcritas + erros de transcrição (gravação apagada, áudio ruim depois de 3 tentativas): a barra do
  dia é processadas / ligações, então um dia pronto fecha em 100%;
- o mês soma todos os dias, inclusive os sem gravação no Native (que não têm o que transcrever);
- ritmo = áudio processado nos últimos JANELA s; falta = áudio dos dias com gravação ainda não processado;
- o mês completo = fim da transcrição + a IA do último dia (no ritmo da IA medido aqui)."""
import datetime as dt
import json
import os
import subprocess
import time
import urllib.request

URL = os.environ.get("SERVIDOR_URL", "https://n8n-staging.sebratel.net.br/webhook/historico-gpu")
TOKEN = os.environ.get("GPU_TOKEN", "")
INTERVALO = int(os.environ.get("PAINEL_SEG", "10"))
JANELA = int(os.environ.get("PAINEL_JANELA_SEG", "600"))  # ritmo: média dos últimos 10 min
SEMANA = ("seg", "ter", "qua", "qui", "sex", "sáb", "dom")
MESES = ("janeiro", "fevereiro", "março", "abril", "maio", "junho", "julho", "agosto", "setembro", "outubro",
         "novembro", "dezembro")
VERDE, AMARELO, AZUL, CINZA, VERMELHO, NEGRITO, FIM = ("\033[32m", "\033[33m", "\033[36m", "\033[90m", "\033[31m",
                                                       "\033[1m", "\033[0m")


def _andamento():
    req = urllib.request.Request(URL, data=json.dumps({"acao": "andamento", "worker": "painel"}).encode(), method="POST",
                                 headers={"Content-Type": "application/json", "X-Gpu-Token": TOKEN})
    with urllib.request.urlopen(req, timeout=60) as r:
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


def _n(x):
    return f"{x:,}".replace(",", ".")


def _h(seg):
    return f"{seg / 3600:.1f}".replace(".", ",")


def _pct(a, b):
    return (f"{100 * a / b:.1f}".replace(".", ",") + "%") if b else "—"


def _dur(seg):
    h, m = divmod(round(seg / 60), 60)
    return f"{h} h {m:02d} min" if h else f"{m} min"


def _barra(frac, n=12):
    cheio = round(max(0.0, min(1.0, frac)) * n)
    return "█" * cheio + "░" * (n - cheio)


def _situacao(d):
    if d["etapa"] == "indisponivel":
        return CINZA + "sem gravação" + FIM
    if d["status"] == 2:
        return VERDE + "pronto" + FIM
    if d["status"] == 1:
        return (AMARELO + "IA" + FIM) if d["etapa"] == "ia" else (AZUL + "transcrevendo" + FIM)
    if d["transcritas"] or d["com_ajudante"]:
        return AZUL + "adiantando" + FIM  # o ajudante já transcreve enquanto o servidor faz a IA de outro dia
    return CINZA + "na fila" + FIM


def _tela(r, maquina, hist):
    agora = dt.datetime.fromisoformat(r["agora"])
    ano, mes = (int(x) for x in r["mes"].split("-"))
    est = r["estado"]
    linhas = [f"{NEGRITO}Histórico de conversas — {MESES[mes - 1]}/{ano}{FIM}"
              f"   atualizado {agora:%d/%m %H:%M:%S} (a cada {INTERVALO} s · Ctrl+C sai)", ""]
    ajud = est.get("gpu", {}).get("ajudantes", {})
    vivos = [f"{k} há {v}s" for k, v in ajud.items() if v < 180]
    if est.get("rodando") and est.get("dia"):
        servidor = f"dia {dt.date.fromisoformat(est['dia']):%d/%m} · {est.get('etapa') or 'começando'}"
    else:
        servidor = "começando" if est.get("rodando") else f"{AMARELO}parado — o n8n retoma em até 5 min{FIM}"
    linhas.append(f"Servidor: {servidor} · ajudante: {', '.join(vivos) if vivos else VERMELHO + 'nenhum ativo' + FIM}"
                  + (f" · {VERMELHO}erro: {est['erro']}{FIM}" if est.get("erro") else ""))
    linhas.append(f"Esta máquina: {maquina}")
    linhas.append("")
    linhas.append(f"{'Dia':<9}  {'Processadas':<18} {'Situação':<13} {'Ligações':>8} {'Transcr.':>8} {'Err.tr':>6}"
                  f" {'IA':>6} {'Err.IA':>6} {'Áudio transcr./total':>21}  Com quem agora")

    t = dict.fromkeys(("lig", "lig_ok", "trans", "err_t", "ia", "err_ia", "seg", "seg_ok", "seg_t", "seg_e",
                       "prontos", "andamento", "fila", "sem"), 0)
    ultimo_dia_lig = 0
    for d in r["dias"]:
        data = dt.date.fromisoformat(d["data"])
        sem = d["etapa"] == "indisponivel"
        err_t = d.get("erros_transcricao", d["erros"])
        err_ia = d.get("erros_ia", 0)
        seg_e = d.get("seg_erro", 0)
        proc = d["transcritas"] + err_t
        frac = proc / d["ligacoes"] if d["ligacoes"] and not sem else 0.0
        quem = ([f"ajudante {d['com_ajudante']}"] if d["com_ajudante"] else []) + (
            [f"servidor {d['com_servidor']}"] if d["com_servidor"] else [])
        cor = CINZA if sem else ""
        linhas.append(f"{cor}{data:%d/%m} {SEMANA[data.weekday()]}  "
                      + (f"{'':<12} {'':>5}" if sem else f"{_barra(frac)} {100 * frac:>4.0f}%")
                      + f" {_pad(_situacao(d), 13)}{cor} {_n(d['ligacoes']):>8} {_n(d['transcritas']):>8} {_n(err_t):>6}"
                      f" {_n(d['analisadas']):>6} {_n(err_ia):>6}"
                      f" {_h(d['seg_transcrito']) + ' / ' + _h(d['seg']) + ' h':>21}  {', '.join(quem)}{FIM}")
        t["lig"] += d["ligacoes"]
        t["seg"] += d["seg"]
        if sem:
            t["sem"] += 1
            continue
        t["lig_ok"] += d["ligacoes"]
        t["seg_ok"] += d["seg"]
        t["trans"] += d["transcritas"]
        t["err_t"] += err_t
        t["ia"] += d["analisadas"]
        t["err_ia"] += err_ia
        t["seg_t"] += d["seg_transcrito"]
        t["seg_e"] += seg_e
        t["prontos" if d["status"] == 2 else "andamento" if (d["status"] == 1 or proc) else "fila"] += 1
        ultimo_dia_lig = d["ligacoes"]

    proc_seg = t["seg_t"] + t["seg_e"]
    falta_seg = max(0, t["seg_ok"] - proc_seg)
    falta_ia = max(0, t["lig_ok"] - t["err_t"] - t["ia"] - t["err_ia"])
    linhas.append("")
    linhas.append(f"{NEGRITO}Mês ({len(r['dias'])} dias):{FIM} {t['prontos']} pronto(s) · {t['andamento']} em andamento"
                  f" · {t['fila']} na fila · {t['sem']} sem gravação no Native")
    linhas.append(f"  Ligações: {_n(t['lig'])} no mês · {_n(t['lig_ok'])} com gravação"
                  f" · transcritas {_n(t['trans'])} ({_pct(t['trans'], t['lig_ok'])})"
                  f" · erros de transcrição {_n(t['err_t'])} · IA {_n(t['ia'])} ({_pct(t['ia'], t['lig_ok'])})")
    linhas.append(f"  Áudio:    {_h(t['seg'])} h no mês · {_h(t['seg_ok'])} h com gravação"
                  f" · transcrito {_h(t['seg_t'])} h ({_pct(t['seg_t'], t['seg_ok'])}) · falta {_h(falta_seg)} h")
    linhas.append(f"  {_barra(proc_seg / t['seg_ok'] if t['seg_ok'] else 0, 50)} {_pct(proc_seg, t['seg_ok'])} processado")

    # Ritmo nos últimos JANELA s (áudio processado e ligações analisadas pela IA).
    hist.append((time.time(), r["mes"], t["seg_t"], t["ia"] + t["err_ia"]))  # erros entram de uma vez: fora do ritmo
    while hist and (hist[0][1] != r["mes"] or time.time() - hist[0][0] > JANELA):
        hist.pop(0)
    a, b = hist[0], hist[-1]
    dt_s = b[0] - a[0]
    if dt_s < 60:
        linhas.append(f"Ritmo: medindo… (aparece com 1 min de medição; média dos últimos {JANELA // 60} min)")
    else:
        ritmo = (b[2] - a[2]) / dt_s                      # s de áudio por s
        ia_min = (b[3] - a[3]) / dt_s * 60                # ligações por minuto
        if ia_min > 0:
            _tela.ia_min = ia_min                         # guarda o último ritmo da IA (ela roda em surtos)
        if ritmo > 0 and falta_seg:
            fim_t = agora + dt.timedelta(seconds=falta_seg / ritmo)
            ia_ult = getattr(_tela, "ia_min", 0)
            fim = fim_t + dt.timedelta(minutes=ultimo_dia_lig / ia_ult) if ia_ult else None
            linhas.append(f"Ritmo (últimos {_dur(dt_s)}): {ritmo:.0f}x o tempo real ({_h(ritmo * 3600)} h de áudio/h)"
                          f" · IA {ia_min:.0f} ligações/min")
            linhas.append(f"{NEGRITO}Previsão:{FIM} transcrição do mês termina em ~{_dur(falta_seg / ritmo)}"
                          f" ({fim_t:%d/%m %H:%M})"
                          + (f" · mês completo, com a IA do último dia: ~{fim:%d/%m %H:%M}" if fim else
                             " · mês completo: aguardando medir o ritmo da IA"))
        elif not falta_seg:
            linhas.append(f"Transcrição do mês concluída · IA faltando: {_n(falta_ia)} ligações"
                          + (f" (~{_dur(falta_ia / ia_min * 60)})" if ia_min > 0 else ""))
        else:
            linhas.append(f"Ritmo: nada transcrito nos últimos {_dur(dt_s)} (pausa) · IA {ia_min:.0f} ligações/min")

    quem = sorted(r["modelos"].items(), key=lambda kv: -kv[1]["seg"])
    nomes = {"medium gpu": "GPU (medium)", "small cpu ajudante": "CPU do ajudante (small)", "small": "servidor (small)"}
    linhas.append("Quem transcreveu no mês: " + (" · ".join(
        f"{nomes.get(k, k)} {_n(v['ligacoes'])} ({_h(v['seg'])} h)" for k, v in quem) if quem else "ainda ninguém"))
    return linhas


def main():
    if not TOKEN:
        raise SystemExit("defina GPU_TOKEN (rode dentro do container do ajudante: docker exec -it historico-gpu python -m app.painel)")
    hist, cpu, ultima = [], _cpu(), None
    print("\033[2J", end="")
    while True:
        maquina, cpu = _maquina(cpu)
        try:
            ultima = _tela(_andamento(), maquina, hist)
            aviso = []
        except Exception as e:  # noqa: BLE001 - rede/servidor: mostra o último quadro e tenta de novo
            aviso = [f"{VERMELHO}não deu para atualizar ({type(e).__name__}: {e}); tento de novo em {INTERVALO} s{FIM}"]
        # Redesenha por cima (sem limpar a tela inteira): sem piscar a cada 10 s.
        texto = "\n".join(l + "\033[K" for l in (ultima or ["Histórico de conversas: carregando…"]) + aviso)
        print("\033[H" + texto + "\033[J", end="", flush=True)
        time.sleep(INTERVALO)


if __name__ == "__main__":
    try:
        main()
    except KeyboardInterrupt:
        print()
