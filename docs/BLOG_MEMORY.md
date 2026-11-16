# Jade 踩坑记忆

> 项目级记忆，仅保存在本仓库。不要复制到全局 `~/.dsh/AGENTS.md`。
> 用途：记录搭建 coding agent 时遇到的问题，并同步到博客 `coding-agent-pitfalls`。

## 文章元数据

| 字段 | 值 |
|---|---|
| 标题 | 从能跑到跑对：搭一个本地 Coding Agent 踩过的坑 |
| slug | `coding-agent-pitfalls` |
| 线上地址 | https://www.jasoncai.site/posts/coding-agent-pitfalls.html |
| 博客源码 | `~/Documents/deepseek-harness/default-workspace/blog/content/posts/coding-agent-pitfalls/` |
| 标签 | `harness`, `codegen`, `architecture` |
| 发布状态 | 已发布（2026-11-16 14:32） |
| 项目复盘 | `docs/RETROSPECTIVE.md` |

## 踩坑账本

| # | 日期 | 领域 | 症状 | 根因 | 修法 | 博客章节 |
|---|---|---|---|---|---|---|
| 1 | 2026-11-14 | 循环终止 | Go 快排 `MAX_STEPS`，但 `go build/test/run` 全过 | 10 轮用尽，模型没来得及写最终回答 | 调到 12 轮复跑成功；区分终止状态与工件结论 | 2 |
| 2 | 2026-11-14 | 工作区污染 | `gofmt -l .` 扫到 Go SDK 坏样例退出 2；验证器把 SDK 里的 JS 当项目源码 | 模型把 Go SDK 下载到 `.toolchain/go`；源码扫描未跳过 SDK 目录 | `.toolchain`/`.sdk` 等加入 `SKIP_DIRS`；禁止在工作区安装 SDK | 3 |
| 3 | 2026-11-15 | 沙箱 | Spring Boot `Maven compile failed`，报 `~/.m2/...: Operation not permitted` | 写沙箱只允许工作区；Maven 缓存、临时目录、网络、端口均未建模 | 预热缓存；`java.io.tmpdir` 指回工作区；按需开网络；Jade 退到 8081 | 4 |
| 4 | 2026-11-14 | 多语言验证 | `node --check a.js b.js` 只检查第一个文件 | Node 参数语义 | 每个 `.js` 单独执行；补“合法 a + 非法 b”测试 | 5.1 |
| 5 | 2026-11-14 | 语言检测 | 工作区被 SDK 的 `.js` 污染后误选 JavaScript | 按扩展名数量投票，没有隔离依赖/SDK 目录 | 只扫描项目文件；允许显式指定 language | 5.2 |
| 6 | 2026-11-14 | Go 构建 | 无 `go.mod` 时 `go build ./...` 失败 | Go 1.27 默认模块模式 | 自动生成最小 `go.mod`；老项目兼容 `GO111MODULE=off` | 5.3 |
| 7 | 2026-11-14 | 工具发现 | ServiceLoader 测试编译失败，找不到 `EchoToolProvider` | 服务文件里的 provider 名与实际嵌套类不一致 | 改成 `EchoProvider`；加发现数量和重名冲突测试 | 6 |
| 8 | 2026-11-14 | 验证门 | 编译成功即标 verified，运行失败仍可能 `SUCCEEDED` | `run_java` 在进程启动前就置 verified | 建议运行 exit 0 才算；当前仍未完全修完，文章标记为 open limitation | 7 |
| 9 | 2026-11-14 | 会话恢复 | 重启后 `/api/runs` 为空；事件日志缺 `run.finished` | 只存在内存；先持久化后发终止事件 | 恢复 transcript/events；最后再持久化一次；RUNNING 改 FAILED | 8 |
| 10 | 2026-11-15 | Trace | 请求体包含完整上下文，展开后可能泄露敏感内容 | 只防了 Authorization，没防 body | 默认折叠；不写普通日志；run 目录按敏感数据管理 | 9 |
| 11 | 2026-11-15 | 连续会话 | 第一轮结束后无法追问，SSE 已断开 | `run.finished` 被当成会话终点；`AgentLoop` 每轮关闭事件日志 | 日志保留到服务停机；`run.finished` 按轮发；新增 `turn.started`；UI 不断 SSE、按 `lastSeq` 续连 | 8.1 |
| 12 | 2026-11-15 | 前端状态 | 空状态下 Start 按钮被禁用 | 初始 `status` 未定义，busy 判断把空状态当运行中 | 初始状态 `IDLE`；busy 仅在选中会话且 RUNNING/QUEUED 时成立 | 8.1 |
| 13 | 2026-11-15 | 权限 UI | 后端 `WAITING_APPROVAL`、API 有 pending，页面看不到审批按钮 | 卡片 append 在 Live Trace 滚动区末尾；prepend 后仍可能落在 950px 视口下方 | 卡片移出时间线，挂到 `document.body`，固定底部 overlay；刷新补拉 pending | 8.2 |
| 14 | 2026-11-15 | 沙箱默认 | Spring Boot 已生成并通过测试，启动仍 `SocketException: Operation not permitted`；`/health` HTTP 000 | `AGENT_SANDBOX=auto` 默认开启写监狱，`AGENT_SANDBOX_NETWORK=0` 在进程创建时锁死网络；Maven 缓存/JVM temp 也受写限制 | 默认改 `off` 本地直接执行；需要隔离时显式 `auto`/`required`；同一 Spring Boot 会话复跑，8080 `/health` 200 | 4.1 |
| 15 | 2026-11-16 | 长任务轮次 | 10/30 轮上限会截断已经完成的长任务，也会把“没写完最终回答”误判成失败 | 用固定模型轮数当唯一护栏，无法区分正常长任务和失控循环 | 默认 `AGENT_MAX_STEPS=0` 不限制轮数；累计 token 预算默认 0（可选，正数才硬停）和同名同参数连续 4 次的 `ToolLoopGuard` 兜底；正数上限保留 | 2 |

