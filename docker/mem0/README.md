# Jade Mem0 sidecar

本目录是 Jade 的本地 Mem0 OSS 运行层。Jade 通过 REST 调用 Mem0；Mem0 自己负责抽取、向量化和检索，Jade 不把 Mem0 挂成 `ToolRegistry` 工具。

## 结构

- `Dockerfile`：官方 `mem0/mem0-api-server` + FastEmbed。
- `patch_main.py`：把官方镜像的 pgvector/Neo4j/OpenAI 默认配置替换为本地 Qdrant + OpenAI 兼容 LLM/embedder。
- `embed_server.py`：FastEmbed BGE-small 的 OpenAI `/v1/embeddings` 兼容服务，固定 384 维。
- `compose.yaml`：`mem0` API + `jade-embed` embedding sidecar。

## 启动

```bash
cd java-coding-agent
cp .env.example .env        # 已有可跳过
# 确保 DEEPSEEK_API_KEY 已配置
cd docker/mem0
docker compose build
docker compose up -d
```

Jade 侧：

```bash
AGENT_MEMORY_MODE=on
AGENT_MEMORY_URL=http://127.0.0.1:8888
```

确认：

```bash
curl http://127.0.0.1:8888/memories?user_id=jade-local-user
curl http://127.0.0.1:8081/api/memory/health
```

## BGE 模型缓存

`jade-embed` 使用 FastEmbed 的 `BAAI/bge-small-en-v1.5`（ONNX，384 维）。模型放在 Docker volume `jade_mem0_models` 的 `/models`。

首次启动时 FastEmbed 会从 HuggingFace 下载。网络受限时可设置：

```bash
HF_ENDPOINT=https://hf-mirror.com docker compose up -d
```

若下载仍卡住，需要先把下面 7 个文件放进缓存目录：

```text
/models/models--Qdrant--bge-small-en-v1.5-onnx-Q/
  blobs/51f1bd0addd6e859e42c2c8021a5e5461385bb676a649f4b269aa445449f2431   # model_optimized.onnx
  snapshots/aa8f8b060edb00e03bfdd08813a2949946c8ba55/
    config.json
    model_optimized.onnx -> ../../blobs/51f1bd0ad...
    ort_config.json
    special_tokens_map.json
    tokenizer.json
    tokenizer_config.json
    vocab.txt
```

模型 ONNX 的官方地址：

```text
https://huggingface.co/Qdrant/bge-small-en-v1.5-onnx-Q/resolve/main/model_optimized.onnx
```

本机验证记录：模型 66,465,124 字节；用 `hf-mirror.com` 分段并行下载后可离线加载。

## 配置

- LLM：`MEM0_LLM_BASE_URL`（默认 `https://api.deepseek.com`）、`MEM0_LLM_MODEL`（`deepseek-chat`）。
- Embedding：`MEM0_EMBED_BASE_URL`（默认 `http://jade-embed:18092/v1`）。
- Qdrant：容器内 `/app/qdrant`，volume `mem0_qdrant`。
- 历史：`/app/history`，volume `mem0_history`。
- 认证：默认仅绑定 `127.0.0.1:8888`，无 API key；不要暴露公网。

## 边界

这是本地单用户 sidecar，不是多租户服务。Jade 侧仍负责项目隔离、敏感信息过滤、召回阈值和注入边界；Mem0 不可用时 Jade 回退 `NoopMemoryService`，run 不失败。
