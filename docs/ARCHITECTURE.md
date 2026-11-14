# Architecture

Jade is a local, single-process coding agent. The design target is a demo that
is small enough to read in one sitting but still has the mechanisms a real
agent needs: a bounded loop, tool discovery, skill discovery, real execution,
a verification gate, context compaction, and an observable event stream.

## 1. Runtime topology

```
Browser (vanilla HTML/CSS/JS)
        │  REST + SSE (localhost)
        ▼
WebServer (com.sun.net.httpserver.HttpServer)
        │
AgentService (run registry, virtual-thread executor)
        │
AgentLoop ──────────────► DeepSeekClient ──► api.deepseek.com
   │                         (HttpClient)
   ├── ToolRegistry ──► built-in tools + ServiceLoader providers
   ├── SkillRegistry ─► skills/**/SKILL.md
   ├── ContextCompactor
   ├── Verifier ──────► javac / Maven / java
   └── Workspace ─────► per-run directory under agent-workspaces/
```

There is no database. Run snapshots live in memory for the process lifetime.
The durable artifacts are the run directory, `.agent/transcript.json`,
`.agent/events.jsonl`, and `.agent/final.md`.

## 2. Package map

| Package | Responsibility |
|---------|----------------|
| `io.github.jasoncai0.agent` | entry point, mode dispatch, shutdown |
| `agent` | loop, phases, run state, event log, compaction, verifier, artifacts |
| `cli` | options and headless console renderer |
| `config` | `.env`/environment loading, validated limits, JSON logging |
| `llm` | DeepSeek client, request/response records, usage |
| `skill` | `SKILL.md` parser, ranking, catalog |
| `tool` | tool contract, registry, SPI, argument helpers, built-ins |
| `web` | HTTP routing, API envelope, SSE, static asset serving |
| `workspace` | path-jailed file IO, file tree, guarded command runner |

## 3. The ten phases and the turn cap

The phrase "ten step loop" is represented at two levels:

1. **Ten phases per model turn.** Every turn emits `intake`, `context`,
   `skills`, `plan`, `model`, `tools`, `observe`, `verify`, `compact`, and
   `checkpoint` phase events, in that order.
2. **Ten model turns per run.** `AgentSettings.maxSteps` defaults to 10 and is
   hard-capped at 30 by validation. This is the outer bound; a run can finish
   earlier.

The model only participates in `model` and `tools`. The framework owns the rest
so that verification, compaction, and persistence cannot be skipped by a model
decision.

### Loop pseudocode

```
state.markRunning()
messages = [system(prompt + skill catalog + selected skills), user(task + tree)]
for turn in 1..maxSteps:
    emit intake/context/skills/plan
    result = llm.chat(messages, toolSpecs, temperature=0.2)
    add assistant(result); emit usage/assistant
    emit tools
    if result.toolCalls:
        execute up to maxToolCallsPerStep, one tool message per call
    emit observe
    emit verify
    if no tool calls:
        if finishReason == "length": ask model to continue
        else if !verifyNow(): append failure feedback and continue
    emit compact
    checkpoint transcript
    if no tool calls and verified:
        succeed; break
if not finished:
    verify dirty workspace if possible
    finish as max_steps
finally:
    persist transcript/events/final.md; emit run.finished; close log
```

`maxToolCallsPerStep` does not drop calls. Calls beyond the limit receive a tool
message explaining the limit, so the assistant/tool pairing stays valid.

## 4. Verification gate

`Verifier.verify()` is the only code path that decides whether a changed
workspace is buildable:

- If no Java source exists, return a skip result.
- If `pom.xml` exists, run Maven compile (prefer `./mvnw`, fall back to `mvn`).
- Otherwise, clear `.agent-build/classes` and run `javac` over all workspace
  `.java` files.
- `runMain()` puts `.agent-build/classes` (and `target/classes` when present) on
  the classpath and starts `java`.

The gate runs only when the model stops calling tools, and only if the workspace
is dirty or has been touched. A failed build is converted into a user message:

