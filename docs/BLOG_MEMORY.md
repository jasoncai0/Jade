# Jade 踩坑记忆

> 项目级记忆，仅保存在本仓库。不要复制到全局 `~/.dsh/AGENTS.md`。
> 用途：记录搭建 coding agent 时遇到的问题，并同步到博客 Jade 系列。

## 文章元数据

| 字段 | 值 |
|---|---|
| 标题 | 从能跑到跑对：搭一个本地 Coding Agent 踩过的坑 |
| slug | `coding-agent-pitfalls` |
| 线上地址 | https://www.jasoncai.site/posts/coding-agent-pitfalls.html |
| 博客源码 | `~/Documents/deepseek-harness/default-workspace/blog/content/posts/coding-agent-pitfalls/` |
| 标签 | `harness`, `codegen`, `architecture` |
| 发布状态 | 已发布（系列拆分 2026-11-16 18:33） |
| 项目复盘 | `docs/RETROSPECTIVE.md` |

## 系列文章（2026-11-16 拆分）

| 篇目 | slug | 内容 | 线上 |
|---|---|---|---|
| 总览 | `coding-agent-pitfalls` | 结论、系统结构、检查清单、六个设计判断 | https://www.jasoncai.site/posts/coding-agent-pitfalls.html |
| 循环护栏 | `coding-agent-loop` | MAX_STEPS、反思机制、工具发现、验证门 | https://www.jasoncai.site/posts/coding-agent-loop.html |
| 执行环境 | `coding-agent-sandbox` | SDK 污染、沙箱边界、Maven/Spring Boot、多语言验证 | https://www.jasoncai.site/posts/coding-agent-sandbox.html |
| 会话与历史 | `coding-agent-sessions` | 恢复、连续追问、权限、MySQL 分页、压缩、Trace | https://www.jasoncai.site/posts/coding-agent-sessions.html |

## 踩坑账本

