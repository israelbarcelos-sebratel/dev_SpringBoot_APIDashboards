"""Pedido ao Gemini (generateContent) para uma ligação suspeita. O n8n só repassa `geminiRequest` e devolve a
resposta em POST /analises/{data}/avaliacoes — o prompt e o formato da resposta ficam versionados aqui."""
import json

REGRA = """Você é analista de qualidade do call center da Sebratel (provedor de internet). Avalie UMA ligação quanto à \
regra "Mute / linha com problema": o atendente coloca a ligação no mudo, fica em silêncio ou alega problema na linha \
("não estou te ouvindo", "está cortando") para encerrar a chamada ou empurrar o cliente para outro canal (WhatsApp, \
"liga de novo"), quando o problema não é real — inclusive por falta de paciência (ex.: cliente distraído falando com \
outras pessoas).

Como ler os dados:
- A transcrição foi feita por IA (Whisper) a partir de gravação MONO: atendente e cliente estão juntos e as falas NÃO \
dizem quem falou — deduza pelo conteúdo. Há erros de transcrição (palavras trocadas, trechos truncados).
- O número no começo de cada linha é o segundo desde que o atendente atendeu. "[…]" = trecho não transcrito (nas \
ligações longas só o começo e o fim são transcritos).
- CPF, telefones, e-mails e números longos foram mascarados.
- "Marcas" são sinais automáticos por regra e podem ser falso positivo.

NÃO é a regra: problema de linha real em que a conversa continua; ligação entre colegas/técnicos; transferência para \
outro setor; cliente que desliga sozinho depois de um atendimento normal; ligação muda do lado do cliente (ninguém fala \
e o cliente desliga, sem sinal de que o atendente ouviu algo).
Sinais fortes: o cliente diz que está ouvindo e mesmo assim o atendente encerra dizendo que não ouve; o atendente atende, \
não fala nada e derruba; o cliente liga de novo logo em seguida (precisava do atendimento).
Na dúvida, responda "inconclusivo" e precisa_ouvir = true. Nunca invente falas que não estão na transcrição."""

SCHEMA = {
    "type": "OBJECT",
    "properties": {
        "enquadra": {"type": "STRING", "enum": ["sim", "nao", "inconclusivo"],
                     "description": "A ligação se enquadra na regra Mute / linha com problema?"},
        "confianca": {"type": "NUMBER", "description": "0 a 1"},
        "justificativa": {"type": "STRING", "description": "Até 3 frases, citando os segundos da transcrição."},
        "trecho_chave": {"type": "STRING", "description": "O trecho que sustenta a decisão, copiado da transcrição."},
        "sentimento_cliente": {"type": "STRING", "enum": ["positivo", "neutro", "negativo", "indefinido"]},
        "motivo_contato": {"type": "STRING", "description": "Motivo do contato em poucas palavras."},
        "precisa_ouvir": {"type": "BOOLEAN", "description": "Um supervisor precisa ouvir a gravação para decidir?"},
    },
    "required": ["enquadra", "confianca", "justificativa", "trecho_chave", "sentimento_cliente", "motivo_contato",
                 "precisa_ouvir"],
}

_QUEM = {"Origem": "o cliente", "Destino": "o atendente", "Transferida": "transferida para outro atendente"}


def pedido(l):
    """l: linha de analise_ligacao já com marcas/cadeia/trecho decodificados."""
    cadeia = " -> ".join(l["cadeia"]) if l.get("cadeia") else "não houve"
    religou = f"sim, {l['religou_min']} min depois do fim desta" if l.get("religou_min") is not None else "não (em até 2 h)"
    dados = "\n".join([
        f"Atendente: {l['agente']}",
        f"Fila: {l['fila']}",
        f"Hora: {l['hora']}",
        f"Tempo de atendimento: {l['seg']} s" if l.get("seg") is not None else "Tempo de atendimento: desconhecido",
        f"Quem encerrou: {_QUEM.get(l.get('desconexao'), l.get('desconexao') or 'desconhecido')}",
        f"Cadeia de transferência (a gravação segue com quem recebeu): {cadeia}",
        f"Cliente ligou de novo: {religou}",
        f"Marcas: {'; '.join(l.get('marcas') or []) or 'nenhuma'}",
        "Transcrição do trecho deste atendente:",
        "\n".join(l.get("trecho") or []) or "(nenhuma fala detectada)",
    ])
    return {
        "contents": [{"role": "user", "parts": [{"text": f"{REGRA}\n\nDADOS DA LIGAÇÃO\n{dados}\n\nResponda só o JSON."}]}],
        "generationConfig": {"temperature": 0.1, "responseMimeType": "application/json", "responseSchema": SCHEMA},
    }


def ler_resposta(corpo):
    """Aceita o JSON já extraído ({enquadra, ...}) ou a resposta bruta do generateContent."""
    if isinstance(corpo, str):
        corpo = json.loads(corpo)
    if "candidates" in corpo:
        partes = corpo["candidates"][0]["content"]["parts"]
        corpo = json.loads("".join(p.get("text", "") for p in partes if not p.get("thought")))
    return corpo
