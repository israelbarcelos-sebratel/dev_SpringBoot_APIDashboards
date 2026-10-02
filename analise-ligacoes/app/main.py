"""API interna da análise de ligações (só na rede do n8n, sem porta publicada).

POST /analises                      {data?, limite?, reprocessar?}  dispara o dia (padrão: ontem)
GET  /analises/{data}               andamento: tabela 0/1/2, ritmo, previsão
GET  /analises/{data}/chamadas      a tabela de controle (?status=0|1|2)
GET  /analises/{data}/suspeitas     ligações para a IA, cada uma com o geminiRequest pronto
POST /analises/{data}/avaliacoes    [{protocolo, resultado | resposta | erro}] parecer da IA
GET  /analises/{data}/resumo        totais e casos (JSON)
GET  /analises/{data}/relatorio     o mesmo em HTML

Histórico (todas as ligações, transcrição completa + IA, um dia por vez do mais antigo ao mais novo):
POST /historico/avancar             garante o processo rodando (o n8n chama de tempos em tempos)
GET  /historico                     andamento: dias 0/1/2, dia atual, ritmo, previsão
GET  /historico/dias                a tabela de dias (?status=0|1|2)
POST /historico/reavaliar           volta para a IA as ligações em que ela falhou (?data=AAAA-MM-DD opcional)
POST /historico/gpu                 ajudante com GPU: {acao: vivo | pegar | entregar | falhou | andamento} (header
                                    X-Gpu-Token; chega pelo webhook do n8n, n8n/historico-gpu.json — o container continua
                                    sem porta publicada). andamento: o mês em processamento, dia a dia (painel)
"""
import datetime as dt
import hmac
import html
import json
import logging
import os
import threading
from decimal import Decimal
from typing import Any, Optional

from fastapi import Depends, FastAPI, Header, HTTPException, Query, Request
from fastapi.responses import HTMLResponse
from pydantic import BaseModel, Field

from . import db, gemini, historico, ia, pipeline, regras

logging.basicConfig(level=logging.INFO, format="%(asctime)s %(levelname)s %(name)s %(message)s")
log = logging.getLogger("analise")
TOKEN = os.environ.get("ANALISE_TOKEN", "")
app = FastAPI(title="analise-ligacoes")


def _token(x_token: str = Header(default="")):
    if TOKEN and x_token != TOKEN:
        raise HTTPException(401, "token inválido")


def _data(valor: str) -> str:
    try:
        return dt.date.fromisoformat(valor).isoformat()
    except ValueError:
        raise HTTPException(400, "data no formato AAAA-MM-DD") from None


def _consulta(sql, args=()):
    with db.app() as c, c.cursor() as cur:
        cur.execute(sql, args)
        return cur.fetchall()


def _linha(r):
    out = dict(r)
    for k in ("marcas", "cadeia", "trecho", "buracos", "transcrito"):
        if k in out and out[k]:
            out[k] = json.loads(out[k])
    for k in ("inicio", "fim", "ia_em", "data"):
        if out.get(k) is not None:
            out[k] = str(out[k])
    if "hora" in out:
        out["hora"] = pipeline.hms(out["hora"])
    return out


# Colunas com texto de conversa (JSON ou texto livre): a máscara é passada de novo a cada subida — barato e
# idempotente, e cobre o que foi gravado antes de a máscara melhorar (ex.: CPF ditado com vírgulas, 01/10/2026).
_COM_TEXTO = (
    ("analise_ligacao", ("data", "protocolo"), ("segmentos", "trecho", "ia_json"), ("ia_justificativa", "ia_motivo")),
    ("conversa_ligacao", ("protocolo",), ("transcricao", "ia_json", "palavras_chave"),
     ("resumo", "motivo", "regra_mudo_justificativa", "pontos_atencao")),
)


