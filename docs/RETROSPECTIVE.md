# Jade 开发复盘：从“能跑”到“可交付”

> 项目：[`jasoncai0/Jade`](https://github.com/jasoncai0/Jade)（公开仓库）
> 周期：2026-11-13 至 2026-11-16
> 对应公开文章：<https://www.jasoncai.site/posts/coding-agent-pitfalls.html>
>
> 后续更新（2026-11-18）：内置工具 15 个，测试 143 个；新增流式 SSE、会话
> fork、只读子代理、MCP resources、沙箱可见性与 Skills 目录化开关。下文
> “12 个工具 / 60 tests”是当时快照，不再代表当前状态。

## 1. 最终形态

Jade 是一个本地 Java Coding Agent：

- 纯 Java 21 + JDK `HttpServer`/`HttpClient`，Jackson 是唯一外部运行时依赖。
- 十阶段循环：Intake → Context → Skills → Plan → Model → Tools → Observe → Verify → Compact → Checkpoint。
- 12 个内置工具，`ServiceLoader` 发现外部 `ToolProvider`。
- 6 个 `SKILL.md` 技能，启动时发现、按任务匹配、`load_skill` 按需加载。
- 支持 Java、Python、JavaScript、Go 的真实编译/语法检查、运行和验证。
- 每次 run 独立工作区；默认本地执行，可选 macOS `sandbox-exec` 写监狱。
- 权限审批：文件写、代码执行、危险命令、网络访问；`balanced` 默认。
- 会话恢复与连续追问；SSE 实时 trace；LLM 请求体默认折叠展示。
- 默认不限制模型轮次；重复调用护栏 + 可选 token 预算。

## 2. 最值得保留的五个设计

### 2.1 运行时拥有不变量，模型只负责决策

模型可以决定“写什么、跑什么”，但不能决定“是否验证、是否隔离、是否记录”。
验证门、权限检查、工作区路径限制、事件日志都在 `AgentLoop`/`AgentService` 中强制执行。

### 2.2 验证必须执行真实命令

`Verifier` 不相信模型说“已完成”。Java 用 `javac`/Maven，Python 用 `py_compile`，
JavaScript 逐文件 `node --check`，Go 用 `go build`。失败输出回填为 tool/user 消息，
让模型在下一轮修复。

### 2.3 状态机要区分“本轮结束”和“会话结束”

`run.finished` 是“这一轮模型结束了”，不是“这个会话不能再输入”。
连续追问复用同一个 run id、workspace、transcript 和事件日志；日志只在服务关闭时关闭。

### 2.4 权限是 UX，隔离是执行环境

审批卡解决的是“用户是否授权”，不是“代码是否安全”。
本地模式下，用户批准后的 `python -c` 仍然能读主机文件；真正的隔离必须靠容器/VM/OS 策略。
文档必须明确这个边界，不能把审批 UI 说成安全沙箱。

### 2.5 默认值就是产品行为

- 沙箱默认 `off`：本地服务、Maven 缓存、JVM 临时目录都需要主机环境。
- 轮次默认不限：长任务不该被固定轮数截断；预算默认关闭，只给无人值守任务显式开启。
- 权限默认 `balanced`：第一次写文件/执行代码/联网要问，读操作放行。

## 3. 踩过的坑与修法

| # | 现象 | 根因 | 修法 |
|---|---|---|---|
| 1 | `MAX_STEPS`，但 Go 代码其实能跑 | 固定轮数把“没写最终回答”误判成代码失败 | 检查 verification/workspace；默认取消轮次上限 |
| 2 | 验证器把 Go SDK 里的 JS 当项目源码 | 模型把 67 MB SDK 下载到 `.toolchain/`；扫描器只按扩展名找文件 | 排除 `.toolchain`/vendor；Go 原生检测 |
| 3 | Spring Boot 编译过，启动 `Operation not permitted` | 默认写监狱锁住 `~/.m2`、JVM temp、localhost bind | 默认本地执行；需要时显式开 sandbox |
| 4 | `node --check a.js b.js` 只检查第一个 | Node 命令语义 | 逐文件循环检查 |
| 5 | 无 `go.mod` 的工作区 `go build ./...` 失败 | Go module 模式要求模块根 | 自动生成最小 `go.mod` |
| 6 | 权限后端有 pending，页面没有按钮 | 审批卡追加在 Live Trace 滚动区末尾，落在视口外 | 卡片挂到 `document.body`，固定底部 overlay |
| 7 | 空状态下 Start 按钮被禁用 | 初始 `status` 未定义，busy 判断把空状态当运行中 | 初始 `IDLE`；busy 只在选中会话 RUNNING/QUEUED 时成立 |
| 8 | 服务重启后旧会话不能追问 | 恢复时事件日志被标记 closed | `RunEventLog.open()`；恢复的会话按需重开 |
| 9 | 打包时服务报 `NoClassDefFoundError` | `mvn package` 替换了运行中的 jar，JVM 懒加载类失败 | 先停服务，再打包，再启动 |
| 10 | 审批卡出现但按钮点不到 | 卡片在滚动容器内，按钮低于 950px 视口 | 固定 overlay + 滚动/键盘可用 |
| 11 | 长任务被 2M token 预算硬停 | 总预算默认开启，和交互式长任务冲突 | 默认 `AGENT_TOKEN_BUDGET=0`；只给无人值守任务设上限 |
| 12 | 重复调用导致空转 | 模型反复执行同名同参数工具 | `ToolLoopGuard`：连续 4 次即停止并保留 transcript |

## 4. 关于轮次与预算

固定轮次和固定 token 预算都是“代理指标”：

- 轮次不衡量进展。一个需要 15 轮的重构可能是正常的；一个 3 轮的重复读文件可能是死循环。
- 累计 token 不衡量价值。长任务天然消耗更多 token；硬上限会把正常任务截断。
- 更可靠的停止信号是：没有工具调用且验证通过（正常完成）、连续相同工具调用（无进展）、用户取消、显式配置的成本上限（无人值守）。

Claude Agent SDK 的 `max_turns`、`max_budget_usd` 默认都是 `None`；DSH 默认 profile 使用 token-meter 与上下文压缩，硬预算由可选插件提供。Jade 采用同样策略：默认不限，显式开启。

## 5. 如果重来一次

1. 先写权限/隔离边界文档，再写工具；不要把“审批”和“沙箱”混成一个词。
2. 从第一天起把“编译通过、运行通过、测试通过”分成三个验证层级。
3. 工作区扫描器先做 SDK/依赖目录排除，再做语言检测。
4. 会话状态从第一天就按“多轮”设计，而不是先做单轮再补。
5. UI 的阻塞式交互（审批、确认）必须独立于滚动容器。
6. 任何“硬停止”都先问：它是在保护用户，还是在替用户做决定？

## 6. 交付检查清单

- [x] `mvn test`：60 tests，0 failures
- [x] `mvn package`：2.5 MB shaded jar
- [x] 真实 DeepSeek Java/Python/JS/Go 运行
- [x] 权限审批（write / execute / network）与审计
- [x] 会话恢复、连续追问、SSE replay
- [x] 默认本地执行，sandbox 可选
- [x] 默认不限轮次，loop guard + 可选 token budget
- [x] 文档、博客、项目记忆同步

## 7. 相关文档

- `README.md`：启动、配置、API、工具/技能扩展
- `docs/ARCHITECTURE.md`：阶段循环、事件模型、验证门、权限门
- `docs/PERMISSIONS.md`：权限设计
- `docs/SANDBOX.md`：沙箱边界与 Docker 方案
- `docs/BLOG_MEMORY.md`：踩坑账本与发布记录
