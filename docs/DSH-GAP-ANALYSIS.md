# DSH 与 Jade 能力差距分析

- 分析日期：2026-11-18（定时任务触发于 2026-11-17 12:00 Asia/Shanghai）
- Jade：`java-coding-agent`，HEAD `5772293`，工作树干净，108 个 Java 文件、10,740 行，73 个 `@Test`
- DSH：已安装 `@deepseek-ai/dsh 0.2.2-rc.3`；另有一份 `0.2.2-rc.1` 解包源码仅用于交叉核对，行号以 asar 安装版为准
- 性质：只读评估。本轮不改 Java/JS/Python、配置、博客、数据库；只写本文件与配套计划

## 0. 结论

Jade 的十阶段循环、MySQL 事件持久化、权限审批、验证门、Goal review 已经构成一个可用的本地 coding agent。按用户价值排序，最值得补的是五个会改变正确性和交互方式的核心机制：

1. **上下文安全网**：溢出后能压缩重试，工具输出能落盘保留尾部（P0）
2. **并行只读工具调用**：同一轮多个读操作不再排队（P0）
3. **真流式输出**：模型 token 边生成边显示（P1）
4. **子代理委派**：把只读调研丢给子上下文，父上下文只收摘要（P1）
5. **会话 fork**：从某个会话分叉出独立工作区，比较不同方案（P1）

其余差距（MCP resources、技能目录化、跨平台沙箱、插件体系、桌面壳、Schedule/Team/Workflow）要么是产品层，要么工作量大而收益靠后。**不建议本轮搬 DSH 的 Cordis 插件树、profile/bundle、插件市场或桌面壳。**

## 1. 证据口径与限制

### 1.1 路径缩写

- `J/` = `java-coding-agent/`
- `P/` = `/Applications/DeepSeek Harness.app/Contents/Resources/app.asar/dsh/node_modules/@deepseek-ai/`
- `D/` = `P/dsh/docs/`

### 1.2 读取方式

- asar 内文件用 `read` 工具直接读取；原生 `grep/find/glob` 不能进入 asar（报 `Not a directory`）。
- 批量检索用一次性脚本 `/tmp/asar-grep.js`，通过 `ELECTRON_RUN_AS_NODE=1 "$DSH_NODE_EXECUTABLE" /tmp/asar-grep.js ...` 执行；只读，不写 asar。
- Jade 代码、测试、`pom.xml`、git 状态均在本机仓库直接核对。
- DSH 文档与源码行号在安装版 asar 内逐条核对；`0.2.2-rc.1` 解包源码行号不同，未用于引用。

### 1.3 明确未验证

- DSH 的部署层是否额外限制 `maxSteps`：源码未发现内置步数上限，但未验证所有 profile 配置。
- DSH `fork` 子代理继承父上下文前缀的精确源码行：只读到 bundle 注释与 README 描述。
- DSH 测试门禁、覆盖率、CI：asar 内 `*.test.ts = 0`，测试源码不随产物发布；只能从 `devDependencies` 的 testkit 推断。
- DSH HTTP MCP 的 `tools/list_changed` 推送、HTTP 会话续期细节：文档与源码未给出完整结论。
- Jade 的真实 DeepSeek 流式/溢出错误体：本轮无 API key，未做真实调用；计划里用 mock server 验证，真实错误码需手工补验。

## 2. 能力矩阵

差距等级：**高** = 影响正确性或核心能力；**中** = 明显的能力/体验缺口；**低** = 实现差异，暂不影响使用；**无** = 对等，或双方都没有。