def _remascarar():
    alteradas = 0
    with db.app() as c, c.cursor() as cur:
        for tabela, chave, jsons, textos in _COM_TEXTO:
            cur.execute(f"SELECT {', '.join(chave + jsons + textos)} FROM {tabela}")
            for r in cur.fetchall():
                novo = {k: json.dumps(regras.mascarar_obj(json.loads(r[k])), ensure_ascii=False) for k in jsons if r[k]}
                novo.update({k: regras.mascarar(r[k]) for k in textos if r[k]})
                mud = {k: v for k, v in novo.items() if v != r[k]}
                if mud:
                    cur.execute(f"UPDATE {tabela} SET {', '.join(f'{k}=%s' for k in mud)} WHERE "
                                + " AND ".join(f"{k}=%s" for k in chave), [*mud.values(), *(r[k] for k in chave)])
                    alteradas += 1
    log.info("máscara revisada: %d linha(s) alterada(s)", alteradas)


@app.on_event("startup")
def _startup():
    def preparar():
        db.criar_schema()
        _remascarar()
        # Container reiniciado no meio de um dia: continua de onde parou.
        for r in _consulta("SELECT data FROM analise_execucao WHERE status IN ('processando','analisando') ORDER BY data"):
            log.info("retomando %s", r["data"])
            pipeline.iniciar(str(r["data"]))
            break
        # Histórico: a chave do Gemini só chega na próxima chamada do n8n (até 15 min) e aí ele continua.
    threading.Thread(target=preparar, daemon=True).start()


@app.get("/saude")
def saude():
    return {"ok": True, "rodando": pipeline.rodando(), "modelo": pipeline.MODELO, "workers": pipeline.WORKERS,
            "historico": historico.estado(), "iaConfigurada": ia.configurado()}


class Pedido(BaseModel):
    data: Optional[str] = None
    limite: Optional[int] = None
    reprocessar: bool = False


@app.post("/analises", dependencies=[Depends(_token)], status_code=202)
def disparar(p: Pedido):
    data = _data(p.data) if p.data else (dt.date.today() - dt.timedelta(days=1)).isoformat()
    if pipeline.rodando() == data:
        return {"data": data, "status": "processando"}
    ex = _consulta("SELECT status FROM analise_execucao WHERE data=%s", (data,))
    if ex and ex[0]["status"] == "pronto" and not p.reprocessar:
        return {"data": data, "status": "pronto"}
    if not pipeline.iniciar(data, p.limite, p.reprocessar):
        raise HTTPException(409, f"já está processando {pipeline.rodando()}")
    return {"data": data, "status": "processando"}


@app.get("/analises/{data}", dependencies=[Depends(_token)])
def andamento(data: str):
    data = _data(data)
    ex = _consulta("SELECT * FROM analise_execucao WHERE data=%s", (data,))
    if not ex:
        raise HTTPException(404, "dia não processado")
    t = _consulta("SELECT status, COUNT(*) n, SUM(IFNULL(seg,0)) seg, SUM(erro IS NOT NULL) erros FROM analise_ligacao"
                  " WHERE data=%s GROUP BY status", (data,))
    tab = {str(k): 0 for k in (0, 1, 2)}
    seg = {str(k): 0 for k in (0, 1, 2)}
    erros = 0
    for r in t:
        tab[str(r["status"])] = r["n"]
        seg[str(r["status"])] = int(r["seg"] or 0)
        erros += int(r["erros"] or 0)
    ult = _consulta("SELECT COUNT(*) n, TIMESTAMPDIFF(SECOND, MAX(fim), NOW()) ha,"
                    " TIMESTAMPDIFF(SECOND, MIN(inicio), NOW()) rodando FROM analise_ligacao"
                    " WHERE data=%s AND fim >= NOW() - INTERVAL 10 MINUTE", (data,))[0]
    ultima = _consulta("SELECT TIMESTAMPDIFF(SECOND, MAX(fim), NOW()) ha FROM analise_ligacao WHERE data=%s", (data,))[0]
    janela = max(60, min(600, ult["rodando"] or 600))
    ritmo = round(ult["n"] / janela * 60, 1)
    falta = tab["0"] + tab["1"]
    susp = _consulta("SELECT COUNT(*) n, SUM(ia_enquadra IS NOT NULL) av FROM analise_ligacao WHERE data=%s AND suspeita=1",
                     (data,))[0]
    e = ex[0]
    return {
        "data": data, "status": e["status"], "rodandoAgora": pipeline.rodando() == data,
        "iniciada": str(e["iniciada"]) if e["iniciada"] else None, "terminada": str(e["terminada"]) if e["terminada"] else None,
        "erro": e["erro"], "modelo": e["modelo"],
        "tabela": {"0_nao_feito": tab["0"], "1_trabalhando": tab["1"], "2_pronto": tab["2"]},
        "total": sum(tab.values()), "errosLigacao": erros,
        "audioMin": {"pronto": round(seg["2"] / 60), "total": round(sum(seg.values()) / 60)},
        "ritmoPorMin": ritmo, "previsaoMin": round(falta / ritmo) if ritmo and falta else (0 if not falta else None),
        "ultimaConcluidaHaSeg": ultima["ha"],
        "suspeitas": int(susp["n"] or 0), "avaliadasIA": int(susp["av"] or 0),
    }