| # | 日期 | 领域 | 症状 | 根因 | 修法 | 博客章节 |
|---|---|---|---|---|---|---|
| 1 | 2026-11-14 | 循环终止 | Go 快排 `MAX_STEPS`，但 `go build/test/run` 全过 | 10 轮用尽，模型没来得及写最终回答 | 调到 12 轮复跑成功；区分终止状态与工件结论 | 循环 1 |
| 2 | 2026-11-14 | 工作区污染 | `gofmt -l .` 扫到 Go SDK 坏样例退出 2；验证器把 SDK 里的 JS 当项目源码 | 模型把 Go SDK 下载到 `.toolchain/go`；源码扫描未跳过 SDK 目录 | `.toolchain`/`.sdk` 等加入 `SKIP_DIRS`；禁止在工作区安装 SDK | 环境 1 |
| 3 | 2026-11-15 | 沙箱 | Spring Boot `Maven compile failed`，报 `~/.m2/...: Operation not permitted` | 写沙箱只允许工作区；Maven 缓存、临时目录、网络、端口均未建模 | 预热缓存；`java.io.tmpdir` 指回工作区；按需开网络；Jade 退到 8081 | 环境 2 |
| 4 | 2026-11-14 | 多语言验证 | `node --check a.js b.js` 只检查第一个文件 | Node 参数语义 | 每个 `.js` 单独执行；补“合法 a + 非法 b”测试 | 环境 3.1 |
| 5 | 2026-11-14 | 语言检测 | 工作区被 SDK 的 `.js` 污染后误选 JavaScript | 按扩展名数量投票，没有隔离依赖/SDK 目录 | 只扫描项目文件；允许显式指定 language | 环境 3.2 |
| 6 | 2026-11-14 | Go 构建 | 无 `go.mod` 时 `go build ./...` 失败 | Go 1.27 默认模块模式 | 自动生成最小 `go.mod`；老项目兼容 `GO111MODULE=off` | 环境 3.3 |
| 7 | 2026-11-14 | 工具发现 | ServiceLoader 测试编译失败，找不到 `EchoToolProvider` | 服务文件里的 provider 名与实际嵌套类不一致 | 改成 `EchoProvider`；加发现数量和重名冲突测试 | 循环 2 |
| 8 | 2026-11-14 | 验证门 | 编译成功即标 verified，运行失败仍可能 `SUCCEEDED` | `run_java` 在进程启动前就置 verified | 建议运行 exit 0 才算；当前仍未完全修完，文章标记为 open limitation | 循环 3 |
| 9 | 2026-11-14 | 会话恢复 | 重启后 `/api/runs` 为空；事件日志缺 `run.finished` | 只存在内存；先持久化后发终止事件 | 恢复 transcript/events；最后再持久化一次；RUNNING 改 FAILED | 会话 1 |
| 10 | 2026-11-15 | Trace | 请求体包含完整上下文，展开后可能泄露敏感内容 | 只防了 Authorization，没防 body | 默认折叠；不写普通日志；run 目录按敏感数据管理 | 会话 2 |
| 11 | 2026-11-15 | 连续会话 | 第一轮结束后无法追问，SSE 已断开 | `run.finished` 被当成会话终点；`AgentLoop` 每轮关闭事件日志 | 日志保留到服务停机；`run.finished` 按轮发；新增 `turn.started`；UI 不断 SSE、按 `lastSeq` 续连 | 会话 1.1 |
| 12 | 2026-11-15 | 前端状态 | 空状态下 Start 按钮被禁用 | 初始 `status` 未定义，busy 判断把空状态当运行中 | 初始状态 `IDLE`；busy 仅在选中会话且 RUNNING/QUEUED 时成立 | 会话 1.1 |
| 13 | 2026-11-15 | 权限 UI | 后端 `WAITING_APPROVAL`、API 有 pending，页面看不到审批按钮 | 卡片 append 在 Live Trace 滚动区末尾；prepend 后仍可能落在 950px 视口下方 | 卡片移出时间线，挂到 `document.body`，固定底部 overlay；刷新补拉 pending | 会话 1.2 |
| 14 | 2026-11-15 | 沙箱默认 | Spring Boot 已生成并通过测试，启动仍 `SocketException: Operation not permitted`；`/health` HTTP 000 | `AGENT_SANDBOX=auto` 默认开启写监狱，`AGENT_SANDBOX_NETWORK=0` 在进程创建时锁死网络；Maven 缓存/JVM temp 也受写限制 | 默认改 `off` 本地直接执行；需要隔离时显式 `auto`/`required`；同一 Spring Boot 会话复跑，8080 `/health` 200 | 环境 2.1 |
| 15 | 2026-11-16 | 长任务轮次 | 10/30 轮上限会截断已经完成的长任务，也会把“没写完最终回答”误判成失败 | 用固定模型轮数当唯一护栏，无法区分正常长任务和失控循环 | 默认 `AGENT_MAX_STEPS=0` 不限制轮数；累计 token 预算默认 0（可选，正数才硬停）和同名同参数连续 4 次的 `ToolLoopGuard` 兜底；正数上限保留 | 循环 1 |
| 16 | 2026-11-16 | 长任务失控 | 取消默认预算后，旧会话连续 15 分钟、641 步翻 `javap` 字节码；96 MB events；Jade 被 SIGKILL | 重复检测只比完整参数，`sed -n 60,80`/`80,100` 每轮不同；token 预算为 0；没有“无文件变化”判据 | 增加 20 轮无进展护栏：文件列表未变且无新验证即 `LOOP_DETECTED`；不恢复 token 预算 | 循环 1 |

