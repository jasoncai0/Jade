# Code Review & Security Audit — 2026-11-26

- 范围：Jade `1.4.0`（`main` @ `53df56a`，含多语言验证、连续会话、Fork、子代理、MCP、Mem0 长期记忆、Team/TeamBoard）
- 维度：1. 整体架构与后端规范对照；2. 功能与并发逻辑缺陷；3. 安全边界审计；4. 分阶段演进 Roadmap

---

## 0. 核心结论

Jade 的单 jar 架构依赖克制（JDK 21 虚拟线程 + `HttpServer`/`HttpClient` + Jackson + MyBatis + MySQL Driver），十阶段循环、真实工具链验证门与容错回退边界清晰。但在连续叠加 Fork、Mem0、Team/TeamBoard 后，出现了 **6 个高优先级逻辑缺陷**、**4 类后端数据访问规范违例**以及 **3 个可在默认配置下绕过审批或跨站触发的安全漏洞**。

优先执行顺序：**P0 安全止血（命令守卫与本地 HTTP 边界） → P0 功能修复（Team/Fork/Mem0/权限表） → P1 存储合规与持久化瘦身 → CI 与许可证补齐**。

---

## 1. 整体设计、架构与代码质量评估

### 1.1 架构优势
1. **运行时轻量**：无重型 IoC 框架，单 jar 体积 < 9 MB，启动快，核心链路可直接通过单元测试与本地 Fake Server 回归（现有 49 个测试类、163 个测试）。
2. **外部依赖降级明确**：Mem0 sidecar 不可达时熔断回退 `NoopMemoryService`；`stream_options` 不兼容时自动降级重试；上下文超窗支持单次压缩恢复；超长工具输出落盘 `.agent/tool-results/` 并通过 `read_tool_output` 分段回读。
3. **完成门禁客观**：`Verifier` 强制执行真实 `javac`/`mvn`/`python3 -m py_compile`/`node --check`/`go build`，配合 `LlmGoalReviewer` 独立 JSON 复核降低幻觉交付率。

### 1.2 核心架构债与后端规范违例
1. **核心编排类过大**：
   - `src/main/java/io/github/jasoncai0/agent/agent/AgentLoop.java`（1,143 行）与 `AgentService.java`（1,011 行）同时承担生命周期、SSE 节流、工具并发、验证、压缩、记忆、子代理与 Team 装配，跨特性交互缺少显式契约，容易漏接线。
2. **循环内单条写库 / N+1 查询（违反数据访问红线 1 & 5）**：
   - `AgentService.java:322-325`：`fork()` 在 `for` 循环中逐条调用 `events.append()`，最多触发 **20,000 次独立 `SqlSession` 单行 `INSERT`**，未走批量会话。
   - `TeamService.java:130-134, 198-201, 442-448`：校验 `blockedBy` 与就绪状态时在循环内逐条 `store.find(root, id)` 查库。
3. **`SELECT *`、函数包列排序与无界查询（违反数据访问红线 4）**：
   - `TeamMapper.java:59-63`：使用 `SELECT *`，且 `ORDER BY CAST(SUBSTRING(task_id, 6) AS UNSIGNED)` 在列上套函数导致 filesort 且无 `LIMIT`。
4. **非事务多步写与未配置探活的独立连接池（违反数据访问红线 5 & 6）**：
   - `MySqlTeamTaskStore.java:69-82`：`openSession(true)` 自动提交，把 `bumpNextTask`、`currentTaskNo`、`insertTask` 拆成 3 个独立事务；`insertTask` 失败会造成序号空洞，并发实例下 `SELECT next_task_no - 1` 存在串号竞态。
   - `MySqlTeamTaskStore.java:29-35`：单独创建了第二个未开启 `poolPingQuery` 的 `PooledDataSource`，MySQL 空闲连接超时断开后会报错。
5. **公开仓库工程化缺口**：缺少 `LICENSE` 原文文件与 `.github/workflows/ci.yml`（单测、前端语法与密钥扫描门禁）。

---

## 2. 代码缺陷与逻辑漏洞清单

