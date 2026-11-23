FROM mem0/mem0-api-server:latest

# FastEmbed is used by the embedding sidecar; the Mem0 API itself uses the
# OpenAI-compatible endpoint exposed by that sidecar.
ARG PIP_INDEX_URL=https://pypi.org/simple
RUN pip install --no-cache-dir --default-timeout=120 --retries 5 \
      -i "${PIP_INDEX_URL}" fastembed

COPY patch_main.py /app/patch_main.py
COPY embed_server.py /app/embed_server.py
RUN python /app/patch_main.py \
 && mkdir -p /app/qdrant /app/history /models

ENV FASTEMBED_CACHE_PATH=/models
