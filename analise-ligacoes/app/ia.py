"""Entendimento de uma conversa inteira pelo Gemini (histórico). Só vai o TEXTO já mascarado — o áudio nunca sai
do servidor. Chave e modelo vêm da stack: GEMINI_API_KEY, GEMINI_MODELO."""
import json
import os
import time
import urllib.error
import urllib.request

API = "https://generativelanguage.googleapis.com/v1beta/models/{modelo}:generateContent"
CHAVE = os.environ.get("GEMINI_API_KEY", "")
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

Na dúvida use "indefinido"/"inconclusivo". Nunca invente falas que não estão na transcrição."""

_SN = {"type": "BOOLEAN"}
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
        "sentimento_inicio": {"type": "STRING", "enum": ["positivo", "neutro", "negativo", "indefinido"]},
        "sentimento_fim": {"type": "STRING", "enum": ["positivo", "neutro", "negativo", "indefinido"]},
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
    "required": ["resumo", "motivo_contato", "categoria", "resolvido", "sentimento_inicio", "sentimento_fim",
                 "satisfacao_estimada", "risco_cancelamento", "ligacao_interna", "regra_mudo", "regra_mudo_justificativa",
                 "roteiro", "pontos_atencao", "palavras_chave", "confianca"],
}

_QUEM = {"Origem": "o cliente", "Destino": "o atendente", "Transferida": "transferida para outro atendente"}


def configurado():
    return bool(CHAVE)


def _texto(l):
    linhas = [f"{s['ini']:.0f}s {s['texto']}" for s in l.get("segmentos") or []]
    texto = "\n".join(linhas) or "(nenhuma fala detectada)"
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
        "Transcrição:", texto])


def analisar(l):
    """Devolve (resultado, modelo). Levanta exceção se o Gemini não responder depois das tentativas."""
    corpo = json.dumps({
        "contents": [{"role": "user", "parts": [{"text": f"{INSTRUCOES}\n\nDADOS DA LIGAÇÃO\n{_texto(l)}\n\nResponda só o JSON."}]}],
        "generationConfig": {"temperature": 0.1, "responseMimeType": "application/json", "responseSchema": SCHEMA},
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
            return json.loads("".join(p.get("text", "") for p in partes if not p.get("thought"))), MODELO
        except urllib.error.HTTPError as e:
            ultimo = f"HTTP {e.code}: {e.read()[:300].decode(errors='replace')}"
            if e.code not in (429, 500, 502, 503, 504):
                break  # chave/modelo errado: não adianta repetir
        except (urllib.error.URLError, TimeoutError, KeyError, ValueError) as e:
            ultimo = f"{type(e).__name__}: {e}"[:300]
    raise RuntimeError(ultimo or "sem resposta")