```
Automatic verification failed before the task can finish: <summary>
Inspect the exact compiler output, fix the source ... 
```

The model then gets another turn. A success sets `context.verified`; writing a
file or running a shell command clears that state, so a later final answer
cannot reuse a stale compile.

## 5. Tool registration and discovery

There are two layers:

1. **Static registration.** `BuiltInTools.all()` returns the ten built-ins.
   `BuiltInTools.registry()` registers each one and then calls
   `ToolRegistry.discover(...)`.
2. **ServiceLoader discovery.** `ToolRegistry.discover` loads every
   `ToolProvider` on the classpath and registers its tools. A provider is:

```java
public interface ToolProvider {
    List<Tool> tools();
}
```

The provider jar contains:

```
META-INF/services/io.github.jasoncai0.agent.tool.ToolProvider
```

`ToolRegistry` rejects duplicate names, including collisions with built-ins.
`execute()` catches runtime failures and turns them into an error `ToolResult`
so a malformed argument cannot kill the run.

`ToolContext` carries the workspace, settings, skills, run state, verifier, and
mutable flags (`dirty`, `touched`, `verified`). Mutating tools call
`markDirty`; shell tools call `markTouched`; compile/run tools can call
`markVerified`. This is how the gate knows whether a rebuild is needed.

## 6. Skill discovery

`SkillRegistry.load(root)` walks at most three levels looking for `SKILL.md`.
The parser supports a deliberately small YAML frontmatter subset:

```yaml
---
name: java-cli
description: Build and verify a Java CLI.
tags: [java, cli, javac]
triggers:
  - cli
  - main method
---
```

No YAML library is used, which keeps the jar small. Missing `name` falls back
to the parent directory; missing `description` falls back to the first
paragraph; missing `triggers` falls back to tags. Duplicate names keep the
first and log a warning.

Ranking is lexical: trigger hit ×3, tag hit ×2, name hit ×4. The top two
matches are injected in full into the system prompt. The full catalog is always
present, and `load_skill` can fetch any playbook on demand. Skills are
instructions, not executable tools.

## 7. Context compaction

`ContextCompactor` works on message groups: an assistant message and all of its
tool results form one group. It preserves:

- the system prompt at index 0
- the first user task at index 1
- the newest three groups by default
- a system note marking the dropped range

It drops oldest groups until the character budget is met. If a group is still
too large, long content is truncated. It never leaves a tool result without its
assistant call, because DeepSeek rejects orphaned tool messages.

## 8. Event model and SSE

`RunEventLog` is append-only and sequence-numbered from 1. `RunEvent` carries
`seq`, `timestamp`, `type`, and a non-null `data` map. The log supports:

- `snapshot()` for a current copy
- `after(seq)` for replay
- `awaitAfter(seq, timeout)` for streaming
- `closed()` and idempotent `close()`

The web endpoint sends standard SSE frames:

```
id: 42
event: tool.end
data: {"seq":42,"timestamp":...,"type":"tool.end","data":{...}}
```

`Last-Event-ID` or `?after=N` replays from that point. A 15-second comment keeps
proxies from closing idle connections. When the run log closes, the server sends
`event: stream_end` and ends the response.

Key events:

| Event | Data |
|-------|------|
| `run.started` | initial `snapshot` |
| `phase` | `phase`, `label`, `description`, `detail`, `step`, `maxSteps` |
| `llm.request` | exact request `body`, `step`, `messages`, `tools`, `chars` |
| `assistant` | `content`, `reasoning`, `finishReason`, `toolCalls` |
| `tool.start` | `id`, `name`, `arguments` |
| `tool.end` | `id`, `name`, `ok`, `output`, `durationMs`, `data` |
| `usage` | cumulative `usage` |
| `plan` / `workspace` | `plan` / `files` |
| `verification` | `ok`, `skipped`, `summary`, `command`, `output`, `durationMs`, `step` |
| `compaction` | `removed`, `beforeChars`, `afterChars` |
| `run.finished` | final `snapshot` |

