# Jade Agent Team / TeamBoard 设计（待 review）

> 状态：提案，未实施。本文只回答“怎么做”，不改运行时代码。
> 对照版本：Jade `1.4.0`，HEAD `8175c83`，156 tests。
> 参考实现：DSH 的 `@deepseek-ai/dsh-agent-team`、`@deepseek-ai/dsh-tool-agent-team`、`@deepseek-ai/dsh-client-ui-agent-team`。

## 1. 结论

Jade 目前**没有** Team / TeamBoard：

- `SubagentService` 只有同步、只读、单层、最多 2 个并发的 `delegate_task`；
- 子代理没有 `list_agents` / `send_message` / `wait_agent`，不能继续对话；
- 没有共享任务表、依赖关系、owner、revision，也没有 `/api/team` 或 Team UI。

所以这不是“补一个看板 UI”就能完成的模式。要接近 DSH，需要同时补三件事：

1. 可继续运行的 teammate（至少是异步只读 teammate）；
2. 共享任务板（任务、owner、依赖、CAS revision）；
3. Team 模式与提示词、权限、API、UI、恢复语义。

建议分两阶段：

- **Team v1（推荐先做）**：任务板 + 异步、可继续的**只读** teammate。Lead 独占写入/编译/运行；teammate 做检索、审查、方案对比和验证建议。安全边界清晰，能先把协作协议跑通。
- **Team v2**：可写 teammate。共享工作区、写作用域仅作提示、权限审批路由到 Lead、最终验证仍由 Lead 负责。这个阶段才接近 DSH 的“团队写代码”。

如果首版就要求 teammate 能直接改代码，范围会从“补任务板”变成“重做子代理运行时 + 并发写安全”，建议单独立项。

## 2. DSH 参考实现的实际语义

DSH 的 Team 不是独立组织模型，而是 **root Session + 所有 continuable descendants**：

- Team mode 按 root 保存，默认关闭；子 Session 读取 root 的实时状态。关闭后保留任务和子代理，只隐藏团队工具和策略。
- 任务存在 Lead Session 日志里，以 `team/task` versioned event 保存，投影成 `agentTeam` 状态。写入按 Lead 串行化，成功后 flush。
- 任务字段：`id`、`revision`、`subject`（≤200）、`description`（≤16,384）、`status`（pending/in_progress/completed/deleted）、可选 `ownerId`/`ownerName`、`blockedBy[]`、`writeScopes[]`。
- 动作：create、claim、release、edit、set_dependencies、complete、reopen、reassign、delete。只有 Lead 能 reassign；owner 或 Lead 才能修改。claim 要求所有 blocker completed。仍有依赖的任务不能删除；删除是 tombstone；最多 256 个未删除任务。
- CAS 使用 `expectedRevision`；过期返回 `TEAM_TASK_STALE_REVISION`。
- `writeScopes` 是工作区相对路径，重叠只警告，不锁文件。没有自动释放 owner，也没有“任务变更自动唤醒 agent”；要推进 idle teammate，必须显式发消息。
- 工具：`team_task_create`、`team_task_list`、`team_task_get`、`team_task_update`。列表支持 status/owner/unowned/ready 过滤、cursor、limit（≤100）。
- 客户端只有**只读** TeamBoard，挂在 subagent footer；任务创建/修改由模型工具完成，UI 不提供编辑按钮。
- DSH 自己的限制：单进程、共享文件系统、advisory write scopes、冷成员不恢复、没有跨团队消息、没有自动 owner 释放。

这些限制很重要：不要把 `writeScopes` 当成互斥锁，也不要把 TeamBoard 当成调度器。

## 3. Jade 现状与差距

| 能力 | Jade 现状 | DSH | 结论 |
|---|---|---|---|
| Team 模式 | 无 | root 级开关，默认 off | 需新增 |
| 团队成员 | 同步只读 subagent | root + continuable descendants | 需重做生命周期 |
| 子代理继续对话 | 否 | 是 | v1 必须补 |
| 子代理消息 | 否 | `send_message` / `wait_agent` | v1 必须补 |
| 共享任务板 | 无 | 有，durable + revision | 需新增 |
| 任务依赖 | 无 | `blockedBy` DAG | 需新增 |
| 任务 owner | 无 | Session id/name | 需新增 |
| 写入作用域 | 无 | advisory paths | v2 再做 |
| 团队 UI | 无 | 只读 board | 需新增 |
| 持久化 | 每 run `.agent` + MySQL events | Lead journal | 用 MySQL 表 + 事件 |

