# Code Review — 2026-11-18

范围：Jade 1.4.0（`main`，UI 改版工作树）、13,303 行 Java、4,575 行 Web 资源。
方法：模块级走查 + 现有测试 + 浏览器手工回归，不是逐行安全审计。

## 结论

没有发现会阻止当前本地单用户使用的 P0 缺陷。UI 改版修复了 4 个实际交互问题；代码的主要风险集中在
“公网/多用户边界”“长时间运行的数据增长”“前端缺少自动化回归”和两个超大核心类。

当前验证：

- `mvn -q test`：143 tests，0 failures / 0 errors。
- `node --check src/main/resources/web/app.js`：通过。
- 浏览器回归：1440px 与 390px；空状态、Session/Trace 切换、Context 抽屉、历史分页、长任务标题。
- `git diff` 仅涉及 `AGENTS.md` 和 3 个 Web 文件；Java 后端未改。

## 本次 UI 改版修掉的问题

| 问题 | 根因 | 修法 |
|---|---|---|
| Trace 每次 snapshot 更新跳回 Session | `applySnapshot()` 内调用了 `setView('session')` | 移除；只在选会话、空状态、follow-up 时设置视图 |
| 长任务标题把会话内容顶出视口 | 4 行以上标题 + 浏览器聚焦 Tab 时滚动 `overflow:hidden` 容器 | 标题 4 行 clamp + `.run-view { overflow: clip; }` |
| 三栏改单列后 composer 被裁掉 | `body` 未约束 flex 高度，sandbox banner 额外占高 | body 固定 100vh column，app-shell `flex:1/min-height:0` |
| Checkpoint 文案重复 “Checkpoint Checkpoint — 1” | phase 标签和 phase detail 都渲染同一 label | 隐藏 `.phase-detail-label` |

## 发现

### P1 — 公共服务边界

1. **MCP 启动连接是串行阻塞的。**
   `AgentService.java:96` 在构造期调用 `McpManager.connect`；`McpManager.java:27` 逐台服务器连接，
   `connectWithRetry` 最多 5 次、退避到 30s。配置多个不可达 HTTP MCP 时，Jade 启动会被拖住数分钟。
   建议：启动只做配置加载，连接放到虚拟线程；`/api/mcp` 显示 `connecting`；单台超时不影响主服务。

2. **没有远端访问鉴权。**
   `WebServer.java:71` 直接 `HttpServer.create`，默认绑定 `127.0.0.1` 是正确的，但 `--host 0.0.0.0`
   没有 token/CSRF/Origin 校验。建议：非 loopback 启动默认拒绝，必须显式 `AGENT_ALLOW_REMOTE=1`
   并配置 bearer token；SSE 同样校验。

3. **没有 CI、没有 LICENSE。**
   仓库已公开（`jasoncai0/Jade`），当前测试只在本机跑；建议补 GitHub Actions：`mvn test`、`node --check`、
   secret scan（gitleaks），再决定 MIT LICENSE 或保留 all-rights-reserved。LICENSE 只放标准原文。

### P1 — 前端回归与可访问性

4. **新 UI 没有自动化测试。**
   `app.js` 1,982 行、CSS 2,329 行，Session/Trace、抽屉、审批卡、问题卡、历史分页都靠手工回归。
   建议加 Playwright：空状态、tab 切换不重置、Context 抽屉、长标题、390px 输入框可达。
   `static` 资源已有 `WebServerTest` 覆盖 HTTP，但不覆盖 DOM 行为。

5. **Context 抽屉缺焦点管理。**
   `setContextOpen` 只切 class/aria-expanded；没有 focus trap、打开后聚焦关闭按钮、关闭后恢复焦点。
   对纯鼠标使用没问题，对键盘/读屏用户不完整。modal 已有类似逻辑，可复用。

6. **移动端 composer 在空状态下落在首屏之外。**
   390px 下页面可滚动，但输入框需要先滚动才能看到。建议改成 sticky bottom composer，或压缩空状态 facts。

### P2 — 长会话与存储

7. **MySQL 事件没有保留/清理策略。**
   `MySqlEventStore` 只插入；`jade_events` 会随会话数线性增长。建议加按 run 的 TTL/归档命令，
   例如保留最近 90 天或最近 200 个会话，删除前先归档 `.agent`。
   `RunArtifacts` 的 legacy 文件也应与 MySQL 导入状态一致，避免重复扫描。

8. **Trace 仍是一次性 DOM 分页，不是窗口化。**
   每页 150 条已避免 12,786 条全渲染，但加载 10 页后仍会有 1,500 个节点。建议下一步按 turn 折叠，
   或只保留当前视口附近的 300 个节点。

9. **AgentLoop / AgentService 过大。**
   `AgentLoop.java` 1,007 行、`AgentService.java` 858 行。十阶段、工具执行、Goal、压缩、事件 emit 混在一起。
   建议按“turn 执行器 / 工具执行器 / 完成门禁”拆类，先补行为快照测试再重构。

### P2 — 产品与协议

10. **MCP 只覆盖 tools + resources。**
    prompts、sampling、OAuth、`tools/list_changed` 长连接续期都未实现；GitHub 写入只做了 mock 烟测，
    没有真实 PAT 回归。建议在 README 明确“GitHub 示例未实测”的边界。
11. **Goal review 固定一次额外模型调用。**
    对简单任务也走 review；可加 `goalMode=auto`，仅当任务含“设计/实现/测试”等长任务信号时启用。
12. **沙箱默认关闭是产品选择，不是安全保证。**
    `AGENT_SANDBOX=off` 时 `command` 直接使用宿主；文档已说明，但公共部署文档应把“localhost-only”放在首屏。

## 未来 TODO

### P0（下一轮先做）

- [ ] GitHub Actions：`mvn test` + `node --check` + secret scan。
- [ ] 非 loopback 启动保护：默认拒绝，显式 remote + bearer token。
- [ ] Playwright 覆盖 Session/Trace、抽屉、审批/问题卡、历史分页。
- [ ] 决定公开仓库 LICENSE。
- [ ] MCP 连接移出启动路径，增加 `connecting` 状态与单台超时。

### P1（1–2 个迭代）

- [ ] MySQL/工作区保留策略与归档命令。
- [ ] Trace 按 turn 折叠；超过 500 节点启用窗口化。
- [ ] Context 抽屉 focus trap + 移动端 sticky composer。
- [ ] AgentLoop / AgentService 拆分，先补 characterization tests。
- [ ] MCP prompts/OAuth/PAT 实测清单。
- [ ] Linux `bwrap` 沙箱（有 Linux 用户后再做）。

### P2（按产品需要）

- [ ] 多用户/租户鉴权。
- [ ] 会话搜索、标签、批量删除。
- [ ] MCP 工具注解驱动的只读自动放行（`readOnlyHint`）。
- [ ] 桌面壳 / 安装包。
- [ ] 插件市场与 Cordis 式扩展（非当前目标）。

## 验收标准

- CI 在 `main` 每次 push 通过；secret scan 零命中。
- UI E2E 覆盖 6 条核心路径，失败能定位到 tab/抽屉/卡片。
- 5 台 MCP 服务器中 1 台不可达时，Jade 启动 < 3s，`/api/mcp` 显示失败详情。
- 30 天/200 会话保留策略可 dry-run，删除前有归档路径。
- 拆分后的 `AgentLoop` 行为快照测试与 143 个现有测试全部通过。
