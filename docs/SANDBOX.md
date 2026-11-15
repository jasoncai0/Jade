# Jade 沙箱方案（设计稿）

> 状态：方向已确认，待实现
> 日期：2026-11-15  
> 决策：**本地默认执行；沙箱作为可选插件，不强制。**
> 适用项目：`java-coding-agent`（Jade 1.0.0）

## 1. 决策

Jade 的主路径仍然是本地执行，和 Claude Code 终端版一致：

- 默认使用本机 shell、JDK、Maven、Python、Node、Go。
- 默认保留现有 macOS `sandbox-exec` 写/网络隔离，可用 `AGENT_SANDBOX` 调整。
- 不要求安装 Docker，不要求构建沙箱镜像，不因沙箱不可用而拒绝启动。
- Docker 沙箱是一个可选的 `SandboxProvider` 插件，由用户显式启用。

这解决两个问题：日常开发保留本地工具链和速度；需要更强隔离时，可以切换到容器后端。

**准确表述：** 当前本地模式是“本地执行 + 写/网络保护”，不是安全沙箱。只有启用 Docker 插件后，才获得容器级文件系统、进程、网络和资源隔离。

## 2. 为什么本地默认

- Java 项目依赖本机 JDK、Maven、`~/.m2`、数据库、端口和本地服务。
- 本地 Agent 的交互调试、热重启、IDE 和端口访问更直接。
- 沙箱会引入镜像体积、启动延迟、缓存、临时目录、端口映射等成本。
- 主流本地 Agent（Claude Code、Codex CLI、Gemini CLI）默认都在开发者机器上运行，沙箱是可选边界。
- 本项目是单用户本地 demo，强制容器带来的复杂度大于收益。

## 3. 当前本地模式

| 层 | 当前行为 | 边界 |
|---|---|---|
| 路径 | `Workspace` 路径 jail | 约束工具读写，不约束宿主机进程 |
| 命令 | `CommandSandbox` 调 `/usr/bin/sandbox-exec` | 允许全盘读；进程/内核共享 |
| 写 | 允许 workspace、`.agent-build/tmp`、`/dev/null` | 不是只读根文件系统，无磁盘配额 |
| 网络 | 默认拒绝，`AGENT_SANDBOX_NETWORK` 可开 | 无域名 allowlist/代理审计 |
| 资源 | 命令超时、输出截断 | 无 CPU/内存/PID 限制 |
| 工具链 | 宿主机 `PATH` | 换机器可能缺 Go/Python/Node |
| 生命周期 | 命令级进程 | 后台服务可能残留 |

本地模式继续作为默认，不改变上述事实，也不把它宣传成容器级隔离。

## 4. 插件边界

核心只依赖抽象，不依赖 Docker：

```text
SandboxProvider (SPI)
  id() -> "local" | "docker" | ...
  available() -> boolean
  create(SandboxSpec) -> SandboxSession

SandboxSession
  start()
  exec(argv, cwd, timeout)
  execShell(command, cwd, timeout)
  stop()
  info()
```

- `LocalSandboxProvider` 内置，始终可用，包装现有 `CommandSandbox`。
- `DockerSandboxProvider` 是可选插件；未安装/未启用时核心完全不感知 Docker。
- 插件发现：`ServiceLoader<SandboxProvider>`；可选 jar 放在 `plugins/`，启动命令加 classpath：
  `java -cp target/coding-agent.jar:plugins/jade-sandbox-docker.jar io.github.jasoncai0.agent.CodingAgentApplication`
- 单 jar 启动方式不变；没有插件时行为与现在一致。
- 插件失败不能影响本地启动；只有用户显式选择 `docker` 时才尝试加载。

### 4.1 配置

| 变量 | 取值 | 默认 | 说明 |
|---|---|---|---|
| `AGENT_SANDBOX_BACKEND` | `local` / `docker` | `local` | 不设 `auto`，避免静默切换 |
| `AGENT_SANDBOX` | `auto` / `off` / `required` | `auto` | 仅控制 local 的 `sandbox-exec` |
| `AGENT_SANDBOX_NETWORK` | `true` / `false` | `false` | local 模式网络开关 |
| `AGENT_SANDBOX_DOCKER_IMAGE` | `image:digest` | `jade-sandbox:1` | docker 插件使用 |
| `AGENT_SANDBOX_DOCKER_NETWORK` | `off` / `on` / `preview` | `off` | docker 插件网络策略 |
| `AGENT_SANDBOX_DOCKER_MEMORY` | `2g` | `2g` | 容器内存 |
| `AGENT_SANDBOX_DOCKER_CPUS` | `2` | `2` | CPU 配额 |
| `AGENT_SANDBOX_DOCKER_PIDS` | `256` | `256` | PID 上限 |
| `AGENT_SANDBOX_DOCKER_PREVIEW_PORTS` | `8080,3000,5173` | `8080` | 可选预览端口 |

兼容：

- `AGENT_SANDBOX` 的旧语义保持不变：`off` 不套 `sandbox-exec`，`auto` 可用时套，`required` 不可用则拒绝启动。它只影响 local，不影响 Docker。
- `AGENT_SANDBOX_BACKEND` 未设置时固定 `local`；不根据 Docker 是否存在自动切换。
- `AGENT_SANDBOX_BACKEND=docker` 但插件缺失：启动失败并提示安装插件，不回退。用户显式选择时，失败要明确。