| # | 能力 | DSH 现状（证据） | Jade 现状（证据） | 差距 | 用户价值 | 优先级 | 理由 |
|---|------|------------------|-------------------|------|----------|--------|------|
| 1 | Agent loop / turn-step | `turn()` 内循环 `step()`；step = 一次模型请求 + 工具调用；turn = 零或多个 step（`P/dsh-agent-loop/src/agent.ts:276,333,441`；`D/architecture.md:83-106`） | 十阶段 `intake→context→skills→plan→model→tools→observe→verify→compact→checkpoint`，单轮循环（`J/src/main/java/io/github/jasoncai0/agent/agent/AgentLoop.java:136`） | 低 | 中 | — | 语义等价，Jade 的显式阶段更适合单 jar 调试；不重写 |
| 2 | 并行工具调用 | 仅 `isConcurrencySafe === true` 判并行，其余 exclusive；有界滚动池，默认上限 10，exclusive 形成屏障（`P/dsh-tools/src/pipeline.ts:106`；`P/dsh-tools/src/batch.ts:187`；`P/dsh-agent-loop/src/constants.ts:6`） | 严格串行 `for (int i = 0; i < calls.size(); i++)`（`J/src/main/java/io/github/jasoncai0/agent/agent/AgentLoop.java:462`）；无并发元数据（`J/src/main/java/io/github/jasoncai0/agent/tool/Tool.java:7-14`） | 高 | 高 | **P0** | 多文件调研、批量读时墙钟时间线性叠加；只读并发风险低、改动小 |
| 3 | 子代理委派 | 统一 activation；local child 有 durable Session；`maxDepth` 默认 1、`maxActiveSubagents` 默认 8；`spawn` 不继承父上下文（`D/subsystems/subagent.md:9-60`；`P/dsh-subagent/src/index.ts:196-197`；`P/dsh-subagent-spawn-in-process/src/index.ts:46`；`fork` 前缀继承仅见文档/README，源码行未验证） | 全库无 subagent/fork 实现；README 明说没有 branch/fork/concurrent-chat（`J/README.md:626`） | 高 | 高 | **P1** | 长调研会把父上下文撑爆；只读子代理是最小可行切口 |
| 4 | 上下文压缩 | `pressure` 与 `context-overflow` 两种触发；阈值 `min(window×0.8, budget−headroom 65,536)`；保留最近 16%；压缩重试 1 次、溢出重试 1 次；先裁工具结果再摘要（`D/subsystems/compaction.md:15-19,81`；`P/dsh-compaction-basic/src/config.ts:20-23,75,105-106`；同目录 `index.ts:213-228`） | 只在每轮末尾 COMPACT；默认 token 预算 30k、字符 120k；丢组保留至少 3 组；摘要上限 1200 token（`J/src/main/java/io/github/jasoncai0/agent/agent/AgentLoop.java:312-334`；`J/src/main/java/io/github/jasoncai0/agent/agent/ContextCompactor.java:23-26,54-65,84-96`） | 高 | 高 | **P0** | 请求前不压缩；一旦 400 直接 `run.failed`，长会话没有第二次机会（`AgentLoop.java:399-402`） |
| 5 | 工具结果落盘/裁剪 | 超 12,500 tokens 写 spill 文件，inline 留 head/tail + 回读路径；压缩前 pruner 默认 8192/4096/1024 字符（`P/dsh-spill-policy/src/index.ts:28`；`P/dsh-compaction-tool-result-pruner/src/config.ts:10-14`；`D/subsystems/spill.md:90-92`） | 工具输出超 30,000 字符只留前 30k，**丢尾部**（`J/src/main/java/io/github/jasoncai0/agent/agent/AgentLoop.java:37,508,693-698`）；压缩时旧/新工具结果裁成头 3000 + 尾 1000（`ContextCompactor.java:23-26,76-79,168-186`） | 高 | 高 | **P0** | 编译/测试错误常在尾部；被截断后模型只能猜，且原始输出无处可查 |
| 6 | 真流式输出 | `StreamChunk` 闭集（text/reasoning/tool-call/usage/finish）；adapter 一次调用 = 一次 provider attempt；空闲 5 分钟超时；失败统一 `LlmFailure`（`D/subsystems/llm-streaming.md:203-216,298-306`） | `LlmClient` 只有阻塞 `chat()`；`DeepSeekClient` 用 `BodyHandlers.ofString()` 且 `stream=false`（`J/src/main/java/io/github/jasoncai0/agent/llm/LlmClient.java:3-13`；`J/src/main/java/io/github/jasoncai0/agent/llm/DeepSeekClient.java:78,134`） | 高 | 高 | **P1** | Web 只能等整包，长答案看起来像卡死；无法提前取消/看到推理进度 |
| 7 | 会话 fork / 分支 | `SessionStore.fork(source, boundary?, childSessionId?)` 可在任意 turn 间分叉；open turn 内拒绝剪裁（`D/subsystems/session.md:711`）；fork 用 seed 创建子 run（`D/architecture.md:167`） | 只有 resume/follow-up 继续同一 run；`EventStore` 无 fork/copy；MySQL 无 parent 列（`J/src/main/java/io/github/jasoncai0/agent/history/EventStore.java:1-38`；`J/src/main/java/io/github/jasoncai0/agent/history/mapper/SchemaMapper.java:7-29`；`AgentService.java:397-472`） | 中 | 中 | **P1** | 想比较两种改法只能覆盖原会话；fork 是“敢让 agent 试”的前提 |
| 8 | 持久化 | append-only typed event log 是唯一真相源；JSONL + zstd frame；SQLite FTS5 只做查询索引；崩溃恢复补 interrupted turn（`D/subsystems/persistence.md:7,106-112,339-341`；`session.md:5`） | MySQL `jade_sessions`/`jade_events` + 本地 `transcript.json`/`events.jsonl`/`final.md`；启动恢复 200 个会话；RUNNING→FAILED（`J/src/main/java/io/github/jasoncai0/agent/history/mapper/SchemaMapper.java:7-29`；`J/src/main/java/io/github/jasoncai0/agent/agent/RunArtifacts.java:31-32,59-113`；`RunState.java:59-78`） | 低 | 中 | — | 路线不同但都可用；MySQL 对本地单用户偏重，不建议本轮换存储 |
| 9 | 长期记忆 | **没有长期记忆系统**；会话日志 + `AGENTS.md` + spill 是持久状态；跨会话搜索默认关闭（`D/subsystems/session.md:5`；全仓未发现 memory/notes 包，负证据） | 没有长期记忆；`AGENTS.md` + MySQL 会话 + `.agent` 文件（`J/README.md:616-629`） | 无 | — | — | 双方都没有；不要把“补记忆”当成 DSH 差距，也不建议本轮做向量库 |
| 10 | 权限/审批 | 权限 = `(sandbox mode, approval policy)` preset；`ask/never`；只有 `allowed-once` 放行，其他全 fail-closed；审计事件 log-only（`D/subsystems/approval.md:21-28`；`D/subsystems/permission-presets.md:15-24`；`P/dsh-user-approval/src/index.ts:296-301`） | 四种模式 `balanced/strict/read-only/bypass`；ASK 阻塞 300 秒，超时拒绝；once/session；`.agent/permissions.jsonl` 审计（`J/README.md:180-216`；`J/src/main/java/io/github/jasoncai0/agent/agent/PermissionBroker.java:33-46`） | 低 | 中 | P2 | Jade 的审批 UX 已够；缺的是与沙箱模式打包、以及 shell 逐命令策略（DSH 自己也留 TODO） |
| 11 | 沙箱 | 三平台后端：Linux bwrap/Landlock、macOS Seatbelt、Windows ACL；`read-only/workspace-write/danger-full-access`；`full/partial` 必须显式呈现（`D/subsystems/sandbox.md:5,11,20,30`；`P/dsh-sandbox-local/src/index.ts:2-4`） | 仅 macOS `sandbox-exec` 写监狱 + 可选网络；默认 off；读与进程共享宿主（`J/src/main/java/io/github/jasoncai0/agent/workspace/CommandSandbox.java:26-80`；`J/docs/SANDBOX.md`） | 高 | 高 | **P2** | 安全价值高，但 Linux/Windows 后端是独立大工程；先把“默认开/可见性”做好 |
| 12 | MCP | tools + resources；stdio/HTTP；`mcp__server__tool` 命名；凭据模板；指数退避重连 500ms–30s、单故障 10 次（`P/dsh-mcp-resources/src/tools.ts:16-59`；`P/dsh-mcp-manager/src/index.ts:43-59`；`P/dsh-mcp-client/src/connection.ts:7-13`）。MCP prompts 未实现/未验证（ACP README 明确说 resources/prompts 没有 DSH consumer，但 Web/Host 侧另有 resources 工具） | 只有 tools（stdio + streamable HTTP）；命名 `mcp_<server>_<tool>`；无 resources/prompts/sampling/elicitation/OAuth；无重连状态机（`J/src/main/java/io/github/jasoncai0/agent/mcp/McpConnection.java:55,77`；`J/README.md:281-304`） | 中 | 中 | **P2** | resources 是 MCP 的常见用法；但 Jade 现有 MCP 工具已覆盖主路径；prompts 不作为目标 |
| 13 | Skills | 6 个根、kebab-case；catalog 只放 name+description，正文按需 inject；chokidar 热更新（`D/subsystems/skills.md:66-94,128`） | 3 个根、深度 3；top-2 全文预载进 system prompt；`load_skill` 按需；无 watcher（`J/src/main/java/io/github/jasoncai0/agent/skill/SkillRegistry.java:36-97`；`AgentLoop.java:98,120,642`） | 中 | 中 | **P2** | 预载 top-2 全文会吃上下文；catalog 化是低成本改进，但排在 P0/P1 后 |
| 14 | 模型路由/重试 | adapter 注册；`model.select()` 只作用下一个未准备 step；**无自动 failover**；normal 默认 5 次重试、500ms 起、上限 10s、抖动 0.1（`D/subsystems/llm-streaming.md:437-461`；`P/dsh-llm/src/retry-policy.ts:15-25`） | 单 `DEEPSEEK_MODEL`；自定义网关；429/5xx/超时重试 3 次，700ms 指数 + 抖动；400/401 不重试（`J/src/main/java/io/github/jasoncai0/agent/llm/DeepSeekClient.java:28,97-122,256-268`） | 低 | 低 | P2 | DSH 也没有模型 fallback；Jade 的重试已覆盖大部分；多 provider 不是本轮目标 |
| 15 | 可观测性 | session telemetry ledger/ops、OTel 适配器、token 投影、session inspector（`D/subsystems/session-telemetry.md:31-60`；`P/dsh-token-meter/README.md:49`） | SSE 事件、`llm.request` 完整请求体、usage、MySQL 历史、Web trace 折叠（`J/README.md:217-228`；`J/src/main/java/io/github/jasoncai0/agent/web/WebServer.java:387-434`） | 低 | 中 | — | Jade 的本地 trace 已够用；OTel 是产品层 |
| 16 | CLI / Web UI | WebSocket mux（不是 SSE）、桌面 Electron、headless、ACP、SDK（`D/subsystems/web-server.md:5,47`；`P/dsh-api-gateway/src/stream-protocol.ts:7`） | `check/ping/headless/serve` 四模式；本地 HTTP + SSE；1,879 行原生 JS；无鉴权、127.0.0.1（`J/src/main/java/io/github/jasoncai0/agent/web/WebServer.java:46,244,387-434`；`J/src/main/resources/web/app.js:713`） | 低 | 中 | — | 形态不同；不需要为了“像 DSH”重写 WebSocket/桌面壳 |
| 17 | 测试/发布/扩展 | Cordis 插件树、profile/bundle、plugin_manager；测试源码不随 asar 发布；testkit/回放/mock server 在 devDependencies（`D/architecture.md:15-43`；`D/tool-catalog.md`；`P/dsh/package.json`） | 26 个测试文件、73 个 `@Test`；ServiceLoader `ToolProvider`；shade 单 jar；无插件市场（`J/pom.xml`；`J/src/main/java/io/github/jasoncai0/agent/tool/ToolProvider.java`） | 低 | 低 | — | Cordis 是 DSH 的产品外壳；Jade 的 ServiceLoader 已够扩展 |

