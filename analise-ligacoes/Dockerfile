# Análise das ligações do dia (regra Mute / "linha com problema"): Whisper local + regras + API para o n8n.
FROM python:3.11-slim
RUN apt-get update && apt-get install -y --no-install-recommends tzdata && rm -rf /var/lib/apt/lists/*
ENV PYTHONUNBUFFERED=1 PIP_NO_CACHE_DIR=1 TZ=America/Sao_Paulo HF_HOME=/modelos
WORKDIR /srv
COPY requirements.txt .
RUN pip install -r requirements.txt
COPY app ./app
EXPOSE 8000
HEALTHCHECK --interval=30s --timeout=5s --start-period=60s CMD python -c "import urllib.request;urllib.request.urlopen('http://localhost:8000/saude',timeout=4)"
CMD ["uvicorn", "app.main:app", "--host", "0.0.0.0", "--port", "8000"]
