# Container Sandbox Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: use `executing-plans` to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking. Do not implement until the design decisions in this plan are approved.

**Goal:** Replace the current host-execution path with a per-session Docker sandbox, while keeping a clearly-labelled local fallback and preserving the existing 34 tests.

**Architecture:** Add a `SandboxManager` and `SandboxSession` abstraction. `CommandRunner`, `Verifier`, and every execution tool go through the session. A session owns one container, one workspace bind mount, one network policy, one resource budget, and zero or more loopback preview ports. File tools (`read_file`, `write_file`, `edit_file`, `search_text`) stay host-side behind the existing `Workspace` path jail.

**Tech Stack:** Java 21, Maven, Jackson, JUnit 5, Docker 28.3.2 (Docker Desktop/aarch64), Bash, macOS `sandbox-exec` only as the local fallback.

**Design source:** `docs/SANDBOX.md`.

---

## Global Constraints

- Do not pass `DEEPSEEK_API_KEY`, GitHub tokens, SSH agent, or host `~/.m2` into a container.
- Do not mount the Docker socket, `~/.ssh`, `~/.aws`, project root `.env`, or any path outside the run workspace.
- Do not execute untrusted code through `ProcessBuilder` once the Docker backend is active. Every execution path must share the same session abstraction.
- Do not silently fall back from `required` to local; warning is not enough for that mode.
- Do not claim microVM or “complete isolation”. The accurate claim is container-level filesystem/process/network/resource isolation.
- Keep `.env` and run directories out of git and out of blog/memory content.
- Existing behavior and 34 tests must remain green on the local backend.

## Provisional Decisions (to confirm before Task 1)

- **Default backend:** `auto` = Docker when the Docker CLI/daemon and image are available; otherwise local with an explicit warning. `required` fails closed. `local` and `docker` are explicit.
- **Image distribution:** build locally with `scripts/build-sandbox-image.sh`; tag `jade-sandbox:1`; do not auto-pull a floating tag.
- **Network default:** `off`. Dependency downloads are solved by a prewarmed image/cache, not by default internet. `proxy` and `preview` are explicit.
- **Preview:** publish an ephemeral `127.0.0.1` port. If Docker Desktop cannot combine preview ports with no egress, `preview` is documented as allowing egress; it must not be labelled `off`.
- **Local fallback:** keep it for development and unsupported hosts, but surface `sandbox: local-write-jail` in UI and `/api/config`.
- **Container idle timeout:** 900 seconds; stop on service close/cancel.

---

## Task 1: Sandbox abstraction and local backend

**Files:**
- Create `src/main/java/io/github/jasoncai0/agent/workspace/sandbox/SandboxSession.java`
- Create `src/main/java/io/github/jasoncai0/agent/workspace/sandbox/SandboxManager.java`
- Create `src/main/java/io/github/jasoncai0/agent/workspace/sandbox/SandboxSpec.java`
- Create `src/main/java/io/github/jasoncai0/agent/workspace/sandbox/SandboxInfo.java`
- Create `src/main/java/io/github/jasoncai0/agent/workspace/sandbox/LocalSandboxSession.java`
- Modify `src/main/java/io/github/jasoncai0/agent/workspace/CommandRunner.java`
- Modify `src/main/java/io/github/jasoncai0/agent/config/AgentSettings.java`
- Test `src/test/java/io/github/jasoncai0/agent/workspace/sandbox/SandboxManagerTest.java`

**Interfaces:**
- `SandboxSession.start(SandboxSpec)`, `exec(List<String>, Duration)`, `execShell(String, Duration)`, `stop()`, `info()`
- `SandboxManager.forRun(String runId, Workspace workspace)` returns a session
- `SandboxInfo(backend, active, containerId, networkMode, previewUrls, warning)`