## 3. Top 5–8 差距

### 3.1 上下文安全网（P0）

**现状。** Jade 在每轮 VERIFY 之后才压缩（`AgentLoop.java:312-334`），请求前不检查压力；`LlmException` 一律 `run.failed`（`:399-402`）。工具输出超过 30,000 字符时只保留前 30k（`:508,693-698`），而编译/测试错误通常在尾部。压缩器虽然对工具结果留了头 3000 + 尾 1000（`ContextCompactor.java:168-186`），但那是“已进入上下文之后”的二次裁剪，原始输出已经丢了。

**缺少它会怎样。**
- 长会话在第 N 轮突然 400，整个 run 失败，用户只能新开会话，丢失已验证的上下文。
- 一次 `mvn test` 输出 40k 字符，尾部 10k 的失败堆栈被切掉，模型看到的是“前面都通过”，然后给出错误修复。
- 想回看完整输出时，`.agent/events.jsonl` 只有被截断的版本，无法恢复。

**补上后用户能做什么。**
- 会话可以跨过模型窗口继续跑；溢出时自动“裁工具结果 → 摘要旧组 → 再试一次”，失败才报错。
- 任何大输出都能通过 `read_tool_output` 按字符区间回读，尾部不丢。
- 压缩事件里能看到 `before/after`、`spill` 路径、`overflowRetry`，可解释为什么被裁。

