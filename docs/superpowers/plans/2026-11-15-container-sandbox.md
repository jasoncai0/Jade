# Sandbox Plugin Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: use `executing-plans` to implement task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking. Do not start until the direction below is approved.

**Goal:** Keep local execution as the default, make the existing local write/network guard explicit, and add an optional `SandboxProvider` plugin API so Docker can be enabled only when a user installs and selects it.

**Architecture:** Core `SandboxManager` chooses a provider. `LocalSandboxProvider` wraps the current `CommandSandbox` and preserves today's behavior. `DockerSandboxProvider` is an optional provider jar discovered through `ServiceLoader`, never required for startup. All execution paths (`CommandRunner`, `Verifier`, `run_command`, `compile_code`, `run_code`) share one `SandboxSession`.

**Tech Stack:** Java 21, Maven, Jackson, JUnit 5, current macOS `sandbox-exec`; Docker 28.3.2 only for the optional plugin.

**Design source:** `docs/SANDBOX.md`.

---

## Global Constraints

- Default startup must not require Docker, a sandbox image, or a plugin.
- `AGENT_SANDBOX_BACKEND=local` is the default and the behavior baseline.
- No silent switch from local to Docker. No `auto` backend.
- Local mode remains host toolchain execution; it must not be described as a container/sandbox.
- If `AGENT_SANDBOX_BACKEND=docker` is explicitly set and the plugin/image/daemon is unavailable, fail with an actionable error; do not fall back.
- Do not pass `DEEPSEEK_API_KEY`, SSH agent, `~/.ssh`, `~/.aws`, or Docker socket into a container.
- Preserve the existing 34 tests.
- Do not write to global memory; update `docs/BLOG_MEMORY.md` only when this work produces a reusable pitfall.

## Decisions

- **Local default:** existing `CommandSandbox` remains the local backend.
- **Plugin packaging:** `jade-sandbox-docker` is a separate optional jar; no Docker classes in the core jar.
- **Plugin discovery:** `ServiceLoader<SandboxProvider>` from the app classpath; optional jar can be added with `-cp`.
- **Network:** local uses `AGENT_SANDBOX_NETWORK`; Docker defaults to `off`; preview is explicit.
- **Strictness:** no global `required` backend. Docker is strict only when explicitly selected; local `AGENT_SANDBOX=required` retains its current meaning.
- **Deferred:** Docker provider and preview ports may be implemented after the local/plugin foundation; they do not block the default path.

---

## Task 1: Provider SPI and local provider

**Files:**
- Create `src/main/java/io/github/jasoncai0/agent/workspace/sandbox/SandboxProvider.java`
- Create `src/main/java/io/github/jasoncai0/agent/workspace/sandbox/SandboxSession.java`
- Create `src/main/java/io/github/jasoncai0/agent/workspace/sandbox/SandboxSpec.java`
- Create `src/main/java/io/github/jasoncai0/agent/workspace/sandbox/SandboxInfo.java`
- Create `src/main/java/io/github/jasoncai0/agent/workspace/sandbox/LocalSandboxProvider.java`
- Create `src/main/java/io/github/jasoncai0/agent/workspace/sandbox/LocalSandboxSession.java`
- Create `src/main/java/io/github/jasoncai0/agent/workspace/sandbox/SandboxManager.java`
- Modify `src/main/java/io/github/jasoncai0/agent/workspace/CommandRunner.java`
- Test `src/test/java/io/github/jasoncai0/agent/workspace/sandbox/LocalSandboxProviderTest.java`

**Interfaces:**
- `SandboxProvider.id()`, `displayName()`, `available()`, `create(SandboxSpec)`
- `SandboxSession.start()`, `exec(argv, cwd, timeout)`, `execShell(command, cwd, timeout)`, `stop()`, `info()`
- `SandboxManager.forRun(String runId, Workspace workspace)`; `manager.info()`

- [ ] Add `SandboxSpec` with workspace, backend, network enabled, writable root, timeout.
- [ ] Implement `LocalSandboxProvider` / `LocalSandboxSession` by moving the existing `CommandSandbox` logic behind the interface.
- [ ] Keep `CommandRunner`'s public constructors; internally delegate to a `SandboxSession`.
- [ ] Add tests: local execution, timeout, environment cleaning, duplicate start, idempotent stop.
- [ ] Run `mvn -q -Dtest=LocalSandboxProviderTest,CommandSandboxTest,WorkspaceTest test`.
- [ ] Run full `mvn -q test`; 34 tests must pass.

## Task 2: Plugin loading, config, and status

**Files:**
- Create `src/main/java/io/github/jasoncai0/agent/workspace/sandbox/SandboxPluginLoader.java`
- Modify `src/main/java/io/github/jasoncai0/agent/config/AgentSettings.java`
- Modify `src/main/java/io/github/jasoncai0/agent/config/AppConfig.java`
- Modify `src/main/java/io/github/jasoncai0/agent/agent/AgentService.java`
- Modify `src/main/java/io/github/jasoncai0/agent/CodingAgentApplication.java`
- Modify `src/main/java/io/github/jasoncai0/agent/web/WebServer.java`
- Test `src/test/java/io/github/jasoncai0/agent/workspace/sandbox/SandboxPluginLoaderTest.java`