- [ ] Define the spec with backend, image, network mode, CPU/memory/PID, idle timeout, writable root.
- [ ] Implement `LocalSandboxSession` by wrapping the existing `CommandSandbox` + virtual-thread output readers.
- [ ] Change `CommandRunner` to delegate to a session; keep old constructors as test compatibility shims.
- [ ] Add tests: local execution still works; duplicate start rejected; stop is idempotent; a failed start reports the backend.
- [ ] Run `mvn -q -Dtest=SandboxManagerTest,WorkspaceTest,CommandSandboxTest test`.
- [ ] Run full `mvn -q test` and confirm 34 tests pass.

## Task 2: Sandbox image and build script

**Files:**
- Create `sandbox/Dockerfile`
- Create `sandbox/README.md`
- Create `scripts/build-sandbox-image.sh`
- Create `src/test/java/io/github/jasoncai0/agent/workspace/sandbox/SandboxImageTest.java` (tagged `docker`, skipped when Docker is absent)

**Image requirements:**
- Base: pinned Temurin JDK 21 image digest.
- Maven 3.9, Python 3.12 + pip, Node 22 + npm, Go 1.24.
- `git`, `bash`, `curl`, `ca-certificates` only as needed.
- User `coder` UID/GID 10001; `HOME=/workspace/.agent-home`.
- Prewarm common Maven/Go/npm caches in a layer; no runtime downloads required for a hello-world.
- Healthcheck `java -version && python3 --version && node --version && go version`.

- [ ] Write the Dockerfile; pin every base image and tool version.
- [ ] Write the build script with `docker build --platform linux/arm64 -t jade-sandbox:1`.
- [ ] Add image smoke test: run `sh -lc 'java -version && go version'` as non-root; assert exit 0.
- [ ] Record image ID/digest in the build output; do not commit a tarball.
- [ ] Document the image size and rebuild command in `sandbox/README.md`.

## Task 3: Docker session lifecycle and command execution

**Files:**
- Create `src/main/java/io/github/jasoncai0/agent/workspace/sandbox/DockerSandboxSession.java`
- Create `src/main/java/io/github/jasoncai0/agent/workspace/sandbox/DockerCli.java` (thin, mockable command runner)
- Modify `SandboxManager.java`
- Test `src/test/java/io/github/jasoncai0/agent/workspace/sandbox/DockerSandboxSessionTest.java` (fake `DockerCli`)

**Lifecycle:**
- `start`: `docker run -d --rm --name jade-<runId> --label jade.session=<runId> ... sleep infinity`.
- `exec`: `docker exec -i <container> /bin/sh -lc <command>`; map host workspace to `/workspace`; reject cwd outside workspace.
- `stop`: `docker stop --time 2`, then verify removal; idempotent.
- Cleanup: `SandboxManager.cleanupOrphans()` lists `jade.session` labels and stops containers not in the run registry.

- [ ] Implement `DockerCli` with argv lists only, no shell interpolation on the host.
- [ ] Implement start/exec/stop and capture stdout/stderr/exit/timeout.
- [ ] Set labels, init, read-only root, tmpfs, cap-drop, no-new-privileges, resource limits, and env allowlist.
- [ ] Add fake-Docker tests for argv construction, timeout kill, start failure, and stop idempotence.
- [ ] Add an opt-in integration test (`-Ddocker.tests=true`) for a real container write/exec/stop.

## Task 4: Route verifier and tools through the session

**Files:**
- Modify `src/main/java/io/github/jasoncai0/agent/agent/Verifier.java`
- Modify `src/main/java/io/github/jasoncai0/agent/tool/builtin/RunCommandTool.java`
- Modify `src/main/java/io/github/jasoncai0/agent/tool/builtin/CompileCodeTool.java`
- Modify `src/main/java/io/github/jasoncai0/agent/tool/builtin/RunCodeTool.java`
- Modify `src/main/java/io/github/jasoncai0/agent/agent/AgentLoop.java`
- Test `src/test/java/io/github/jasoncai0/agent/agent/SandboxVerifierTest.java`