**边界。** 不做 DSH 的图片 offload、不做 zstd 会话格式、不做跨模型容量重测；只做文本工具输出、单次溢出重试、本地文件落盘。

### 3.2 并行只读工具调用（P0）

**现状。** `AgentLoop.executeTools` 严格串行（`:462`）；`Tool` 没有 `concurrencySafe` 元数据（`Tool.java:7-14`）。一次“读 5 个文件 + 搜 3 个关键词”的调研要 8 个往返。

**缺少它会怎样。**
- 多文件调研的墙钟时间随工具数线性增长；用户看到一个个 `tool.start/tool.end` 排队。
- 模型倾向于少读文件来省时间，回答质量下降。

**补上后用户能做什么。**
- 同一轮里的只读工具并发执行（上限 4），结果仍按模型给出的顺序回填；写文件、跑命令、问用户仍然串行。
- 权限检查仍在主线程逐个做，拒绝/超时语义不变。

**边界。** 只对白名单 `list_files/read_file/search_text/load_skill` 开启；MCP 工具本轮不并行（共享 stdio 连接，线程安全未验证）；只要批次里有一个非白名单工具，整批退回串行，避免顺序语义变化。

### 3.3 真流式输出（P1）

**现状。** `LlmClient` 只有阻塞 `chat()`（`LlmClient.java:3-13`）；`DeepSeekClient` 用 `BodyHandlers.ofString()` 且 `stream=false`（`:78,134`）。Web 的 SSE 只转发 agent 事件，模型 token 不是逐字到达。