## 发布记录

| 日期 | 动作 | 结果 | 备注 |
|---|---|---|---|
| 2026-11-15 | 创建文章与 3 张图 | 构建通过 | 图：架构、MAX_STEPS 归因、沙箱边界 |
| 2026-11-15 16:59 | 发布 `coding-agent-pitfalls` | 线上 200，3 张图 200 | 发布备份 `/home/ubuntu/backups/blog-20261115-165933`；blog commit `36ca398` |
| 2026-11-15 17:15 | 补充连续会话与前端状态两坑 | 线上 200，8.1 节可见 | blog commit `f9e23ae`；基线 `73bb075`；备份 `/home/ubuntu/backups/blog-20261115-171539` |
| 2026-11-15 23:01 | 补充权限审批卡可见性坑 | 线上 200，8.2 节可见 | blog commit `0f8da6d`；备份 `/home/ubuntu/backups/blog-20261115-230109` |
| 2026-11-15 23:14 | 补充默认沙箱导致本地服务起不来 | 线上 200，4.1 节可见 | blog commit `487138a`；备份 `/home/ubuntu/backups/blog-20261115-231355` |
| 2026-11-16 13:24 | 补充默认取消轮次上限 | 线上 200，2 节更新可见 | blog commit `c8d2c3a`；备份 `/home/ubuntu/backups/blog-20261116-132408` |
| 2026-11-16 13:41 | 说明 token 预算默认关闭 | 线上 200，2 节更新可见 | blog commit `195f831`；备份 `/home/ubuntu/backups/blog-20261116-134125` |
| 2026-11-16 14:32 | 增加最终经验复盘与六个设计判断 | 线上 200，11 节可见 | blog commit `0b0b068`；备份 `/home/ubuntu/backups/blog-20261116-143212` |

## 待办

- [x] 构建 Hugo 并发布 `coding-agent-pitfalls`。
- [x] 发布后把“发布状态”改为“已发布”，记录线上复验结果。
- [ ] 后续新坑按账本格式追加，并同步文章章节。

## 更新规则

1. 只记录可验证的事实：命令、错误原文、run id、文件路径、commit。
2. 每条至少写清楚“症状 / 根因 / 修法”；没有修法则标为“未解决”。
3. 不在账本里写 API key、口令、用户隐私或大段工具输出。
4. 发布前跑 `python3 scripts/check-tags.py content/posts data/tags.yaml` 和 `scripts/publish.sh`。