Data values are never null; `AgentLoop.eventMap` filters nulls because
`RunEventLog.emit` uses `Map.copyOf`.

The `llm.request` body is the same JSON string posted by `DeepSeekClient`; it
contains messages, tools, model, temperature, and token limits. Authorization
headers are not part of the body and are never emitted. The web trace renders
this event as a collapsed `<details>` element so the conversation remains
readable until the user expands it.

`ChatRequest` optionally carries `ResponseFormat` (`text` or `json_object`) and
each `ToolSpec` carries a `strict` flag. `DeepSeekClient` serializes
`response_format` and the function-level `strict` field. Strict tool calling is
a DeepSeek beta feature; `LlmSettings.endpoint(boolean)` routes the official
`api.deepseek.com` base through `/beta/chat/completions` when any tool is
strict, while custom gateways keep their configured path. These are transport
constraints only: `javac` and the verification gate still decide whether an
artifact works.

## 9. Web and concurrency

`WebServer` uses the JDK `HttpServer` with a virtual-thread executor. The API
and static files share the same listener. Responses use the local backend
envelope:

```json
{"code":0,"message":"ok","data":...,"traceId":"..."}
```

Errors use the HTTP status as `code`. Request bodies are limited to 1 MiB and
are not logged. Static assets are served from the jar under `/web` with a
same-origin CSP; styles allow inline rules because the page is a single local
asset, scripts do not.

`AgentService` keeps a `ConcurrentHashMap` of runs and a set of active
workspace names. It rejects a second active run in the same directory. Each run
gets a virtual thread; cancellation interrupts the thread and is checked at
phase, tool, and model-call boundaries.

## 10. Failure behavior

| Failure | Result |
|---------|--------|
| Missing/invalid API key | run fails with a setup message; server still starts |
| HTTP 401 | no retry; `LlmException` status 401 |
| HTTP 429/5xx/IO timeout | up to 3 attempts, exponential backoff with jitter, `Retry-After` honored when ≤10s |
| Compile failure | verification failure fed back; run continues |
| Tool argument error | error tool message; run continues |
| Tool runtime exception | error tool result; run continues |
| User cancel | interruption, `CANCELLED`, transcript preserved |
| Turn cap reached | `MAX_STEPS`, transcript/workspace preserved |
| Artifact write failure | warning; run still returns its snapshot |

## 11. Security boundaries

- `Workspace.resolve` rejects absolute paths, NUL, `..` escapes, and symlinks
  that resolve outside the root.
- File reads are limited to 512 KiB and strict UTF-8; binary files are rejected.
- Writes are atomic and limited to 1 MiB.
- `CommandRunner` captures stdout/stderr separately, caps each at 200,000
  characters, and kills on timeout.
- The shell guard blocks a short list of destructive commands. It is not a
  container or OS sandbox. Documentation and the UI call this out.
- The web server is unauthenticated and localhost-bound by default.

## 12. Why these choices

- **No Spring Boot:** fewer moving parts, one jar, fast startup for a demo.
- **JDK HttpServer:** no framework dependency; enough for REST and SSE.
- **Jackson only:** typed records and explicit wire mapping.
- **ServiceLoader:** standard Java discovery; easy to demonstrate with a test
  provider and no custom classloader protocol.
- **Markdown skills:** readable, diffable, no parser dependency.
- **Two-level ten limit:** the requested ten-step loop is visible per turn, and
  the outer cap prevents an unbounded agent.
- **Verification gate:** the core promise is "compiled and ran", so a model
  sentence is never sufficient evidence.

## 13. Test strategy

The suite uses no real API key:

- unit tests for workspace, skills, tools, and compaction
- a local `HttpServer` fake for DeepSeek request/response shape
- a scripted `LlmClient` for the full loop, including a real `javac` failure
  and repair
- a real `WebServer` + `AgentService` + fake model for REST/SSE integration

`mvn test` is the authoritative check. `mvn package` adds the shaded jar.