**缺少它会怎样。**
- 长答案/长 reasoning 期间界面只有“asking the model”，看起来像卡死。
- 用户不能在中途看到方向错了就取消，只能等整包返回。

**补上后用户能做什么。**
- 回答逐字出现；reasoning 可选折叠；工具调用参数也能边到边显示。
- 取消时立即关闭 HTTP 流，不再等 provider 返回。

**边界。** 只做 DeepSeek/OpenAI-compatible SSE；不做 DSH 的 replay state、image chunk、adapter 热替换；Web 仍用现有 SSE 通道，不重写 WebSocket。

### 3.4 子代理委派（P1）

**现状。** 全库没有 subagent/fork（`README.md:626`）；所有调研都堆在父 transcript 里。Jade 的 `AgentService` 每个 run 一个虚拟线程，但没有父子 run 关系（`AgentService.java:87`；`SchemaMapper.java:7-29`）。

**缺少它会怎样。**
- “读 20 个文件确认一个调用链”会把父上下文塞满，压缩后细节丢失。
- 无法并行调研两个方向；父 agent 只能串行做。

**补上后用户能做什么。**
- 父 agent 调 `delegate_task`，子 agent 在只读工具集里调研，返回 8k 以内的摘要 + 证据路径；完整 transcript 落盘可查。
- 父上下文只增加一条工具结果，不被子 agent 的中间步骤污染。

