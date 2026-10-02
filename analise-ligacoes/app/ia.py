"""Entendimento de uma conversa inteira pelo Gemini (histórico). Só vai o TEXTO já mascarado — o áudio nunca sai
do servidor. A chave fica só no n8n: o nó do histórico usa a credencial do Gemini e ela chega a cada chamada de
POST /historico/avancar; aqui ela fica só em memória (nunca em banco, log ou stack)."""
import json
import os
import time
import urllib.error
import urllib.request

API = "https://generativelanguage.googleapis.com/v1beta/models/{modelo}:generateContent"
CHAVE = ""
MODELO = os.environ.get("GEMINI_MODELO", "gemini-3.8-flash")
MAX_CARACTERES = 60000          # transcrição muito longa: começo e fim
ESPERAS = (5, 15, 45, 90, 180)  # s entre tentativas quando o Gemini está sobrecarregado (429/5xx)

INSTRUCOES = """Você é analista de qualidade do call center da Sebratel (provedor de internet). Analise UMA ligação \
atendida e devolva os dados pedidos, em português.

Como ler os dados:
- A transcrição foi feita por IA (Whisper) a partir de gravação MONO: atendente e cliente estão juntos e as falas NÃO \
dizem quem falou — deduza pelo conteúdo. Há erros de transcrição.
- O número no começo de cada linha é o segundo desde que o atendente atendeu. "[…]" = trecho cortado.
- CPF, telefones, e-mails e números longos foram mascarados. Não tente reconstruí-los e não cite nomes de clientes \
no resumo (use "o cliente").
- "Marcas" são sinais automáticos por regra e podem ser falso positivo.

Regra "Mute / linha com problema": o atendente coloca a ligação no mudo, fica em silêncio ou alega problema na linha \
para encerrar ou empurrar o cliente para outro canal, quando o problema não é real — inclusive por falta de paciência \
(ex.: cliente distraído). NÃO é a regra: problema de linha real em que a conversa continua; ligação entre colegas; \
transferência para outro setor; cliente que desliga sozinho depois de um atendimento normal; ligação muda do lado do \
cliente. Sinais fortes: o cliente diz que está ouvindo e o atendente encerra dizendo que não ouve; o atendente atende, \
não fala nada e derruba; o cliente liga de novo logo em seguida.

Roteiro de atendimento: saudação com o nome do atendente e "Sebratel"; identificação do cliente (nome/CPF/contrato); \
protocolo informado; pergunta se pode ajudar em algo mais; encerramento cordial.

Sentimento do cliente (a Sebratel dá muita importância a ele): SEMPRE dê um valor — positivo, neutro ou negativo —, \
no início, no fim e na ligação como um todo (pese mais o fim). A dúvida não vira "indefinido": vai na confiança. \
Avalie pelas falas do cliente: reclamações, irritação, ameaça de cancelar, repetição do problema, agradecimentos, alívio. \
sentimento_confianca (0 a 100) diz o quanto você tem certeza do sentimento geral: 100 só quando as falas do cliente \
são claras, sem erro de transcrição que importe, sem dúvida de quem falou e coerentes do começo ao fim. Referência: \
90–99 dúvida pequena; 70–89 dúvida real; 40–69 pouca evidência; abaixo de 40 quase um palpite (ex.: sem fala do \
cliente — use neutro). Abaixo de 100, liste em sentimento_motivos TODOS os motivos que tiraram a certeza, cada um \
com o detalhe concreto: o que, citando os segundos ou as falas.

Na dúvida use "indefinido"/"inconclusivo" (menos no sentimento). Nunca invente falas que não estão na transcrição."""

_SN = {"type": "BOOLEAN"}
_SENT = {"type": "STRING", "enum": ["positivo", "neutro", "negativo"]}
SENTIMENTOS = _SENT["enum"]
# Por que o sentimento não tem 100% de confiança. Os da IA ficam no esquema; os outros o servidor põe (ele sabe o que a
# IA não vê: ligação sem gravação, transcrição que falhou ou foi cortada).
MOTIVOS_SENTIMENTO = {
    "transcricao_ruim": "trecho importante com erro de transcrição ou sem sentido",
    "quem_falou_incerto": "gravação mono: não dá para ter certeza de quem disse uma fala que pesa no sentimento",
    "cliente_falou_pouco": "o cliente quase não falou",
    "sinais_contraditorios": "sinais em sentidos opostos (ex.: agradece, mas continua reclamando)",
    "tom_nao_captado": "o sentimento depende do tom de voz (ironia, irritação contida), que o texto não mostra",
    "conversa_incompleta": "a ligação caiu ou foi transferida antes de dar para ver como o cliente terminou",
    "sem_conversa_com_cliente": "sem fala do cliente (ligação muda, só o atendente, ou conversa entre colegas)",
    "outro": "outro motivo (explicado no detalhe)",
}
MOTIVOS_SERVIDOR = {
    "sem_gravacao": "sem gravação (não existe ou já foi apagada no Native): não há o que avaliar (valor padrão neutro)",
    "sem_transcricao": "a transcrição falhou: não há o que avaliar (valor padrão neutro)",
    "ia_falhou": "a análise da IA falhou: não há avaliação (valor padrão neutro)",
    "transcricao_cortada": "a transcrição é longa demais e o meio da conversa não foi para a IA",
    "sem_fala": "nenhuma fala detectada na gravação",
    "motivo_nao_informado": "a IA baixou a confiança sem dizer por quê",
}
_MOTIVOS = {"type": "ARRAY", "description": "Obrigatório quando a confiança é menor que 100: todos os motivos.",
            "items": {"type": "OBJECT", "properties": {
                "motivo": {"type": "STRING", "enum": list(MOTIVOS_SENTIMENTO)},
                "detalhe": {"type": "STRING", "description": "O que exatamente, citando os segundos ou as falas."}},
                "required": ["motivo", "detalhe"]}}