@app.get("/analises/{data}/chamadas", dependencies=[Depends(_token)])
def chamadas(data: str, status: Optional[int] = Query(default=None)):
    data = _data(data)
    sql = ("SELECT protocolo, hora, agente, fila, seg, desconexao, status, etapa, worker, inicio, fim, erro, peso, suspeita,"
           " ia_enquadra FROM analise_ligacao WHERE data=%s")
    args = [data]
    if status is not None:
        sql += " AND status=%s"
        args.append(status)
    return [_linha(r) for r in _consulta(sql + " ORDER BY hora", args)]


def _suspeitas(data, so_sem_avaliacao=False):
    sql = ("SELECT protocolo, hora, agente, fila, seg, desconexao, religou_min, gravacao, marcas, peso, cadeia, trecho,"
           " ia_enquadra, ia_confianca, ia_sentimento, ia_motivo, ia_justificativa, ia_json FROM analise_ligacao"
           " WHERE data=%s AND suspeita=1")
    if so_sem_avaliacao:
        sql += " AND ia_enquadra IS NULL"
    return [_linha(r) for r in _consulta(sql + " ORDER BY peso DESC, hora", (data,))]


@app.get("/analises/{data}/suspeitas", dependencies=[Depends(_token)])
def suspeitas(data: str, pendentes: bool = True):
    """Para a IA: cada item traz o geminiRequest pronto (o n8n só repassa)."""
    out = []
    for l in _suspeitas(_data(data), so_sem_avaliacao=pendentes):
        out.append({k: l[k] for k in ("protocolo", "hora", "agente", "fila", "seg", "desconexao", "peso", "marcas")}
                   | {"geminiRequest": gemini.pedido(l)})
    return out


class Avaliacao(BaseModel):
    protocolo: str
    resultado: Optional[dict[str, Any]] = None  # JSON já extraído
    resposta: Optional[Any] = None              # ou a resposta bruta do generateContent
    erro: Optional[str] = None


@app.post("/analises/{data}/avaliacoes", dependencies=[Depends(_token)])
def avaliacoes(data: str, itens: list[Avaliacao]):
    data = _data(data)
    gravadas, falhas = 0, []
    with db.app() as c, c.cursor() as cur:
        for a in itens:
            try:
                if a.erro:
                    raise ValueError(a.erro)
                r = regras.mascarar_obj(a.resultado or gemini.ler_resposta(a.resposta))
                cur.execute("UPDATE analise_ligacao SET ia_enquadra=%s, ia_confianca=%s, ia_sentimento=%s, ia_motivo=%s,"
                            " ia_justificativa=%s, ia_json=%s, ia_em=NOW() WHERE data=%s AND protocolo=%s AND suspeita=1",
                            (r.get("enquadra"), r.get("confianca"), r.get("sentimento_cliente"),
                             (r.get("motivo_contato") or "")[:255], r.get("justificativa"),
                             json.dumps(r, ensure_ascii=False), data, a.protocolo))
                gravadas += cur.rowcount
            except Exception as e:  # noqa: BLE001 - resposta ruim de uma ligação não perde as outras
                falhas.append({"protocolo": a.protocolo, "erro": str(e)[:300]})
    return {"gravadas": gravadas, "falhas": falhas}