**边界。** v1 子代理只读（不能写、不能跑命令、不能再委派）；同 workspace 读；最多 2 个并发、深度 1、10 分钟超时；不做 DSH 的 fork 子代理、agent team、workflow。

### 3.5 会话 fork（P1）

**现状。** 只有 resume/follow-up 继续同一 run（`AgentService.java:397-472`）；`EventStore` 没有 fork/copy，MySQL 没有 parent 列（`EventStore.java:1-38`；`SchemaMapper.java:7-29`）。README 明确说没有 branch/fork（`:626`）。

**缺少它会怎样。**
- 想比较“方案 A / 方案 B”只能覆盖原会话，或者手工复制整个目录。
- 失败的探索会污染主会话的 transcript 和 workspace。

**补上后用户能做什么。**
- 从已完成会话 fork 出独立 run：复制 transcript 前缀 + 复制 workspace（排除 `.agent/.git/target/node_modules`），父会话不变。
- 两个分支各自跑、各自验证，最后挑一个合并。

**边界。** v1 只支持“终态会话 fork 到当前末尾”，不支持任意 turn 中间分叉；workspace 复制有 200MB/20k 文件上限；不做 git worktree（后续可加）。

### 3.6 MCP resources（P2）

**现状。** Jade 只实现 `initialize → tools/list → tools/call`（`McpConnection.java:55,77`）；DSH 有 `list_mcp_resources/list_mcp_resource_templates/read_mcp_resource` 三个模型工具（`P/dsh-mcp-resources/src/tools.ts:16-59`）。MCP prompts 在 DSH 里也没有明确 consumer，不作为本轮目标。

**缺少它会怎样。** 资源型 MCP server（文档库、数据库 schema、知识库）只能暴露成工具；模型无法按 URI 浏览/读取资源。

**补上后用户能做什么。** 直接列资源模板、读资源内容。

**边界。** 本轮不做；等 P0/P1 稳定后再评估。需要先确认 Jade 的 MCP 连接是否支持并发请求/分页。

### 3.7 Skills 目录化与热更新（P2）

**现状。** Jade 扫 3 个根，top-2 全文预载进 system prompt（`SkillRegistry.java:36-97`；`AgentLoop.java:642`）；DSH 只放 name+description，正文按需 inject，并有 chokidar 热更新（`D/subsystems/skills.md:66-94`）。

**缺少它会怎样。** 技能多了以后 system prompt 被 top-2 全文占用；改 SKILL.md 要重启。

**补上后用户能做什么。** catalog 常驻、正文按需；编辑技能后下轮生效。

**边界。** 本轮不做；这是低成本改进，但优先级低于上下文安全网。

### 3.8 跨平台沙箱（P2）

**现状。** Jade 只有 macOS `sandbox-exec` 写监狱，默认 off，读与进程共享宿主（`CommandSandbox.java:26-80`；`J/docs/SANDBOX.md`）；DSH 有 Linux bwrap/Landlock、macOS Seatbelt、Windows ACL，并明确 `full/partial`（`D/subsystems/sandbox.md:5,30`）。