| 17 | 2026-11-16 | 命令形状循环绕过 | `javap` 只改 `sed -n '60,80p'`/`'80,100p'`，20 轮无进展护栏前已烧 96 MB events | 完整参数指纹不同；数字变化不归一；无反思请求 | 8 轮注入 reflection 检查点；16 轮停止；`run_command` 形状归一化（数字→`#`），10 次窗口 8 次停止；新增 `reflect` 工具 | 循环 1.1 |
| 18 | 2026-11-16 | 长会话历史 | 96 MB / 12,786 事件的失败会话加载卡死；分页后 FAILED 变 RUNNING、token 回跳 | 启动即读全量 events；浏览器从 seq 0 重放全部事件；历史事件改写当前状态 | MySQL 存 snapshot+events；compact 分页 150；完整 body 按 seq 懒取；分页后重放 snapshot；改用已加载事件计数 | 会话 1.3 |
| 19 | 2026-11-16 | 一次性 CLI | 产物和最终回答都已完成，CLI 仍挂 9 分钟；主线程停在 `RunEventLog.awaitAfter` | 为支持连续会话，`run.finished` 不再关闭事件日志；CLI 只等 `closed()`，不知道当前 run 已终止 | CLI 在已消费完事件且快照 terminal 时退出；补 `goal.review` 输出。连续会话/服务端仍保持日志打开 | 会话 1.1 |
| 20 | 2026-11-16 | 上下文压缩 | 超预算但只剩三组时，截断结果被丢弃；旧对话被硬删，模型重复劳动 | `compacted` 只看 `removed > 0`；无 token 预算、无 pinned memory、无工具输出微压缩、无模型摘要 | token+字符双预算；system/首任务/最新用户组钉住；tool result 头尾压缩；旧组经无工具模型摘要回插；摘要失败退化说明；补截断生效测试 | 会话 1.4 |
| 21 | 2026-11-24 | Mem0 sidecar | 官方 `mem0/mem0-api-server:latest` 启动即报 `Neither 'psycopg' nor 'psycopg2' library is available` | 官方镜像默认配置依赖 pgvector + Neo4j + OpenAI，镜像本身不装 psycopg | 本地改为官方 API 代码 + Qdrant local + DeepSeek + OpenAI-compatible embedder；Dockerfile 在 `docker/mem0/` | Mem0 10.1 |
| 22 | 2026-11-24 | BGE embedding | Mem0 0.1.117 配置 `fastembed` 报 `Unsupported embedding provider`；HF 模型下载卡在约 740 KB | 该版本 embedder factory 不含 fastembed；HuggingFace 直连/镜像不稳定 | 单独起 `jade-embed` FastEmbed 服务，Mem0 走 OpenAI-compatible `/v1/embeddings`；用 8 段 Range 下载 66,465,124 字节 ONNX 并离线加载 | Mem0 10.2 |
| 23 | 2026-11-24 | Mem0 冷启动 | BGE 首次召回 656 ms，超过 Jade 500 ms 超时；预热后 310–414 ms | ONNX 首次加载/初始化成本 | sidecar 启动后先跑 warm-up query；或把首次超时单独放宽 | Mem0 10.3 |
| 24 | 2026-11-24 | 会话状态 | 成功任务重启后显示 `FAILED`；`jade_sessions` 仍是 `QUEUED`/`RUNNING`，但事件里已有 `run.succeeded` | CLI 在 `run.succeeded` 后立即退出并关闭 executor；最终快照原本只在 `executeTurn` finally 写入，`run.finished` 事件本身不落 session 快照 | `RunEventLog.emit("run.finished")` 在事件可见前先写终态快照；CLI 等到 `run.finished` 再退出；启动时用工作区 terminal 事件修复 stale 行；`SUCCEEDED` 清空重启错误 | 会话 1.5 |
| 26 | 2026-11-26 | Team wait 超时误判 | `wait_agent` 超时输出只有 `Timed out.`，Lead 可能误判成员卡死并重复 spawn/reassign | 观察窗口结束 ≠ 成员失败；返回结果没有区分 running/queued/completed/failed | 返回 `timedOut`、`waitedMillis` 和四组状态；超时输出明确禁止对活跃成员重派；Team policy 同步说明；新增阻塞成员测试 | Team 8.4 |
| 25 | 2026-11-24 | 记忆生命周期 | 长期记忆没有 TTL/时间衰减/supersede；旧偏好和新偏好可能同时召回 | 当前只做成功 run + 用户明确信号的准入，汰换只有手动删除；设计中的 expires_at/status/feedback 未实现 | 文档明确区分已实现与建议；推荐按 kind 设 TTL、last_verified、supersedes、过期先排除召回；补图 4 | Mem0 11 |

## 发布记录