@app.get("/analises/{data}/resumo", dependencies=[Depends(_token)])
def resumo(data: str):
    data = _data(data)
    st = andamento(data)
    por = _consulta("SELECT agente, COUNT(*) ligacoes, SUM(suspeita) suspeitas, SUM(IFNULL(ia_enquadra='sim', 0)) sim,"
                    " SUM(IFNULL(ia_enquadra='inconclusivo', 0)) inconclusivo FROM analise_ligacao WHERE data=%s GROUP BY agente"
                    " HAVING SUM(suspeita) > 0 ORDER BY sim DESC, inconclusivo DESC, suspeitas DESC", (data,))
    casos = _suspeitas(data)
    cont = {"sim": 0, "inconclusivo": 0, "nao": 0, "semAvaliacao": 0}
    for c in casos:
        cont[c["ia_enquadra"] if c["ia_enquadra"] in cont else "semAvaliacao"] += 1
    return {
        "data": data, "status": st["status"], "ligacoes": st["total"], "suspeitas": len(casos), "ia": cont,
        "porAtendente": [{k: int(v) if isinstance(v, Decimal) else v for k, v in r.items()} for r in por],
        "casos": [{"hora": c["hora"], "agente": c["agente"], "fila": c["fila"], "seg": c["seg"], "peso": c["peso"],
                   "marcas": c["marcas"], "enquadra": c["ia_enquadra"], "confianca": c["ia_confianca"],
                   "sentimento": c["ia_sentimento"], "motivo": c["ia_motivo"], "justificativa": c["ia_justificativa"],
                   "gravacao": c["gravacao"], "protocolo": c["protocolo"]} for c in casos],
    }


_COR = {"sim": "#b42318", "inconclusivo": "#b54708", "nao": "#067647", None: "#475467"}
_ORDEM = {"sim": 0, "inconclusivo": 1, None: 2, "nao": 3}