**缺少它会怎样。** Linux/Windows 用户没有命令级文件隔离；默认 off 时审批是唯一防线。

**补上后用户能做什么。** Linux 上跑 workspace-write；Windows 至少 restricted token；UI 明示 partial。

**边界。** 本轮不做；这是独立工程，且需要真实三平台测试。先把“默认值/可见性/失败关闭”写清楚，不假装是容器。

## 4. 非目标

以下能力**不是本轮要补的差距**，避免把 DSH 的产品功能误当核心：

1. **Cordis 插件树 / profile / bundle / plugin market**：DSH 的扩展体系；Jade 的 ServiceLoader 已够，重写会破坏单 jar 定位。
2. **桌面 Electron 壳 / HMR / 多窗口 / WebSocket mux**：产品外壳；Jade 的本地 HTTP + SSE 够用。
3. **多 provider 自动 failover / 企业路由**：DSH 也没有自动 failover；Jade 单 provider 不是缺陷。
4. **多租户 / 登录 / 鉴权**：Jade 明确本地单用户、127.0.0.1；加鉴权是另一个产品。
5. **图片/视觉输入**：DSH 有 image offload，但 Jade 的定位是代码/文本；本轮不做。
6. **DSH 会话格式 / 迁移链 / zstd / FTS5**：Jade 用 MySQL，迁移成本高、收益低。
7. **OTel / 遥测 / 审计平台**：本地 trace 已够；不做数据外发。
8. **Schedule / Agent Team / Workflow / Office / Browser 工具**：DSH 的产品层能力；Jade 先把 coding loop 做对。
9. **长期记忆 / 向量库**：DSH 也没有；不要为了“对齐”而引入。
10. **重写权限模型为 DSH preset**：Jade 的四模式 + 审计已覆盖；最多后续把 sandbox mode 与 approval 打包展示。

## 5. Jade 已有的优势（不要在对齐中丢掉）

- **验证门**：Java/Maven、Python、JS、Go 的真实编译/语法检查，shell 会使已验证失效（`AgentLoop.java:342`；`Verifier.java:47-222`）。DSH 未见等价的“每轮自动验证”内置门（未验证：可能由插件提供）。
- **MySQL 持久化 + 启动恢复**：200 个会话、RUNNING→FAILED、SSE 重放（`AgentService.java:58,397-472`；`RunState.java:59-78`）。
- **Goal review**：无工具 JSON 复核，连续 3 次拒绝失败（`AgentLoop.java:346-376`；`LlmGoalReviewer.java:26-81`）。
- **单 jar、Jackson-only、无 Cordis**：安装/调试成本低，代码可读。
- **73 个测试**：覆盖循环、压缩、权限、MCP stdio/HTTP、MySQL；比“功能多但测试不随产物发布”的 DSH 更容易本地回归。

## 6. 建议顺序与评审点

**先做 2 项，做完再评估后续：**

1. 上下文安全网（P0）—— 约 2 人日
2. 并行只读工具（P0）—— 约 1 人日

执行粒度已细化为计划里的 **S1–S8**：S1–S3 工具输出落盘/回读，S4–S6 溢出恢复，S7–S8 并行只读。S8 后停下评审，不自动进入 P1。

**然后按 P1 顺序：** 流式（2.5 人日）→ fork（2 人日）→ 子代理（4 人日）。

**需要用户评审确认的 4 个点：**

1. **fork 的 workspace 语义**：v1 复制 workspace（独立、可比较）还是共享（省磁盘、会互相污染）？本分析建议复制，带上限。
2. **子代理权限**：v1 是否只读？本分析建议只读；写入型子代理需要权限继承/隔离设计，风险高。
3. **spill 保留策略**：按 run 保留全部，还是 7 天/每 run 100MB 上限？本分析建议先保留，文档写清清理命令。
4. **DSH 命名是否对齐**：Jade 的 `mcp_<server>_<tool>` 与 DSH 的 `mcp__server__tool` 不同；本轮不改名，避免破坏已有配置。