**Rules:**
- `Verifier` gets the session from `ToolContext`/`AgentLoop`, not a new `CommandRunner`.
- Java/Python/JS/Go commands execute inside the container; no host fallback when backend is Docker.
- `run_java`/`run_code` use container paths `/workspace/...`; host paths never leak into argv.
- File tools still use `Workspace`; `list_files` and trace paths show relative workspace paths.
- Test that a source edit followed by compile/run sees the same bind-mounted file.

- [ ] Add `ToolContext.sandboxSession()` and pass it to `Verifier`.
- [ ] Replace direct `CommandRunner` construction in all execution tools.
- [ ] Add tests for Java, Python, JS, and Go builds/runs inside a container.
- [ ] Assert no execution tool contains a direct `ProcessBuilder` call after the refactor (grep-based test or architecture test).
- [ ] Run full test suite; existing local backend tests should still pass.

## Task 5: Preview ports and continuous sessions

**Files:**
- Modify `RunSnapshot.java` to add `List<PreviewPort> previewPorts` (port, url, label)
- Modify `AgentService.java`
- Modify `WebServer.java` (`/api/config`, `/api/runs/{id}`)
- Modify `src/main/resources/web/index.html`
- Modify `src/main/resources/web/app.js`
- Test `src/test/java/io/github/jasoncai0/agent/web/PreviewApiTest.java`

**Behavior:**
- Container is created with only configured preview ports (`-p 127.0.0.1::8080` etc.).
- After `docker port`, expose `http://127.0.0.1:<mapped>`; UI shows a `Preview` link in the inspector.
- Background server command must return immediately and log to `.agent-build/server.log`.
- follow-up reuses the container; after service restart, recreate the container on the same workspace and surface the new port.
- On stop/cancel/idle, stop the container and remove the URL.

- [ ] Add port allocation and `docker port` parsing with unit tests.
- [ ] Add a fake-based test for preview URL in the run snapshot.
- [ ] Add a real integration test with a tiny Python HTTP server in a container; assert host 200.
- [ ] Add UI preview link and state cleanup.
- [ ] Verify port 8080/8081 collision avoidance.

## Task 6: Security, failure semantics, and acceptance tests

**Files:**
- Create `src/test/java/io/github/jasoncai0/agent/workspace/sandbox/SandboxSecurityTest.java` (opt-in Docker tests)
- Modify `AgentService.java`, `CodingAgentApplication.java`, `docs/ARCHITECTURE.md`, `README.md`, `docs/DEMO.md`, `docs/SANDBOX.md`

**Negative tests:**
- Container cannot read a host-only sentinel file (e.g. `~/.ssh` path is not mounted; assert `test -e` fails for a path that exists on host).
- Container cannot read project root `.env`; only the run workspace is mounted.
- Container cannot write outside `/workspace` (rootfs read-only).
- `network=off`: `curl` fails; no DNS/egress.
- `required` + missing Docker/image fails start; no degradation.
- OOM/timeout produces a structured tool error, not RUNNING forever.
- Container stop removes processes and preview ports.

- [ ] Add sentinel-based tests using temporary files outside the workspace.
- [ ] Add config/status surface: backend, image, network, limits, warning.
- [ ] Update docs with exact boundaries and the `local` fallback limitations.
- [ ] Run all tests, image smoke, live Go/Java/Python/JS, Spring Boot preview.
- [ ] Only after approval: commit and push.

---

## Review Focus

- The same session abstraction is used by every execution path; no hidden `ProcessBuilder` fallback.
- Host secrets are not mounted or inherited; env allowlist is explicit.
- `off` really means no egress; `preview` accurately reports whether egress is enabled.
- Resource limits and timeout cleanup prevent permanent RUNNING sessions.
- Existing local mode still works and is clearly labelled non-secure.
- No unapproved silent fallback from `required`.

## Execution Order

1. Task 1 + full tests (local backend parity).
2. Task 2 image build.
3. Task 3 Docker session + fake tests.
4. Task 4 route verifier/tools + real container tests.
5. Task 5 preview + continuous sessions.
6. Task 6 security/acceptance + docs.