`/api/config` 和 `--check` 显示：当前 backend、插件是否加载、local 隔离状态、Docker 是否可用（仅提示）、网络模式。UI 显示 `sandbox: local-write-jail` 或 `sandbox: docker`，不写模糊的“已沙箱”。

## 5. 本地模式

`LocalSandboxProvider` 负责：

- 调用现有 `CommandSandbox.wrap` / `prepare`。
- 保留 `sandbox-exec` 写隔离、网络默认拒绝、TMPDIR/GOCACHE/JAVA_TOOL_OPTIONS 设置。
- 保留超时、输出上限、环境清洗和危险命令拦截。
- 不改变文件工具：`read_file`、`write_file`、`edit_file` 仍走 `Workspace`。

本地模式不承诺：

- 阻止读取 `~/.ssh`、`~/.aws`、`.env`。
- 阻止子进程继承宿主环境。
- 阻止恶意代码逃逸。
- 资源隔离。

## 6. 可选 Docker 插件

Docker 插件是“强隔离模式”，不是默认路径。

### 6.1 镜像

`sandbox/Dockerfile` 构建 `jade-sandbox:<version>`：

- Temurin JDK 21 + Maven 3.9
- Python 3.12 + pip
- Node.js 22 + npm
- Go 1.24 + gofmt
- 非 root 用户 `coder`（UID 10001）
- 预装常用依赖缓存，减少运行期下载

镜像固定 digest；不使用 `latest`；构建脚本 `scripts/build-sandbox-image.sh`，不提交镜像 tar。

### 6.2 容器规格

```text
docker run -d --rm
  --name jade-<runId>
  --label jade.session=<runId>
  --init
  --read-only
  --tmpfs /tmp:rw,noexec,nosuid,size=256m
  --cap-drop=ALL
  --security-opt=no-new-privileges
  --pids-limit=256
  --memory=2g
  --cpus=2
  --user 10001:10001
  --workdir /workspace
  -v <workspace>:/workspace:rw
  jade-sandbox:<digest> sleep infinity
```

- 不挂载 `~/.m2`、`~/.ssh`、`~/.gitconfig`、Docker socket。
- 不传 `DEEPSEEK_API_KEY`；LLM 调用仍在宿主机。
- 只挂载当前 workspace。
- 每会话一个容器，follow-up 复用；服务重启后按需重建。

### 6.3 网络与预览

- Docker 插件默认 `network=off`。
- `preview` 模式发布 `127.0.0.1:<ephemeral>`，只用于用户显式要访问的端口。
- 已实测：`--network none` 和 `--network internal` 下 `docker port` 不返回映射。因此“完全无网络”和“发布预览端口”不能同时默认成立。
- 若 Docker Desktop 无法同时满足，`preview` 明确标记为“允许出网”，UI 显示真实模式；不伪装成 `off`。
- 依赖下载不默认走公网；优先预装镜像，必要时提供 `proxy` 模式，后续再实现 allowlist 代理。

### 6.4 失败语义

- `backend=local` 且 `sandbox-exec` 不可用：按 `AGENT_SANDBOX` 语义处理；`auto` 降级为无 OS 沙箱并警告。
- `backend=docker` 且插件缺失/镜像缺失/容器启动失败：启动失败，明确提示，不回退。
- `backend=docker` 插件内部命令失败：返回工具错误；不自动切回本地执行，避免同一会话混用两种边界。
- 容器 OOM/超时/退出：采集 `docker inspect`，返回结构化错误。

## 7. 与执行链的关系

所有执行型路径统一走 `SandboxSession`：

- `CommandRunner`
- `Verifier`（javac/Maven、py_compile、node --check、go build）
- `run_command`
- `compile_code`
- `run_code`

文件工具不走沙箱会话，因为它们不执行用户代码，继续由 `Workspace` 做路径 jail。

## 8. 安全边界

本地模式：

- 有路径约束、写隔离、网络开关、超时。
- 无读隔离、无进程隔离、无资源隔离。
- 不能称为安全沙箱。

Docker 模式：

- 有容器级文件系统/进程/网络/资源隔离。
- 仍与宿主共享内核；Docker daemon 被攻破即失守。
- workspace 双向挂载是产品需求，不要在其中放密钥。
- 镜像供应链需要固定 digest 和扫描。

任何模式都不承诺 microVM 级隔离。

## 9. 实施计划

详见 `docs/superpowers/plans/2026-11-15-container-sandbox.md`。顺序调整为：

1. 引入 `SandboxProvider` / `SandboxSession`，内置 local provider，保持 34 个测试。
2. 增加插件加载与状态展示；没有插件时单 jar 行为不变。
3. `CommandRunner` / `Verifier` / 执行工具统一走 session。
4. 可选 Docker provider 与镜像；作为独立阶段，不阻塞本地默认。
5. 安全/回归测试、文档、演示。

## 10. 验收标准

- 默认启动不需要 Docker；`--check` 显示 `local`，34 个测试通过。
- 没有 `plugins/` 时，Jar 正常启动，功能与现在一致。
- 设置 `AGENT_SANDBOX_BACKEND=docker` 且插件存在时，执行走容器；没有插件时明确失败。
- 本地模式仍然是 host toolchain；Docker 模式不依赖宿主机 Go/JDK/Python/Node。
- 本地写隔离、超时、网络开关行为不回退。
- Docker 模式通过只读根、非 root、资源限制、网络 off 和工作区挂载测试。
- 不把本地模式描述成容器级沙箱。
