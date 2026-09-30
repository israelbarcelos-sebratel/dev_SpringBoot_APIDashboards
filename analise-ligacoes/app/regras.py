"""Regra Mute / "linha com problema": marcas por ligação, cadeias de transferência e máscara de dados.

A gravação é mono (atendente e cliente na mesma trilha), então as regras só apontam o que vale ouvir;
o entendimento fica com a IA (Gemini, no n8n) e a confirmação com quem ouve a gravação.
"""
import re
import unicodedata

INICIO_MUDO = 6.0     # s até a primeira fala depois de atender
FIM_MUDO = 15.0       # s de silêncio antes da ligação terminar
CURTA_DERRUBADA = 30  # s: ligação curta derrubada pelo atendente (desconexão = Destino)
ENCERROU = 45.0       # s: alegou problema na linha e a ligação acabou até aqui depois
RELIGOU_MIN = 30      # min: cliente ligou de novo -> o problema não foi resolvido
CLIENTE_DESISTIU = 5  # s: cliente desligou antes disso -> não é do atendente
PESO_SUSPEITA = 3     # a partir deste peso a ligação vai para a IA
FIM_CADEIA = 3.0      # s: gravações que terminam juntas são a mesma ligação transferida

# Técnicos/colegas ligando: "linha ruim" costuma ser real.
INTERNAS = {"FILA_ATD_1052", "FILA_ATD_BKO_INTERNO"}

FRASES = {
    "nao_ouve": r"n[aã]o (estou |to |tô |t[oô] )?(te |lhe |o senhor |a senhora |voc[eê] )?(ouvindo|escutando)"
                r"|n[aã]o (te |lhe )?(ou[çc]o|escuto)|n[aã]o (consigo|t[oô] conseguindo|estou conseguindo) (te |lhe )?(ouvir|escutar)",
    "linha": r"linha (est[aá] |t[aá] )?(com problema|ruim|chiando|falhando|caindo|muda|p[eé]ssima)"
             r"|liga[çc][aã]o (est[aá] |t[aá] )?(falhando|cortando|picotando|muda|ruim|caindo)"
             r"|(est[aá]|t[aá]) (cortando|falhando|picotando|chiando)|muito (ru[ií]do|chiado)"
             r"|muito (ruim|curto|para|pra) (a |pra )?minha liga[çc][aã]o|sem [aá]udio|\bmud[oa]\b",
    "religar": r"(liga|ligue|ligar|retorna|retornar|retorne)( pra gente| para n[oó]s)? (de novo|novamente|mais tarde|daqui a pouco)"
               r"|volta(r)? a ligar|retornar (a|essa) liga[çc][aã]o",
}
OUTRO_CANAL = re.compile(FRASES["religar"] + r"|whats|zap|\bno ato\b|te chamo|te chamar", re.I)
CLIENTE_OUVE = re.compile(r"(?<!não )(?<!nao )\b(estou|tô|to|t[oô]) (te |lhe |sim )?(ouvindo|escutando)"
                          r"|(?<!não )(?<!nao )\bte ou[çc]o\b", re.I)
ALO = re.compile(r"\bal[oô]\b", re.I)
# Frases que o Whisper inventa em silêncio/ruído.
ALUCINA = re.compile(r"amara\.org|legendas|obrigad[oa] por assistir|inscreva-se|tchau, tchau\.$", re.I)

# LGPD: nada de CPF, telefone, e-mail ou sequência longa de números no texto guardado / enviado à IA.
_EMAIL = re.compile(r"\b[\w.+-]+@[\w-]+\.[\w.]+\b")
_NUMEROS = re.compile(r"\b(?:\d[\s.\-/]?){5,}\d\b")


def mascarar(texto):
    return _NUMEROS.sub("[número]", _EMAIL.sub("[e-mail]", texto))


def norm(s):
    return unicodedata.normalize("NFC", s.lower())


def segundos(hora):
    h, m, s = (int(p) for p in str(hora).split(":")[:3])
    return h * 3600 + m * 60 + s


def montar_cadeias(ligacoes):
    """Marca cadeia / ultimoDaCadeia e dá ao último o texto das outras gravações (mesmo áudio do fim,
    outra transcrição), levado para a linha do tempo dele. A gravação de quem transfere continua depois
    da transferência, então o fim pertence a quem entrou por último."""
    validas = [x for x in ligacoes if x.get("duracao") is not None]
    for x in validas:
        x["_fimRelogio"] = segundos(x["hora"]) + x["duracao"]
    validas.sort(key=lambda x: x["_fimRelogio"])
    for i, x in enumerate(validas):
        grupo = [x]
        for y in validas[i + 1:]:
            if y["_fimRelogio"] - x["_fimRelogio"] > FIM_CADEIA:
                break
            grupo.append(y)
        for y in reversed(validas[:i]):
            if x["_fimRelogio"] - y["_fimRelogio"] > FIM_CADEIA:
                break
            grupo.append(y)
        if len(grupo) > 1:
            x["cadeia"] = [f"{y['hora']} {y['agente']}" for y in sorted(grupo, key=lambda y: segundos(y["hora"]))]
            x["ultimoDaCadeia"] = x is max(grupo, key=lambda y: segundos(y["hora"]))
            x["_grupo"] = grupo
    for x in validas:
        if x.get("ultimoDaCadeia"):
            extra = []
            for y in x["_grupo"]:
                if y is x:
                    continue
                desloc = segundos(x["hora"]) - segundos(y["hora"])
                extra += [{"ini": round(s["ini"] - desloc, 1), "fim": round(s["fim"] - desloc, 1), "texto": s["texto"]}
                          for s in y.get("segmentos") or [] if s["ini"] - desloc >= 0]
            x["segmentosCadeia"] = sorted((x.get("segmentos") or []) + extra, key=lambda s: s["ini"])
    for x in validas:
        x.pop("_grupo", None)
        x.pop("_fimRelogio", None)


