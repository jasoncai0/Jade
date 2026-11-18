# Jade 项目记忆

本文件是 `java-coding-agent` 的项目级记忆，不是全局配置。只记录这个仓库的事实、约定和后续工作；不要写入 `~/.dsh/AGENTS.md`、全局 skill 或其他全局位置。

## 项目身份

- 仓库：<https://github.com/jasoncai0/Jade>（public，`main`）。
- 本地目录：`java-coding-agent/`；版本 `1.4.0`；Java 21，Maven；单 jar `target/coding-agent.jar`。
- 定位：本地 coding agent。真实写入工作区，调用真实 `javac` / `python3` / `node` / `go` 编译并执行，Web 控制台显示十阶段循环与 Trace。
- 技术栈：JDK `HttpServer` / `HttpClient`、Jackson、MyBatis + MySQL、原生 HTML/CSS/JS；不是 Spring Boot。

## 当前架构

- `CodingAgentApplication`：`--check` / `--ping` / `--task` / `--task-file` / `serve`。
- `AgentService`：run/session 生命周期、恢复、fork、权限与问题 broker、MCP 管理器、MySQL 事件存储。
- `AgentLoop`：每个模型 turn 走十阶段（Intake → Context → Skills → Plan → Model → Tools → Observe → Verify → Compact → Checkpoint）；默认不设模型 turn 上限。
- `ToolRegistry`：15 个内置工具 + `ServiceLoader<ToolProvider>` + MCP 工具；写操作、命令、网络经过 `PermissionBroker`。
- `SkillRegistry`：`SKILL.md` 发现、catalog 注入、`load_skill` 按需读取、语言/任务匹配。
- `history/`：MySQL `jade_sessions` / `jade_events`，MyBatis mapper；历史分页、compact 投影、完整 LLM 请求体按需读取。
- `GoalReviewer`：完成前独立复核原始任务；`ContextCompactor`：token 预算 + 模型摘要；`Sandbox`：macOS `sandbox-exec` 可选写监狱，默认关闭。

## 常用命令

```bash
cd java-coding-agent
./run.sh --check
./run.sh --ping
./run.sh                 # 默认 8080
./run.sh --port 8081
./run.sh --task "..." --max-steps 0
mvn test
mvn -q -DskipTests package
```

MySQL 用 `docker compose up -d`；数据库 `jade`，端口 `127.0.0.1:3307`。真实 API key 只放本地 `.env`（600），不提交、不写日志、不写博客。

## 已交付能力（近期）

- 多语言：Java、Python、JavaScript、Go；`compile_code` / `run_code` 与 Java 兼容别名。
- 会话：持续追问、重启恢复、fork、MySQL 历史分页、Trace 正/倒序、历史页面懒加载。
- 安全与正确性：四档权限（balanced 默认）、审批卡、Goal review、反思机制、重复工具/命令形状/无进展护栏、可选 token 预算。
- 上下文：pinned memory、工具输出 micro-compaction、模型摘要、溢出重试与 spill（见 `docs/DSH-GAP-ANALYSIS.md`）。
- 扩展：MCP tools + resources（stdio / Streamable HTTP）、只读子代理、技能目录开关。
- 观测：SSE、完整请求体 Trace、运行指标、沙箱状态 health/banner。

## 关键约束与已知限制

- `AGENT_MAX_STEPS=0` 表示无限；正数是显式上限。没有默认 token 预算；无人值守长任务可显式设 `AGENT_TOKEN_BUDGET`。
- 默认 `AGENT_SANDBOX=off`，本地执行使用宿主工具链；macOS `sandbox-exec` 只是写/网络隔离，不是容器或 VM，读仍共享宿主。
- MCP 工具默认走审批；GitHub MCP 需要 PAT，当前没有在真实 GitHub 上完成写入烟测。
- MCP 未实现 prompts / sampling / OAuth；HTTP 启动重试是有限退避，不是完整重连状态机。
- 单用户、localhost、无鉴权；不要暴露公网。
- Web UI 为 Codex 风格：左侧会话栏、中间阅读列、底部 composer；Session/Trace 双 Tab，Context 详情在右侧抽屉。Trace 默认收起在 Tab 内。

## 文档索引

- `README.md`：功能、API、配置、测试。
- `docs/ARCHITECTURE.md`：组件、阶段、事件、并发。
- `docs/DEMO.md`：演示脚本。
- `docs/PERMISSIONS.md`、`docs/SANDBOX.md`：权限与沙箱边界。
- `docs/DSH-GAP-ANALYSIS.md`、`docs/superpowers/plans/2026-11-18-dsh-gap-closure.md`：差距与后续计划。
- `docs/RETROSPECTIVE.md`：设计与踩坑复盘。
- `docs/CODE-REVIEW-2026-11-18.md`：全量走查、P0/P1/P2 TODO。

## 博客踩坑记忆

- 项目踩坑日志：`docs/BLOG_MEMORY.md`。
- 已发布系列：`coding-agent-pitfalls`（总览）及 `coding-agent-loop`、`coding-agent-sandbox`、`coding-agent-sessions`。
- 线上总览：<https://www.jasoncai.site/posts/coding-agent-pitfalls.html>。
- 博客源码：`~/Documents/deepseek-harness/default-workspace/blog/content/posts/`。

## 后续工作要求

1. 新 bug、误判或环境坑，先按“症状 / 根因 / 修法 / 证据”追加到 `docs/BLOG_MEMORY.md`。
2. 判断是否对读者有价值；有价值就更新对应系列文章，必要时补图，并重新发布。
3. 发布前读 `~/.dsh/hosts/blog-jasoncai.site.md`，用 `scripts/publish.sh`，不要手改服务器。
4. 修改后跑相关测试；声称通过前先看实际输出。不要提交 `.env`、`target/`、`agent-workspaces/`、`mcp.json` 或任何密钥。
5. 保持本文件更新：架构、命令、测试数、已知限制变化时同步修改。
