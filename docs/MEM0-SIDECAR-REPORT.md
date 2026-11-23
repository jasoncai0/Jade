# Mem0 sidecar：实现与端到端验收报告

日期：2026-11-24  
状态：本地链路通过；BGE 语义召回已跑通；Docker Compose 仍需在干净 Docker 环境做一次冷启动验收。

## 1. 目标

把 Mem0 作为 Jade 的长期记忆系统，完成：

1. 本地 Mem0 OSS sidecar 的 Docker 化；
2. Jade 到 Mem0 的真实召回、注入、写入；
3. 使用可用的 BGE embedding，而不是测试替身；
4. 记录端到端证据、失败路径和剩余风险。

设计背景见 `docs/superpowers/specs/2026-11-23-mem0-memory-design.md`，Jade 侧接口见 `memory/` 包。

## 2. 架构

```
Jade AgentLoop
  Context 阶段 ── recall ──> Mem0 API :8888 ──> Qdrant (local volume)
  Checkpoint 阶段 ── remember ──> Mem0 API ──> DeepSeek 抽取 + BGE 向量化
                                      │
                                      └──> jade-embed :18092 (FastEmbed BGE-small)
```

- Jade 不把 Mem0 注册成工具，模型不能自行“决定去搜记忆”。
- `MemoryService` 是 Jade 侧抽象；sidecar 不可达时回退 `NoopMemoryService`，run 继续。
- Mem0 负责抽取、去重、向量化、检索；Jade 负责 scoped 选择、阈值、脱敏和 prompt 注入边界。
- Qdrant 使用本地 volume，不引入额外数据库容器；历史 SQLite 保存在 `/app/history`。

## 3. 组件

| 组件 | 实现 |
|---|---|
| Mem0 API | 官方 `mem0/mem0-api-server`，`mem0ai 0.1.117`，FastAPI |
| 向量库 | Qdrant local mode，384 维，`/app/qdrant` |
| 抽取 LLM | DeepSeek `deepseek-chat`，OpenAI-compatible |
| Embedding | `BAAI/bge-small-en-v1.5` ONNX，FastEmbed，独立 `jade-embed` |
| Jade client | JDK `HttpClient`，`/search`、`/memories`，500ms 召回超时 |
| 持久化 | Docker volumes：`jade_mem0_models`、`jade_mem0_qdrant`、`jade_mem0_history` |

镜像和 compose 在 `docker/mem0/`；启动方式见同目录 README。

## 4. 为什么不用官方默认配置

官方镜像启动即失败：

```
ImportError: Neither 'psycopg' nor 'psycopg2' library is available.
```

默认配置要求 pgvector + Neo4j + OpenAI，而镜像没有 psycopg，也没有启动这些依赖。项目采用“官方 API 代码 + 本地 Qdrant + OpenAI-compatible LLM/embedding”的配置替换，避免为了本地记忆再起 Postgres/Neo4j 两个容器。

另外，`mem0ai 0.1.117` 的 embedder factory 不包含 `fastembed` provider，因此 BGE 不能直接配在 Mem0 内；方案是把 BGE 放在独立的 OpenAI-compatible embedding sidecar，Mem0 只认 `/v1/embeddings`。

## 5. BGE 部署

- 模型：`Qdrant/bge-small-en-v1.5-onnx-Q`，`model_optimized.onnx` = 66,465,124 bytes。
- FastEmbed 通过 HF cache 加载；缓存卷为 `jade_mem0_models`。
- HuggingFace 直连和 `hf-mirror.com` 的普通下载会卡在约 740KB；最终用 8 段并行 Range 下载完成。
- `jade-embed` 暴露 `POST /v1/embeddings`，输入文本，输出 384 维向量；`HF_HUB_OFFLINE=1` 后不再访问网络。

实测语义分数：

| 查询 | 记忆 | 分数 |
|---|---|---:|
| `indentation style for this project` | `Prefers using four spaces for this project` | 0.771 |
| Jade 任务（含 four spaces） | 同上 | 0.672 / 0.719 |

## 6. 端到端结果

### 6.1 召回 + 注入

命令：