_CAMPOS_SENTIMENTO = {
    "sentimento_inicio": _SENT,
    "sentimento_fim": _SENT,
    "sentimento": dict(_SENT, description="Sentimento do cliente na ligação como um todo (pese mais o fim)."),
    "sentimento_confianca": {"type": "INTEGER", "description": "0 a 100: certeza sobre o sentimento geral."},
    "sentimento_motivos": _MOTIVOS,
}
SCHEMA = {
    "type": "OBJECT",
    "properties": {
        "resumo": {"type": "STRING", "description": "2 a 4 frases: o que o cliente queria, o que foi feito, como terminou."},
        "motivo_contato": {"type": "STRING", "description": "Motivo em poucas palavras."},
        "categoria": {"type": "STRING", "enum": [
            "suporte_tecnico", "financeiro_cobranca", "segunda_via_pagamento", "vendas_contratacao", "upgrade_plano",
            "cancelamento", "mudanca_endereco", "troca_titularidade", "agendamento_visita", "reclamacao", "informacao",
            "interna_colegas", "outro"]},
        "resolvido": {"type": "STRING", "enum": ["sim", "nao", "parcial", "encaminhado", "indefinido"]},
        **_CAMPOS_SENTIMENTO,
        "satisfacao_estimada": {"type": "INTEGER", "description": "1 (muito insatisfeito) a 5 (muito satisfeito)"},
        "risco_cancelamento": _SN,
        "ligacao_interna": {"type": "BOOLEAN", "description": "Conversa entre colegas/técnicos, não com cliente."},
        "regra_mudo": {"type": "STRING", "enum": ["sim", "nao", "inconclusivo"]},
        "regra_mudo_justificativa": {"type": "STRING", "description": "1 a 2 frases citando os segundos."},
        "roteiro": {"type": "OBJECT", "properties": {
            "saudacao": _SN, "identificou_cliente": _SN, "informou_protocolo": _SN, "ofereceu_ajuda_adicional": _SN,
            "encerramento_cordial": _SN}, "required": ["saudacao", "identificou_cliente", "informou_protocolo",
                                                       "ofereceu_ajuda_adicional", "encerramento_cordial"]},
        "pontos_atencao": {"type": "STRING", "description": "O que poderia ter sido melhor (treinamento). Vazio se nada."},
        "palavras_chave": {"type": "ARRAY", "items": {"type": "STRING"}, "description": "Até 6."},
        "confianca": {"type": "NUMBER", "description": "0 a 1"},
    },
    "required": ["resumo", "motivo_contato", "categoria", "resolvido", *_CAMPOS_SENTIMENTO,
                 "satisfacao_estimada", "risco_cancelamento", "ligacao_interna", "regra_mudo", "regra_mudo_justificativa",
                 "roteiro", "pontos_atencao", "palavras_chave", "confianca"],
}
# Só o sentimento: para completar as ligações analisadas antes de ele existir, sem refazer o resto.
SCHEMA_SENTIMENTO = {"type": "OBJECT", "properties": _CAMPOS_SENTIMENTO, "required": list(_CAMPOS_SENTIMENTO)}

_QUEM = {"Origem": "o cliente", "Destino": "o atendente", "Transferida": "transferida para outro atendente"}


def definir_chave(chave):
    global CHAVE
    CHAVE = chave or CHAVE


def configurado():
    return bool(CHAVE)


def _transcricao(l):
    return "\n".join(f"{s['ini']:.0f}s {s['texto']}" for s in l.get("segmentos") or [])