## 7. 证据索引（关键行）

**DSH**

- 循环：`P/dsh-agent-loop/src/agent.ts:276,333,441`；`D/architecture.md:83-106`
- 并行：`P/dsh-tools/src/pipeline.ts:106`；`P/dsh-tools/src/batch.ts:187`；`P/dsh-agent-loop/src/constants.ts:6`
- 压缩：`D/subsystems/compaction.md:15-19,81`；`P/dsh-compaction-basic/src/config.ts:20-23,75,105-106`；`P/dsh-compaction-basic/src/index.ts:213-228`
- 工具裁剪：`P/dsh-compaction-tool-result-pruner/src/config.ts:10-14`；`P/dsh-spill-policy/src/index.ts:28`
- 流式：`D/subsystems/llm-streaming.md:203-216,298-306`；`P/dsh-llm/src/retry-policy.ts:15-25`
- 子代理：`D/subsystems/subagent.md:9-60`；`P/dsh-subagent/src/index.ts:196-197`
- fork：`D/subsystems/session.md:711`；`D/architecture.md:167`
- 权限：`D/subsystems/approval.md:21-28`；`P/dsh-user-approval/src/index.ts:296-301`
- 沙箱：`D/subsystems/sandbox.md:5,11,20,30`；`P/dsh-sandbox-local/src/index.ts:2-4`
- MCP：`P/dsh-mcp-resources/src/tools.ts:16-59`；`P/dsh-mcp-client/src/connection.ts:7-13`
- Skills：`D/subsystems/skills.md:66-94`
- 记忆：全仓未发现 memory/notes 包（负证据）；`D/subsystems/session.md:5` 仅说明会话日志

**Jade**

- 循环/工具：`J/src/main/java/io/github/jasoncai0/agent/agent/AgentLoop.java:136,197,312-334,399-402,458-529,693-698`
- 压缩：`J/src/main/java/io/github/jasoncai0/agent/agent/ContextCompactor.java:23-26,54-65,76-79,84-96,168-214`
- LLM：`J/src/main/java/io/github/jasoncai0/agent/llm/LlmClient.java:3-13`；`J/src/main/java/io/github/jasoncai0/agent/llm/DeepSeekClient.java:28,78,97-122,134,256-268`
- 工具：`J/src/main/java/io/github/jasoncai0/agent/tool/Tool.java:7-14`；`J/src/main/java/io/github/jasoncai0/agent/tool/ToolRegistry.java:17-23,60-72`
- 持久化：`J/src/main/java/io/github/jasoncai0/agent/history/mapper/SchemaMapper.java:7-29`；`J/src/main/java/io/github/jasoncai0/agent/history/EventStore.java:1-38`；`J/src/main/java/io/github/jasoncai0/agent/agent/AgentService.java:58,87,397-472`
- Web/SSE：`J/src/main/java/io/github/jasoncai0/agent/web/WebServer.java:387-434`；`J/src/main/resources/web/app.js:713,730,808-816`
- MCP/Skills：`J/src/main/java/io/github/jasoncai0/agent/mcp/McpConnection.java:55,77,149`；`J/src/main/java/io/github/jasoncai0/agent/skill/SkillRegistry.java:36-97`

## 8. 未验证/待补证据

- DSH 部署层 `maxSteps` 限制：源码未见，未逐 profile 验证。
- DSH `fork` 子代理前缀继承的源码行：仅读文档/注释。
- DSH 测试门禁与覆盖率：asar 不含测试源码。
- DSH MCP `tools/list_changed` 与 HTTP 会话续期：文档未给出完整结论。
- Jade 真实 DeepSeek 的溢出错误体与 SSE 分块格式：本轮无真实调用，计划用 mock server；上线前需用真实 key 手工验证。