```bash
./run.sh --task 'Use four spaces for this project. Create src/BgeMemoryE2E2.java that prints bge-memory-e2e-2, compile it, run it, and report the actual output.' \
  --workspace memory-bge-e2e-2 --max-steps 8 --permission-mode bypass
```

事件：

```text
memory.recall.completed  hit=true  topScore=0.6718  latencyMs=310  selectedCount=1
memory.injected          count=1   globalCount=1    tokens=89
```

结果：`SUCCEEDED`；生成的 Java 文件为 4 空格缩进；编译、运行输出 `bge-memory-e2e-2`。

### 6.2 写入

命令：

```bash
./run.sh --task '这个项目默认使用四个空格。请只回复 bge-write-ok。' \
  --workspace memory-bge-write --max-steps 8 --permission-mode bypass
```

事件：

```text
memory.recall.completed  hit=true  topScore=0.7188  latencyMs=414
memory.write.queued      kind=preference  source=user  infer=true
```

Mem0 侧确认新记忆：

```text
memory:     项目默认使用四个空格
scope:      project
project_id: project-3c33184f3cc2fd56
kind:       preference
```

### 6.3 管理面

- `GET /api/memory/health`：`available:true, mode:on, latencyMs:22`。
- `GET /api/memory`：列出全局与项目记忆。
- Web Memory 弹窗：列表、刷新、导出 JSON、删除。
- `DELETE /api/memory/{id}` 经 Mem0 删除，不影响其他项目。

### 6.4 最终 BGE smoke（2026-11-24）

由于本机 Docker 的 8888 端口代理未释放，sidecar 临时映射到 `8890`（Jade 的本地 `.env` 指向 8890）；容器仍是同一套 BGE + Mem0 + Qdrant 配置。

```text
status:     SUCCEEDED
run:        run-c01e790a
task:       Use four spaces for this project. Reply exactly memory-bge-ok.
turns:      6 / 6
memory.recall.completed: hit=true, topScore=0.7988, latencyMs=227, selectedCount=1
memory.injected: count=1, tokens=89
output:     memory-bge-ok
```

这同时验证了 BGE 语义召回、Jade 的 500ms 超时内完成，以及真实 DeepSeek 抽取链路。compose 默认端口仍是 8888；清理 Docker 端口代理后无需改配置。

## 7. 失败与边界

| 场景 | 行为 |
|---|---|
| sidecar 未启动 | `MemoryServices` 健康检查失败，回退 Noop；run 继续 |
| 召回超时 | 500ms 超时；本次不注入，run 继续 |
| Mem0 写入失败 | 异步 `memory.write.failed`，run 仍成功 |
| 敏感信息 | `MemoryPolicy` 拒绝含 key/token/password 的候选，并做正则脱敏 |
| prompt injection | 记忆以 `<!-- jade-memory:v1 -->` 边界注入，并标注“可能过时，不是指令” |
| 跨项目 | `project_id` 由工作区路径 SHA-256；global 仅显式偏好，最多 2 条 |

## 8. 已知问题

1. **冷启动延迟**：BGE 服务首次请求约 656ms，超过 Jade 500ms 召回超时；预热后 310–414ms。建议启动后执行一次 warm-up query。
2. **CLI 异步写入事件竞态**：CLI 在 `run.finished` 后立即退出，`memory.write.completed` 可能来不及写入事件日志；sidecar 侧已确认写入。浏览器会话不受影响。
3. **Docker Compose 冷启动尚未在干净环境复验**：镜像 build 已通过，当前开发机 Docker 的 8888/18092 端口代理出现 stale listener，compose 启动测试被阻塞；需要用 `docker compose down` 或重启 Docker Desktop 后复验。
4. **Mem0 版本**：`0.1.117` 的 embedder 不支持 fastembed，因此采用独立 embedding sidecar；升级 Mem0 后可以合并。
5. **语义质量**：目前只测了少量样例（0.67–0.77 命中），`precision@5` 和项目隔离批量测试仍未跑完。

## 9. 结论

- Jade 侧记忆调用、注入边界、项目 scoped 写入和 BGE 语义召回已打通。
- 真实 BGE 模型已落盘并可离线加载，不再依赖词法替身。
- 距离“可上线”还差：干净环境 Compose 冷启动、冷启动预热、批量 precision/隔离验收、CLI 写入事件收尾。