| 日期 | 动作 | 结果 | 备注 |
|---|---|---|---|
| 2026-11-15 | 创建文章与 3 张图 | 构建通过 | 图：架构、MAX_STEPS 归因、沙箱边界 |
| 2026-11-15 16:59 | 发布 `coding-agent-pitfalls` | 线上 200，3 张图 200 | 发布备份 `/home/ubuntu/backups/blog-20261115-165933`；blog commit `36ca398` |
| 2026-11-15 17:15 | 补充连续会话与前端状态两坑 | 线上 200，8.1 节可见 | blog commit `f9e23ae`；基线 `73bb075`；备份 `/home/ubuntu/backups/blog-20261115-171539` |
| 2026-11-15 23:01 | 补充权限审批卡可见性坑 | 线上 200，8.2 节可见 | blog commit `0f8da6d`；备份 `/home/ubuntu/backups/blog-20261115-230109` |
| 2026-11-15 23:14 | 补充默认沙箱导致本地服务起不来 | 线上 200，4.1 节可见 | blog commit `487138a`；备份 `/home/ubuntu/backups/blog-20261115-231355` |
| 2026-11-16 13:24 | 补充默认取消轮次上限 | 线上 200，2 节更新可见 | blog commit `c8d2c3a`；备份 `/home/ubuntu/backups/blog-20261116-132408` |
| 2026-11-16 14:42 | 补充无进展循环护栏 | 线上 200，无进展段落可见 | blog commit `50456c6`；备份 `/home/ubuntu/backups/blog-20261116-144240` |
| 2026-11-16 13:41 | 说明 token 预算默认关闭 | 线上 200，2 节更新可见 | blog commit `195f831`；备份 `/home/ubuntu/backups/blog-20261116-134125` |
| 2026-11-16 14:32 | 增加最终经验复盘与六个设计判断 | 线上 200，11 节可见 | blog commit `0b0b068`；备份 `/home/ubuntu/backups/blog-20261116-143212` |

| 2026-11-16 16:24 | 补充长会话历史分页坑 | 线上 200，8.3 节可见 | blog commit `0ef478c`；基线 `b3aa363`；备份 `/home/ubuntu/backups/blog-20261116-162416` |
| 2026-11-16 17:43 | 补充上下文压缩管线 | 线上 200，8.4 节可见 | blog commit `9e8056d`；基线更新；备份 `/home/ubuntu/backups/blog-20261116-174350` |
| 2026-11-16 18:29 | 补充反思机制、循环护栏与一次性 CLI 坑 | 线上 200，2.1 节与图可见 | blog commit `d63bcfe`；基线更新；备份 `/home/ubuntu/backups/blog-20261116-182936` |
| 2026-11-16 18:33 | 拆分为总览 + 3 篇专题 | 4 篇线上 200；4 张图 200 | blog commit `ab88ee9`；备份 `/home/ubuntu/backups/blog-20261116-183316` |

| 2026-11-24 01:12 | 补充 Mem0 sidecar 落地与端侧验收 | 文章 200，图 3 200 | blog commit `b06f927`；备份 `/home/ubuntu/backups/blog-20261124-011205` |

| 2026-11-24 01:18 | 补充 BGE 最终 smoke 数据 | 文章 200 | blog commit `1018233`；备份 `/home/ubuntu/backups/blog-20261124-011836` |

| 2026-11-24 13:33 | 补充成功会话误标失败的持久化顺序坑 | 文章 200，1.5 节可见 | blog commit `b86c707`；备份 `/home/ubuntu/backups/blog-20261124-133319` |

| 2026-11-24 16:15 | 补充记忆准入、召回与汰换 | 文章 200，11 节与图 4 可见 | blog commit `a812d19`；备份 `/home/ubuntu/backups/blog-20261124-161344` |

| 2026-11-24 16:34 | 补充记忆实现代码位置与示例 | 文章 200，11.5 节与 GitHub 链接可见 | blog commit `d804ec5`、baseline `c30f772`；备份 `/home/ubuntu/backups/blog-20261124-163417` |

| 2026-11-24 16:37 | 标注偏好/项目约定的精确命中行号 | 11.1 节可见 7 个 GitHub 行链接 | blog commit `9ca5f23`；备份 `/home/ubuntu/backups/blog-20261124-163704` |
| 2026-11-26 01:13 | 补充 Team wait_agent 超时语义 | 文章 200，8.4 节可见 | blog commit `039fa9d`；备份 `/home/ubuntu/backups/blog-20261126-011333` |

## 待办

- [x] 构建 Hugo 并发布 `coding-agent-pitfalls`。
- [x] 发布后把“发布状态”改为“已发布”，记录线上复验结果。
- [ ] 后续新坑按账本格式追加，并同步文章章节。

## 更新规则

1. 只记录可验证的事实：命令、错误原文、run id、文件路径、commit。
2. 每条至少写清楚“症状 / 根因 / 修法”；没有修法则标为“未解决”。
3. 不在账本里写 API key、口令、用户隐私或大段工具输出。
4. 发布前跑 `python3 scripts/check-tags.py content/posts data/tags.yaml` 和 `scripts/publish.sh`。