| 级别 | 模块 | 位置 | 症状与根因 | 修复方案 |
|---|---|---|---|---|
| **P0** | Team 续跑 | `AgentService.java:562-565`<br>`AgentLoop.java:1139`<br>`RunArtifacts.java:178-185` | Teammate 标记 `isSubagent=true`，首轮 transcript 写入 `.agent/subagents/<team-id>/transcript.json`；但 `send_message` 续跑时误调单参 `loadMessages(root)`，加载了根工作区 **Lead 的对话历史**，导致成员丢记忆且混入主会话上下文 | 改调双参 `RunArtifacts.loadMessages(run.workspace().root(), run.id())` |
| **P0** | Team 提示词 | `AgentService.java:594-598`<br>`TeamService.java:100-112` | `TeamService.teamPolicy(run)` 写了 Lead 协调规范，但根会话入口 `executeTurn` 漏调 `.withTeamPolicy(team.teamPolicy(run))`，Lead 从未收到 Team 规则提示词 | 在 `executeTurn` 构建 `AgentLoop` 时注入 `.withTeamPolicy(team.teamPolicy(run))` |
| **P0** | Mem0 隔离 | `MemoryPolicy.java:26-42, 77-78`<br>`AgentService.java:152-162` | `projectId` 对单次运行目录 `agent-workspaces/run-xxxx` 取哈希，每次新会话目录名不同导致 `projectId` 变化，`MemoryPolicy.select()` 过滤掉历史会话写入的全部 `project` 记忆 | 默认归属工作区根目录（支持 `AGENT_MEMORY_PROJECT_ID` 显式覆盖），同实例各 `run-*` 共享稳定 `projectId` |
| **P1** | Fork / Cancel | `TeamService.java:84`<br>`AgentService.java:317, 643-653` | Fork 会话带 `parentRunId`，被 `isRoot()` 误判为非根会话，无法开启或查看 Team；`AgentService.cancel(id)` 未级联停止 `TeamService` 下的活跃成员线程 | `isRoot` 改为 `!run.state().isSubagent()`；`fork` 初始化 team 记录；`cancel` 增加 `team.cancelRoot(id)` |
| **P1** | 工具权限表 | `PermissionPolicy.java:8-36, 131-140` | `read_tool_output`、`delegate_task` 与 MCP 资源读取工具漏加进只读白名单，落入 `evaluateUnknown`：在 `read-only` 子代理中被拒，在 `balanced` 下每次弹审批且无法按会话缓存；而 `team_spawn` 却在 `READ_TOOLS` 中，使 `read-only` 模式仍可派生可写成员 | 补齐只读工具白名单；将 Team 写/派生工具拆分为独立类别，在 `READ_ONLY` 模式下拒绝 |
| **P1** | 本地事件日志 | `RunEventLog.java:30-35`<br>`RunArtifacts.java:121-125` | 重启恢复后 `RunEventLog` 内存列表为空，追问时 `persist()` 整文件覆盖 `.agent/events.jsonl` 导致历史本地日志丢失；未重启长会话每步全量重写文件产生 `O(N^2)` 磁盘写入 | 跟踪已落盘序号，改为增量追加写入 `events.jsonl` |
| **P2** | Team 依赖环 | `TeamService.java:190-204` | `set_dependencies` 只拦自环，未拦 `A -> B -> A` 多跳环；`edit` 分支跳过 `blockedBy` 存在性与环检测；`subject` 未限制 200 字符长度 | 提取统一依赖图校验（存在性 + DFS 环检测）与字段长度校验 |
| **P2** | 终态与分页 | `RunArtifacts.java:350-353`<br>`AgentService.java:963-966` | `RunArtifacts.normalize` 漏掉 `LOOP_DETECTED` 和 `BUDGET_EXCEEDED`，本地恢复误判为 `FAILED`；无 MySQL 分页 `skip` 用了过滤前长度导致翻页为空 | 补齐 6 种终态；按过滤后列表切片分页 |
| **P2** | Mem0 / 压缩 | `Mem0HttpMemoryService.java:48-66`<br>`ContextCompactor.java:93-109` | `health()` 每次无缓存发起同步 HTTP GET，使召回/写入 RTT 翻倍；二次压缩若模型摘要失败会丢弃旧摘要 | `health()` 增加短 TTL 缓存；压缩降级时保留已有历史摘要前缀 |

---

## 3. 安全问题审计

