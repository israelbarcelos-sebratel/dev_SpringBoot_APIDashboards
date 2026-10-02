# Ajudante com GPU do histórico (app/gpu.py): mesmo código do container do servidor, com as bibliotecas CUDA 12 /
# cuDNN 9 que o faster-whisper (CTranslate2) usa na placa NVIDIA. Build a partir de analise-ligacoes/ (ver compose).
FROM python:3.11-slim
RUN apt-get update && apt-get install -y --no-install-recommends tzdata && rm -rf /var/lib/apt/lists/*
ENV PYTHONUNBUFFERED=1 PIP_NO_CACHE_DIR=1 TZ=America/Sao_Paulo HF_HOME=/modelos
WORKDIR /srv
COPY requirements.txt .
RUN pip install -r requirements.txt nvidia-cublas-cu12 "nvidia-cudnn-cu12==9.*"
ENV LD_LIBRARY_PATH=/usr/local/lib/python3.11/site-packages/nvidia/cublas/lib:/usr/local/lib/python3.11/site-packages/nvidia/cudnn/lib
COPY app ./app
CMD ["python", "-m", "app.gpu"]