现有约束（代码位置）：

- `src/main/java/io/github/jasoncai0/agent/agent/SubagentService.java:34-35`：`MAX_ACTIVE_PER_PARENT=2`、`MAX_DEPTH=1`。
- `SubagentService.java:96-107`：`newChildRun` + executor + `runChild`，一次任务结束即返回。
- `AgentService.java:413-433`：parent 注册 `delegate_task`，child 只拿到 `list_files/read_file/search_text/load_skill/reflect`。
- `AgentService.java:435-470`：child 的 `.agent/subagents/<id>`、read-only registry、单轮执行。
- `WebServer` 无 team 路由；`app.js` 无 Team tab/board。

## 4. 方案选项

### A. 只加任务板

复用现有 `delegate_task`，加任务表、4 个工具和只读看板。Lead 可以把“调研/审查”任务分给只读子代理，但不能并发、不能继续对话。

- 优点：改动小，约 1 个模块；安全。
- 缺点：不是团队，只是“任务清单 + 一次性调研”。不推荐作为最终形态，但可作为 v0。

### B. Team v1：任务板 + 异步只读 teammate（推荐）

在 A 的基础上重做 `SubagentService` 为可继续的只读 teammate：

- `team_spawn` 立即返回成员 id，child 在虚拟线程里跑；
- parent/child 都有 `list_agents`、`send_message`、`wait_agent`；
- child 可继续接收消息，能 claim/complete 只读任务；
- Lead 保留所有写入/编译/运行权限；
- 任务板对全队可读，owner 是 child 的 run id。

- 优点：真正有协作、消息、继续对话和任务状态；风险可控。
- 缺点：teammate 不能改代码，无法独立完成实现类任务。

### C. Team v2：可写 teammate

在 B 上开放 write/compile/run/command 给 teammate：

- 共享同一工作区；
- `writeScopes` 只做提示，冲突检测靠写前 hash/文件锁；
- 每个 teammate 的权限请求汇总到 Lead；
- Lead 负责最终 diff 审查和 Goal/verify；
- 可选 worktree 隔离后合并。

- 优点：接近 DSH。
- 缺点：并发写、权限、评审、回滚复杂度显著上升；不建议与 v1 同批交付。

**推荐：先 B，review 通过后再决定是否做 C。** 如果用户首版就要“teammate 直接写代码”，则应把 C 作为主目标，而不是先做 B 再返工。

## 5. Team v1 目标架构

```text
Root run (Lead)
  ├─ TeamService (mode, task board, members)
  │    ├─ TeamTaskStore (MySQL, CAS)
  │    └─ TeamEvent projection (SSE / history)
  ├─ Lead AgentLoop (writes, compile, permissions, final verify)
  ├─ Teammate A (read-only AgentRun, own transcript, continuable)
  ├─ Teammate B (read-only AgentRun, own transcript, continuable)
  └─ Shared Workspace (read-only for teammates in v1)

Web UI
  ├─ Session / Trace tabs (existing)
  └─ Team drawer: tasks + members (read-only, DSH-style)
```

### 5.1 团队身份与模式

- 一个 root run 就是一个 team；成员 = root + `parentRunId` 在 root 下的所有 continuable child。
- 不建独立 membership 表。新增 `jade_teams(root_run_id PK, enabled, next_task_no, created_at, updated_at)`。
- `AGENT_TEAM_MODE=off|on` 为默认值（建议 off）；`POST /api/runs/{id}/team` 只允许 root 切换；子 run 继承。
- 关闭时：隐藏 `team_*`、`team_spawn` 等工具和 Team UI，保留任务板与历史成员。

### 5.2 任务领域模型

```java
record TeamTask(
    String id,            // task-1, task-2...
    long revision,        // CAS
    String subject,       // <=200
    String description,   // <=16384
    TeamTaskStatus status,// PENDING, IN_PROGRESS, COMPLETED, DELETED
    String ownerRunId,
    String ownerName,
    List<String> blockedBy,
    List<String> writeScopes,
    long createdAt,
    long updatedAt
) {}
```