def avaliar(x):
    """(marcas, peso, trechos do atendente). Só o trecho do próprio atendente: [0, atendimento]; numa
    transferida a gravação segue com quem recebeu, e o último da cadeia fica com o fim."""
    seg = x.get("seg")
    duracao = x["duracao"]
    fim_ag = min(duracao, seg + 3) if seg is not None else duracao
    if x.get("ultimoDaCadeia"):
        fim_ag = duracao
    fonte = x.get("segmentosCadeia") or x.get("segmentos") or []
    segs = [s for s in fonte if s["ini"] <= fim_ag and not ALUCINA.search(s["texto"])]
    marcas, peso = [], 0
    silencio = False
    # Cliente desligou nos primeiros segundos: o atendente nem teve tempo de falar — não conta contra ele.
    if x.get("desconexao") == "Origem" and seg is not None and seg <= CLIENTE_DESISTIU:
        return ["cliente desligou logo"], 0, segs
    if not segs and (x.get("inicioFala") is None or x["inicioFala"] >= fim_ag):
        marcas.append("sem fala nenhuma")
        peso += 2
        silencio = True
    elif x.get("inicioFala") is not None and x["inicioFala"] >= INICIO_MUDO:
        marcas.append(f"mudo no início ({x['inicioFala']:.0f}s)")
        peso += 1
    if x.get("desconexao") != "Transferida":
        mudo = [b for b in x.get("buracos") or [] if b[1] >= fim_ag - 0.5 and b[1] - b[0] >= FIM_MUDO]
        if mudo:
            marcas.append(f"mudo no fim ({mudo[0][1] - mudo[0][0]:.0f}s)")
            peso += 2 if x.get("desconexao") == "Origem" else 1  # cliente desistiu depois do silêncio
    alegou = None
    for s in segs:
        t = norm(s["texto"])
        for nome in ("nao_ouve", "linha"):
            m = re.search(FRASES[nome], t, re.I)
            if m:
                if fim_ag - s["ini"] <= ENCERROU:
                    alegou = alegou or s
                    marcas.append(f"alegou problema na linha e encerrou ({s['ini']:.0f}s: \"{m.group(0)}\")")
                else:
                    marcas.append(f"(linha ruim mas a ligação seguiu, {s['ini']:.0f}s)")
                break
    if alegou:
        peso += 4 if x.get("desconexao") != "Transferida" else 2
        depois = [s for s in segs if s["ini"] >= alegou["ini"] - 15]
        if any(CLIENTE_OUVE.search(norm(s["texto"])) for s in depois):
            marcas.append("cliente disse que estava ouvindo")
            peso += 2
        if OUTRO_CANAL.search(norm(" ".join(s["texto"] for s in depois))):
            marcas.append("mandou religar / WhatsApp")
            peso += 1
    alos = sum(len(ALO.findall(s["texto"])) for s in segs if s["ini"] <= 20)
    if alos >= 3:
        marcas.append(f"alô x{alos} no começo")
        peso += 1
    curta = seg is not None and seg <= CURTA_DERRUBADA and x.get("desconexao") == "Destino"
    if curta:
        marcas.append(f"curta ({seg}s) derrubada pelo atendente")
        peso += 2
    religou = x.get("religouMin")
    if religou is not None and religou <= RELIGOU_MIN and (silencio or curta or alegou):
        marcas.append(f"cliente ligou de novo em {religou} min")
        peso += 2
    if x.get("cadeia") and not x.get("ultimoDaCadeia"):  # transferiu: não é "derrubou" nem "mudo até cair"
        tirar = [m for m in marcas if m.startswith(("curta", "sem fala", "mudo no fim", "cliente ligou"))]
        peso -= sum(1 if m.startswith("mudo no fim") else 2 for m in tirar)
        marcas = [m for m in marcas if m not in tirar] + (["transferiu"] if tirar else [])
    if x.get("fila") in INTERNAS:
        marcas.append("fila interna")
        peso -= 2
    return marcas, peso, segs


def trecho(segs, limite=40):
    """Linhas "12s texto" do atendente; nas longas, começo e fim."""
    if len(segs) > limite:
        segs = segs[:8] + [{"ini": segs[8]["ini"], "texto": "[…]"}] + segs[-(limite - 9):]
    return [f"{s['ini']:.0f}s {s['texto']}" for s in segs]