**Behavior:**
- `AGENT_SANDBOX_BACKEND=local` (default) selects local without probing Docker.
- `AGENT_SANDBOX_BACKEND=docker` requires a discovered provider with id `docker`; otherwise startup fails with `Install jade-sandbox-docker.jar under plugins/ and add it to the classpath`.
- `SandboxPluginLoader` uses `ServiceLoader`; duplicate ids are rejected; built-in local wins for id `local`.
- `--check`/`/api/config` include `sandboxBackend`, `sandboxProvider`, `sandboxAvailable`, `sandboxDescription`.
- Add a `plugins/README.md` with the `-cp` example.

- [ ] Add config parsing and validation; default local, two allowed values.
- [ ] Implement ServiceLoader loading with clear errors.
- [ ] Add fake provider jars under `src/test/resources` and test discovery, duplicate, missing docker.
- [ ] Update `/api/config` and CLI check output; never print secrets.
- [ ] Run full test suite; no Docker required.

## Task 3: Route all execution through the session

**Files:**
- Modify `src/main/java/io/github/jasoncai0/agent/agent/Verifier.java`
- Modify `src/main/java/io/github/jasoncai0/agent/agent/AgentLoop.java`
- Modify `src/main/java/io/github/jasoncai0/agent/tool/ToolContext.java`
- Modify `src/main/java/io/github/jasoncai0/agent/tool/builtin/RunCommandTool.java`
- Modify `src/main/java/io/github/jasoncai0/agent/tool/builtin/CompileCodeTool.java`
- Modify `src/main/java/io/github/jasoncai0/agent/tool/builtin/RunCodeTool.java`
- Test `src/test/java/io/github/jasoncai0/agent/agent/SandboxRoutingTest.java`

**Rules:**
- `Verifier` receives `SandboxSession`; no direct `ProcessBuilder`.
- All four language commands run through the same session.
- File tools still use `Workspace`.
- A grep/architecture test asserts no execution tool constructs `ProcessBuilder` directly.
- Local behavior and 34 tests unchanged.

- [ ] Add session to `ToolContext` / `AgentLoop`.
- [ ] Refactor `Verifier` methods to use session commands.
- [ ] Refactor execution tools to use the same session.
- [ ] Add Java/Python/JS/Go tests in local mode.
- [ ] Full suite.

## Task 4: Optional Docker provider (separate artifact)

**Files:**
- Create `sandbox-docker/pom.xml`
- Create `sandbox-docker/src/main/java/.../DockerSandboxProvider.java`
- Create `sandbox-docker/src/main/java/.../DockerSandboxSession.java`
- Create `sandbox-docker/src/main/resources/META-INF/services/io.github.jasoncai0.agent.workspace.sandbox.SandboxProvider`
- Create `sandbox/Dockerfile`
- Create `scripts/build-sandbox-image.sh`
- Test `sandbox-docker/src/test/java/.../DockerSandboxProviderTest.java`

**Behavior:**
- Provider id `docker`; `available()` checks CLI only, not daemon health.
- Start container per session; reuse for follow-up; stop on close.
- Image includes JDK/Maven/Python/Node/Go; non-root; read-only root; workspace-only mount; resource limits.
- Network off by default; preview ports only when explicitly configured.
- Fake `DockerCli` tests; real Docker tests opt-in with `-Ddocker.tests=true`.

- [ ] Build the provider jar without changing the core jar.
- [ ] Add fake tests for argv, timeout, stop, missing image.
- [ ] Add image smoke test and real container test.
- [ ] Verify core still starts without the plugin.
- [ ] Verify `AGENT_SANDBOX_BACKEND=docker` without plugin fails clearly.

## Task 5: Docs, UI, and acceptance

**Files:**
- Modify `README.md`, `docs/ARCHITECTURE.md`, `docs/DEMO.md`, `docs/SANDBOX.md`
- Modify `src/main/resources/web/index.html`, `app.js`
- Test `src/test/java/io/github/jasoncai0/agent/web/SandboxStatusTest.java`

- [ ] UI shows `local-write-jail` by default; `docker` only when selected.
- [ ] Docs include plugin install and `-cp` command; no claim that local is a security sandbox.
- [ ] Acceptance: local starts without Docker; 34 tests pass; optional provider passes its tests; no secrets in config/logs.
- [ ] Do not commit `.env`, workspaces, or images.

---

## Review Focus

- The core jar has no compile/runtime dependency on Docker.
- Local remains default; no probing or silent switching.
- Explicit `docker` failure is actionable and does not fall back.
- All code execution paths use one session abstraction.
- Local security claims are accurate.
- Optional plugin can be removed and the app still works.

## Execution Order

1. Task 1 + Task 2, full tests.
2. Task 3, full tests.
3. Task 4 only if strong isolation is needed; otherwise leave as future.
4. Task 5 docs/acceptance.

Start with Task 1 only after approval.