def _texto(l):
    texto = _transcricao(l) or "(nenhuma fala detectada)"
    if len(texto) > MAX_CARACTERES:
        metade = MAX_CARACTERES // 2
        texto = texto[:metade] + "\n[…]\n" + texto[-metade:]
    cadeia = " -> ".join(l["cadeia"]) if l.get("cadeia") else "não houve"
    religou = f"sim, {l['religou_min']} min depois do fim desta" if l.get("religou_min") is not None else "não (em até 2 h)"
    return "\n".join([
        f"Atendente: {l['agente']}", f"Fila: {l['fila']}", f"Data/hora: {l['data_hora']}",
        f"Espera: {l.get('espera_seg')} s · Atendimento: {l.get('atendimento_seg')} s · Áudio: {l.get('duracao_audio')} s",
        f"Quem encerrou: {_QUEM.get(l.get('desconexao'), l.get('desconexao') or 'desconhecido')}",
        f"Cadeia de transferência: {cadeia}", f"Cliente ligou de novo: {religou}",
        f"Marcas: {'; '.join(l.get('marcas') or []) or 'nenhuma'}",
        f"Transcrição (Whisper {l.get('modelo_stt') or '?'}; o modelo small erra mais que o medium):", texto])


def ajustar_sentimento(x, l):
    """Garante o contrato do sentimento: sempre um valor, confiança de 0 a 100 e, abaixo de 100, os motivos
    explícitos. Soma o que o servidor sabe e a IA não vê (sem fala, transcrição cortada); a confiança nunca fica maior
    do que esses motivos permitem."""
    motivos = [{"motivo": m["motivo"], "detalhe": (m.get("detalhe") or "").strip()[:300]}
               for m in x.get("sentimento_motivos") or [] if isinstance(m, dict) and m.get("motivo")]
    try:
        conf = max(0, min(100, round(float(x.get("sentimento_confianca")))))
    except (TypeError, ValueError):
        conf = 0
        motivos.append({"motivo": "motivo_nao_informado", "detalhe": "a IA não informou a confiança"})
    if x.get("sentimento") not in SENTIMENTOS:
        fim = x.get("sentimento_fim")
        x["sentimento"] = fim if fim in SENTIMENTOS else "neutro"
        conf = min(conf, 30)
        motivos.append({"motivo": "outro", "detalhe": "a IA não deu o sentimento geral: vale o do fim da ligação"
                        if fim in SENTIMENTOS else "a IA não deu o sentimento: valor padrão neutro"})
    for campo in ("sentimento_inicio", "sentimento_fim"):
        if x.get(campo) not in SENTIMENTOS:
            x[campo] = x["sentimento"]
    texto = _transcricao(l)
    if not texto.strip():
        conf = min(conf, 10)
        motivos.append({"motivo": "sem_fala", "detalhe": MOTIVOS_SERVIDOR["sem_fala"]})
    elif len(texto) > MAX_CARACTERES:
        conf = min(conf, 90)
        motivos.append({"motivo": "transcricao_cortada", "detalhe": f"a transcrição tem {len(texto)} caracteres; só "
                        f"os primeiros e os últimos {MAX_CARACTERES // 2} foram para a IA"})
    if conf < 100 and not motivos:
        motivos.append({"motivo": "motivo_nao_informado", "detalhe": MOTIVOS_SERVIDOR["motivo_nao_informado"]})
    x.update(sentimento_confianca=conf, sentimento_motivos=motivos)
    return x


def analisar(l, so_sentimento=False):
    """Devolve (resultado, modelo). Levanta exceção se o Gemini não responder depois das tentativas.
    so_sentimento: pede só os campos do sentimento (ligações analisadas antes de ele existir)."""
    corpo = json.dumps({
        "contents": [{"role": "user", "parts": [{"text": f"{INSTRUCOES}\n\nDADOS DA LIGAÇÃO\n{_texto(l)}\n\nResponda só o JSON."}]}],
        "generationConfig": {"temperature": 0.1, "responseMimeType": "application/json",
                             "responseSchema": SCHEMA_SENTIMENTO if so_sentimento else SCHEMA},
    }).encode()
    req = urllib.request.Request(API.format(modelo=MODELO), data=corpo, method="POST",
                                 headers={"Content-Type": "application/json", "x-goog-api-key": CHAVE})
    ultimo = None
    for espera in (0,) + ESPERAS:
        if espera:
            time.sleep(espera)
        try:
            with urllib.request.urlopen(req, timeout=180) as r:
                resp = json.load(r)
            partes = resp["candidates"][0]["content"]["parts"]
            x = json.loads("".join(p.get("text", "") for p in partes if not p.get("thought")))
            return ajustar_sentimento(x, l), MODELO
        except urllib.error.HTTPError as e:
            ultimo = f"HTTP {e.code}: {e.read()[:300].decode(errors='replace')}"
            if e.code not in (429, 500, 502, 503, 504):
                break  # chave/modelo errado: não adianta repetir
        except (urllib.error.URLError, TimeoutError, KeyError, ValueError) as e:
            ultimo = f"{type(e).__name__}: {e}"[:300]
    raise RuntimeError(ultimo or "sem resposta")