规则：

- 状态机：`pending -> in_progress -> completed -> (reopen) in_progress`；deleted 为 tombstone，不再列出。
- `claim` 前所有 `blockedBy` 必须 completed；`set_dependencies` 必须做环检测。
- 只有 Lead 能 reassign；owner 或 Lead 可 edit/complete/release。
- 删除被依赖的任务必须拒绝；最多 256 个未删除任务。
- `writeScopes` v1 仅用于 UI 提示和审计；v2 才参与冲突警告。

### 5.3 持久化与并发

不要复用 `RunEventLog` 当任务数据库：事件流适合 UI，不适合 owner/ready/revision 查询。新增规范化表：

```sql
jade_teams(root_run_id PK, enabled, next_task_no, created_at, updated_at)
jade_team_tasks(root_run_id, task_id, revision, subject, description,
                status, owner_run_id, owner_name, created_at, updated_at,
                PK(root_run_id, task_id))
jade_team_task_deps(root_run_id, task_id, blocked_task_id, PK(...))
jade_team_task_scopes(root_run_id, task_id, path, PK(...))
```

- 每次变更在事务里：校验权限/依赖/环 -> `UPDATE ... WHERE revision=?` -> 失败返回 stale -> 写 `team.task.*` 事件。
- `next_task_no` 在 `jade_teams` 内原子自增。
- TeamService 对同一 root 加内存串行锁，DB revision 做第二道保护。
- 重启后从表恢复；任务不依赖内存中的成员。

### 5.4 工具

本团队模式开启时，为 root 和 teammate 注册：

| 工具 | 作用 | 权限 |
|---|---|---|
| `team_task_create` | 创建任务/依赖 | 自动（元数据） |
| `team_task_list` | 过滤/分页 | 自动 |
| `team_task_get` | 单任务详情 | 自动 |
| `team_task_update` | claim/release/edit/complete/reopen/reassign/delete/set_dependencies | 自动 + 领域校验 |
| `team_spawn` | 创建只读 teammate | 自动（v1） |
| `list_agents` | 成员状态 | 自动 |
| `send_message` | 给成员发消息 | 自动 |
| `wait_agent` | 等成员完成/超时 | 自动 |

限制：

- v1 teammate 不能 spawn；depth=1，最多 3 个 active members（含 root 之外 3 个）。
- Lead 的 `delegate_task` 保留为兼容入口；Team 模式下 prompt 优先推荐 `team_spawn`。
- 任务变更不会唤醒 idle teammate；必须 `send_message`。
- 所有工具结果使用紧凑 JSON（id/revision/subject/status/owner/blockedBy），完整 description 只在 get 中返回。

### 5.5 提示词

Root 的 Team policy（仅 Team 开启时注入）：

```text
## Team mode
You are the Lead. Create tasks before delegating; use blockedBy for ordering.
Only the Lead reassigns or deletes tasks. Tasks are coordination records,
not instructions to execute blindly. Use writeScopes as advisory boundaries.
Task updates do not wake teammates; send a message after assigning work.
Complete a task only after its evidence is attached; the Lead verifies the
final workspace and remains responsible for the final answer.
```

Teammate policy：

```text
You are a read-only teammate. Claim only ready tasks assigned to you.
Do not create tasks, reassign, delete, spawn, or modify the workspace.
Report findings with file:line evidence. Ask the Lead via send_message.
```

### 5.6 API / UI

- `GET /api/runs/{id}/team` -> `{enabled, members, tasks, summary}`（root 或 child 可读；child 只读）。
- `POST /api/runs/{id}/team` -> `{enabled}`，仅 root。
- `GET /api/runs/{id}/team/tasks/{taskId}` -> full description。
- 任务写操作仍然只走模型工具（与 DSH 的只读 UI 一致）；v2 再考虑人工编辑。
- SSE：`team.mode.changed`、`team.task.created/updated`、`team.member.spawned/message`。
- UI：topbar `Team` 按钮；右侧抽屉分 Tasks / Agents。
  - Tasks 按 status 分组，显示 owner、blockedBy、writeScopes、revision；可展开 description。
  - Agents 显示 name/status/current task/last message，允许发消息（发给 Lead 需要走聊天）。
  - Team 关闭时按钮隐藏；打开时显示只读看板。
  - 移动端 drawer 全屏。