@app.get("/analises/{data}/relatorio", response_class=HTMLResponse, dependencies=[Depends(_token)])
def relatorio(data: str):
    r = resumo(data)
    e = html.escape
    casos = sorted(_suspeitas(r["data"]), key=lambda c: (_ORDEM.get(c["ia_enquadra"], 2), -(c["peso"] or 0)))
    linhas = "".join(
        f"<tr><td>{e(a['agente'] or '-')}</td><td>{a['ligacoes']}</td><td>{a['suspeitas']}</td>"
        f"<td>{a['sim'] or 0}</td><td>{a['inconclusivo'] or 0}</td></tr>" for a in r["porAtendente"])
    cards = []
    for c in casos:
        v = c["ia_enquadra"]
        ia = (f"<p><b style='color:{_COR.get(v, '#475467')}'>IA: {e(v or 'sem avaliação')}</b>"
              + (f" · confiança {c['ia_confianca']:.0%}" if c["ia_confianca"] is not None else "")
              + (f" · cliente {e(c['ia_sentimento'])}" if c["ia_sentimento"] else "")
              + (f" · {e(c['ia_motivo'])}" if c["ia_motivo"] else "") + "</p>"
              + (f"<p>{e(c['ia_justificativa'])}</p>" if c["ia_justificativa"] else ""))
        cadeia = f"<p class='m'>Cadeia: {e(' → '.join(c['cadeia']))}</p>" if c.get("cadeia") else ""
        grav = f" · <a href='{e(c['gravacao'])}' target='_blank' rel='noreferrer'>gravação</a>" if c["gravacao"] else ""
        cards.append(
            f"<div class='card' style='border-left-color:{_COR.get(v, '#475467')}'>"
            f"<h3>{e(c['hora'])} · {e(c['agente'] or '-')}</h3>"
            f"<p class='m'>{e(c['fila'] or '')} · {c['seg']} s · encerrou: {e(c['desconexao'] or '-')} · peso {c['peso']}{grav}</p>"
            f"<p class='m'>{e('; '.join(c['marcas'] or []))}</p>{cadeia}{ia}"
            f"<details><summary>Transcrição do trecho</summary><pre>{e(chr(10).join(c['trecho'] or []))}</pre></details></div>")
    ia = r["ia"]
    return f"""<!doctype html><html lang="pt-BR"><head><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1">
<title>Análise de ligações {e(r['data'])}</title><style>
body{{font:14px/1.45 system-ui,sans-serif;margin:0;padding:16px;background:#f8fafc;color:#101828}}main{{max-width:960px;margin:auto}}
table{{border-collapse:collapse;width:100%;background:#fff}}td,th{{border-bottom:1px solid #eaecf0;padding:6px 8px;text-align:left}}
.card{{background:#fff;border:1px solid #eaecf0;border-left:4px solid;border-radius:6px;padding:10px 14px;margin:10px 0}}
.card h3{{margin:0 0 4px;font-size:15px}}.m{{color:#475467;margin:2px 0}}pre{{white-space:pre-wrap;font-size:12.5px;background:#f2f4f7;padding:8px}}
</style></head><body><main>
<h1>Mute / "linha com problema" — {e(r['data'])}</h1>
<p>{r['ligacoes']} ligações atendidas · {r['suspeitas']} suspeitas · IA: {ia['sim']} enquadram, {ia['inconclusivo']} inconclusivas,
{ia['nao']} não, {ia['semAvaliacao']} sem avaliação · status: {e(r['status'])}</p>
<p class="m">Gravação mono: a IA indica, a decisão é de quem ouve a gravação.</p>
<h2>Por atendente</h2><table><tr><th>Atendente</th><th>Ligações</th><th>Suspeitas</th><th>IA: sim</th><th>IA: inconclusivo</th></tr>{linhas}</table>
<h2>Casos</h2>{''.join(cards) or '<p>Nenhuma ligação suspeita.</p>'}
</main></body></html>"""


# ---------------------------------------------------------------- histórico
def _andamento_historico():
    dias = historico.dias_candidatos()
    feitos = {str(r["data"]): r for r in _consulta("SELECT * FROM conversa_dia")}
    cont = {"0_nao_feito": 0, "1_trabalhando": 0, "2_pronto": 0}
    indisponiveis = 0
    for d in dias:
        r = feitos.get(d)
        st = r["status"] if r else 0
        cont[("0_nao_feito", "1_trabalhando", "2_pronto")[st]] += 1
        if r and r["etapa"] == "indisponivel":
            indisponiveis += 1
    atual = next((r for r in feitos.values() if r["status"] == 1), None)
    # Ritmo: ligações concluídas na última hora; previsão pelo tempo médio dos dias já processados de verdade.
    ritmo = _consulta("SELECT COUNT(*) n FROM conversa_ligacao WHERE fim >= NOW() - INTERVAL 1 HOUR AND status=2")[0]["n"]
    medio = _consulta("SELECT AVG(TIMESTAMPDIFF(SECOND, iniciada, terminada)) s, SUM(total) ligacoes, COUNT(*) n"
                      " FROM conversa_dia WHERE status=2 AND etapa='pronto'")[0]
    restantes = cont["0_nao_feito"] + cont["1_trabalhando"]
    tot = _consulta("SELECT COUNT(*) n, SUM(ia_em IS NOT NULL) ia, SUM(erro IS NOT NULL) erro_stt,"
                    " SUM(ia_erro IS NOT NULL) erro_ia FROM conversa_ligacao")[0]
    return {
        "estado": historico.estado(), "iaConfigurada": ia.configurado(), "modeloStt": historico.MODELO,
        "modeloIa": ia.MODELO, "dias": {"total": len(dias), **cont, "indisponiveisNoNative": indisponiveis,
                                        "primeiro": dias[0] if dias else None, "ultimo": dias[-1] if dias else None},
        "diaAtual": {k: (str(v) if isinstance(v, (dt.date, dt.datetime)) else v) for k, v in atual.items()} if atual else None,
        "ligacoes": {"registradas": tot["n"], "comIa": int(tot["ia"] or 0), "errosTranscricao": int(tot["erro_stt"] or 0),
                     "errosIa": int(tot["erro_ia"] or 0)},
        "ritmoPorHora": ritmo,
        "previsaoDias": round(restantes * float(medio["s"]) / 86400, 1) if medio["s"] and restantes else None,
    }