| 级别 | 风险项 | 位置 | 攻击路径与影响 | 加固措施 |
|---|---|---|---|---|
| **P0 高危** | 命令分类器与守卫绕过 | `CommandClassifier.java:16-46, 96-108`<br>`CommandRunner.java:40-46` | 1. 正则 `(^\|[;&\|]\s*)` 缺 `(?m)` 且 `isReadOnly` 未按 `\n` 切分，`"ls\nsudo ..."` 或 `"ls\ncurl -d @.env ..."` 首行命中 `READ` 免审批直接执行；<br>2. 未拦截 `>`/`>>` 重定向与 `find -exec/-delete`，`echo x > ~/.zshrc` 被判为只读免审放行 | 全部行首锚点加 `(?m)`；按 `\r?\n` 切分命令段；显式拦截重定向符、`tee` 与 `find` 执行/删除参数 |
| **P0 高危** | 本地 Web CSRF 与 DNS Rebinding | `WebServer.java:97-124, 684-705` | 未校验 `Host`、`Origin`/`Sec-Fetch-Site`，且 `readJson` 接受 `text/plain` 简单跨域请求；恶意网页可静默向 `127.0.0.1` 发起创建任务或自动批准权限请求，在默认本地执行模式下等价宿主 RCE | 校验 loopback `Host` 头；写接口强制校验 `Content-Type: application/json` 并拦截跨站 `Origin` / `Sec-Fetch-Site: cross-site`；非 loopback 绑定支持并要求 Bearer Token |
| **P1 中危** | 编译阶段任意代码执行绕过 | `PermissionPolicy.java:64-73`<br>`Verifier.java:174-185` | `balanced` 模式下 `compile_code` 免审，若工作区含可执行 `./mvnw` 或恶意 `pom.xml` 插件，可在未获 `EXECUTE` 授权时执行任意代码 | 含 `mvnw` 或 `pom.xml` 的构建型编译在 `balanced` 下要求 `EXECUTE` 授权（已获会话级执行授权后自动放行） |
| **P2 中低危** | ReDoS / SSRF / 环境变量残留 | `SearchTextTool.java:43`<br>`HttpMcpConnection.java:38`<br>`CommandRunner.java:35-38` | 恶意正则可卡死并行读线程池且无法中断；HTTP MCP 开启 `Redirect.NORMAL` 存在重定向 SSRF 与鉴权头泄露；子进程环境未剥离 `*_PASSWORD`/`DATABASE_URL` | `SearchTextTool` 使用可中断且带步数上限的 `CharSequence`；HTTP MCP 改为 `Redirect.NEVER`；补齐敏感环境变量剥离规则 |

---

## 4. 演进 Roadmap

1. **阶段 1：安全止血与 P0 缺陷修复（1 天）**
   - 修复 `CommandClassifier` / `CommandRunner` 多行、重定向、`find -exec` 绕过及环境变量剥离（2 小时）。
   - 加固 `WebServer` 的 `Host`、`Origin`/`Sec-Fetch-Site`、`Content-Type` 与远端 Token 校验（2 小时）。
   - 修复 Teammate 续跑串历史、Lead 漏挂 `teamPolicy`、Fork 会话 `isRoot`、级联取消 Team、只读工具权限表及 Maven 编译授权（3 小时）。
   - 补齐 MIT `LICENSE` 与 GitHub Actions CI（1 小时）。
2. **阶段 2：数据访问合规与持久化治理（1.5 天）**
   - 修复 Mem0 `projectId` 跨会话稳定作用域与 `health()` TTL 缓存，收口异步写入线程（2 小时）。
   - 消除 `fork` 与 `TeamService` 循环单条 SQL，`TeamMapper` 去 `SELECT *` 与函数排序，单事务建任务，补齐 DFS 依赖环检测（4 小时）。
   - `.agent/events.jsonl` 改为增量追加，修复 `RunArtifacts.normalize` 与内存历史分页边界，后续补齐会话 TTL 归档命令（4 小时）。
3. **阶段 3：核心编排解耦与前端自动化回归（2 天）**
   - 拆分 `AgentLoop` 与 `AgentService` 为独立职责组件（1 天）。
   - 引入 Playwright UI E2E 回归，并将长会话 Trace 升级为按 Turn 默认折叠/窗口化渲染（1 天）。
4. **阶段 4：智能记忆准入 v2 与可选沙箱插件（3.5 天）**
   - 落地 Mem0 混合准入 v2（候选窗口 + 原文引文校验 + `infer=false` + Pending 队列）及 TTL/`supersedes` 淘汰机制（2 天）。
   - MCP 连接异步化与重连状态机，以及可选 Docker `SandboxProvider` SPI 插件（1.5 天）。