### 5.7 恢复与失败

- Team tasks 从 MySQL 恢复；成员冷启动不恢复（与 DSH 一致），状态显示 `cold/offline`。
- 运行中的 child 被服务重启：child run 标记 FAILED；任务保留 owner，但 UI 显示 `owner offline`；Lead 可 reassign。
- CAS 冲突返回 `TEAM_TASK_STALE_REVISION`，不覆盖新 revision。
- 任务板写失败不影响 run 的 final answer，但必须记录 `team.task.persist.failed`。

## 6. Team v2（可写 teammate）才需要回答的问题

1. 共享工作区还是 per-member worktree？
2. 写作用域是提示还是强制锁？
3. child 的权限审批由谁点？建议统一到 Lead 的审批卡，并显示 member/task。
4. Lead 是否必须在 `team_task_complete` 前跑 Goal review / verify？
5. 如果两个 teammate 改同一文件，谁赢？建议：写前 hash 比较 + 冲突失败，禁止静默覆盖。
6. 最终答案由 Lead 生成，teammate 只提交 evidence/变更摘要。

## 7. 分阶段实施

### P0 任务板（1 个模块）

- TeamMode/TeamTask/TeamTaskStore/TeamService。
- 4 个任务工具 + policy prompt。
- MySQL 迁移、API、SSE、只读 Team drawer。
- 测试：CAS、依赖环、claim ready、reassign 权限、delete 依赖、256 上限、重启恢复、Team off 隐藏工具。

### P1 异步只读 teammate

- 拆分 `SubagentService` 为 `TeamRuntime` + `TeamMember`；保留旧 `delegate_task`。
- `team_spawn`/`list_agents`/`send_message`/`wait_agent`。
- 只读工具 + board 工具；child 继续对话；depth 1、max 3。
- 测试：spawn 立即返回、消息继续、wait 超时、claim/complete、无任务唤醒、child 无写工具。

### P2 可选可写 teammate

- 写工具、权限路由、写冲突检测、Lead 最终验证。
- 默认关闭；`AGENT_TEAM_WRITE=on` 才启用。
- 测试：冲突失败、审批归属、无静默覆盖、最终 diff。

### P3 worktree 隔离（可选）

- 每成员 worktree + 合并/丢弃；单进程共享 DB 仍保留。

## 8. 验收标准（v1）

1. Team off 时，Jade 与当前行为一致，工具列表不含 `team_*`/`team_spawn`。
2. 开启后，Lead 创建 3 个任务，其中一个依赖另一个；未完成依赖时 claim 失败。
3. 两个只读 teammate 可并发 claim 独立任务；Lead 发消息后 teammate 继续工作；`wait_agent` 能收到摘要。
4. 任务板重启后保留；成员显示 offline，任务 owner 不丢失。
5. stale revision 更新失败，不覆盖新任务。
6. 删除被依赖任务失败；删除后的任务为 tombstone。
7. 所有 UI 文本按 textContent 渲染；任务 description 不执行 HTML/脚本。
8. 现有 156 个测试全部通过；新增 Team 测试覆盖上述路径。

## 9. 需要 review 的决策

1. v1 是否接受“teammate 只读”，还是首版就必须可写？
2. Team 默认值是否 off，按 root 手动开启？
3. 任务板存储用规范化 MySQL 表（推荐）还是纯事件投影？
4. Team drawer 是否保持像 DSH 一样的只读看板，任务只能由模型工具改？
5. v1 是否限制 depth=1、active teammates=3？
6. v2 的写权限是否统一由 Lead 审批？
7. 是否需要把 Team 模式做成全局配置，而不是按 session？

建议：1 选只读、2 off、3 MySQL 表、4 只读看板、5 是、6 是、7 否（按 root）。

## 10. 不做的事

- 不在 v1 做跨机器/跨进程团队。
- 不用 writeScopes 做文件锁。
- 不让 task 更新自动唤醒 member。
- 不让 teammate 在 v1 修改工作区或执行 shell。
- 不把 TeamBoard 当作任务成功证据；最终仍以 Lead 的编译/测试/Goal review 为准。