@app.post("/historico/avancar", dependencies=[Depends(_token)])
def historico_avancar(request: Request):
    # A credencial do Gemini do n8n manda a chave (query "key" ou header "x-goog-api-key"); fica só em memória.
    ia.definir_chave(request.query_params.get("key") or request.headers.get("x-goog-api-key"))
    r = historico.avancar()
    return {**r, "andamento": _andamento_historico()}


@app.get("/historico", dependencies=[Depends(_token)])
def historico_andamento():
    return _andamento_historico()


@app.get("/historico/dias", dependencies=[Depends(_token)])
def historico_dias(status: Optional[int] = Query(default=None)):
    sql = "SELECT * FROM conversa_dia" + (" WHERE status=%s" if status is not None else "") + " ORDER BY data"
    return [{k: (str(v) if isinstance(v, (dt.date, dt.datetime)) else v) for k, v in r.items()}
            for r in _consulta(sql, (status,) if status is not None else ())]


@app.post("/historico/reavaliar", dependencies=[Depends(_token)])
def historico_reavaliar(data: Optional[str] = Query(default=None)):
    historico.reavaliar(_data(data) if data else None)
    return {"status": "reavaliando", "data": data}


class Segmento(BaseModel):
    ini: float
    fim: float
    texto: str = Field(max_length=5000)


class Transcricao(BaseModel):
    duracao: float
    falaSeg: float
    inicioFala: Optional[float] = None
    buracos: list[list[float]] = Field(max_length=5000)
    segmentos: list[Segmento] = Field(max_length=20000)


class PedidoGpu(BaseModel):
    acao: str = Field(pattern="^(vivo|pegar|entregar|falhou|andamento)$")
    worker: str = Field(pattern=r"^[\w.-]{1,40}$")
    n: int = 4
    reserva: Optional[str] = Field(default=None, max_length=60)
    reservas: list[str] = Field(default=[], max_length=100)  # vivo: as que o ajudante tem em mãos agora
    protocolo: Optional[str] = Field(default=None, max_length=50)
    resultado: Optional[Transcricao] = None
    modelo: Optional[str] = Field(default=None, max_length=40)
    erro: Optional[str] = Field(default=None, max_length=2000)
    contar: bool = True
    definitivo: bool = False


@app.post("/historico/gpu")
def historico_gpu(p: PedidoGpu, x_gpu_token: str = Header(default="")):
    if not historico.GPU_TOKEN:
        raise HTTPException(404, "ajudante com GPU desligado (GPU_TOKEN vazio na stack)")
    if not hmac.compare_digest(x_gpu_token.encode(), historico.GPU_TOKEN.encode()):
        raise HTTPException(401, "token inválido")
    if p.acao in ("entregar", "falhou") and not (p.reserva and p.protocolo):
        raise HTTPException(400, "reserva e protocolo são obrigatórios")
    if p.acao == "entregar" and not p.resultado:
        raise HTTPException(400, "resultado é obrigatório")
    return historico.gpu_pedido(p.model_dump())
