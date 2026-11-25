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
   ├── Verifier ──────► javac/Maven, py_compile, node --check, go build, runtimes
   └── Workspace ─────► per-run directory under agent-workspaces/
```

There is no database. Live run snapshots live in memory, while completed runs
are durable: the run directory, `.agent/transcript.json`,
`.agent/events.jsonl`, and `.agent/final.md`. `AgentService` scans the workspace
root at startup, rehydrates up to 200 recent terminal runs, and reopens their
event logs in a closed state so the web console can replay them. A snapshot that
was still `RUNNING` at startup becomes `FAILED` with an interruption message.

## 2. Package map

| Package | Responsibility |
|---------|----------------|
| `io.github.jasoncai0.agent` | entry point, mode dispatch, shutdown |
| `agent` | loop, phases, run state, event log, compaction, verifier, artifacts, permission broker |
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
2. **No default number-of-turns cap.** `AgentSettings.maxSteps == 0` means
   unlimited; a positive value is an optional explicit cap (up to 10000). The
   session token budget (optional, unlimited by default) and repeated-call
   guard are the long-run safety nets. Use a positive budget only for
   unattended runs; interactive long tasks rely on compaction and cancel.

The model only participates in `model` and `tools`. The framework owns the rest
so that verification, compaction, and persistence cannot be skipped by a model
decision.

### Loop pseudocode

```
state.markRunning()
messages = [system(prompt + skill catalog + selected skills), user(task + tree)]
for turn in 1..∞ (or 1..maxSteps when a positive cap is configured):
    emit intake/context/skills/plan
    if token_budget > 0 and usage.total >= token_budget: stop BUDGET_EXCEEDED  # optional
    result = llm.chat(messages, toolSpecs, temperature=0.2)
    add assistant(result); emit usage/assistant
    emit tools
    if the same tool+args repeats 4x: emit tool errors, stop LOOP_DETECTED
    if a run_command shape repeats 8x in the last 10 calls: stop LOOP_DETECTED
    if result.toolCalls:
        execute up to maxToolCallsPerStep, one tool message per call
        if no file/verification progress for 8 turns: inject reflection prompt
        if no file/verification progress for 16 turns: stop LOOP_DETECTED
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
    persist transcript/events/final.md; emit run.finished
    # log stays open for follow-ups; AgentService closes it on shutdown
```

`run.finished` is emitted once per user turn, not once per session. The event log
remains open between turns so the browser keeps one SSE connection. A follow-up
reopens a restored session's log, appends the user message to the transcript,
and runs another bounded turn in the same workspace.

`AgentService.fork(parentId, name)` copies a terminal session instead of
continuing it. The new run gets its own id (`fork-*`), workspace, transcript and
event log; `RunSnapshot.parentRunId`/`forkSeq` record the lineage. Workspace
files are copied with `WorkspaceFork` (20,000 files / 200 MiB cap, symlinks
rejected, `.git`, `.agent`, build output and dependency directories skipped;
`.agent/tool-results` is copied so spilled output still resolves). Parent events
are replayed into the new log with `id`, `runId` and `workspace` rewritten, then
one `fork.created` event is appended. A fork is created idle: it accepts a
follow-up like any terminal session, but its later turns never touch the parent.

`AgentService` also owns one `SubagentService`. A parent run's registry gets a
per-run `DelegateTaskTool`; a child run's registry is rebuilt from a read-only
whitelist (`list_files`, `read_file`, `search_text`, `load_skill`, `reflect`).
`SubagentService` caps two live children per parent and depth one. Each child is
a real `AgentRun` in the same workspace with its own `RunState`, event log and
`READ_ONLY` permission broker, so a denied write becomes a tool error rather
than a parent failure. Child artifacts go to `.agent/subagents/<childRunId>/`
to avoid overwriting the parent transcript; the parent receives a summary,
child id, usage and up to 20 paths the child read.

`maxToolCallsPerStep` does not drop calls. Calls beyond the limit receive a tool
message explaining the limit, so the assistant/tool pairing stays valid.

## 4. Verification gate

`Verifier.verify()` is the only code path that decides whether a changed
workspace is buildable. It detects the primary language from the source files
(most files, tie order Java > Python > JavaScript > Go):

- If no supported source exists, return a skip result.
- Java with `pom.xml`: run Maven compile (prefer `./mvnw`, fall back to `mvn`).
- Java otherwise: clear `.agent-build/classes` and run `javac` over all `.java`
  files.
- Python: run `python3 -m py_compile` over all `.py` files.
- JavaScript: run `node --check` for each `.js` file.
- Go: ensure a minimal `go.mod` exists for module-less demos, then run
  `go build ./...`; `GO111MODULE=off` remains a fallback.
- `run()` executes `java <mainClass>` (Java), `python3/node <entry file>`
  (Python/JavaScript), or `go run .` / `go run <entry>` (Go). `runMain()`
  remains the Java-specific helper used by the `run_java` alias.

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

1. **Static registration.** `BuiltInTools.all()` returns the fifteen built-ins.
   `BuiltInTools.registry()` registers each one and then calls
   `ToolRegistry.discover(...)`. `delegate_task` is not in this list: it is
   registered per parent run because it needs the parent `AgentRun` and the
   `SubagentService`.
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
matches are injected into the system prompt, each capped at 4,000 characters;
longer bodies are replaced by the description plus a `load_skill` pointer. The
full catalog is always present, and `load_skill` can fetch any playbook on
demand. `AGENT_SKILL_PRELOAD=false` switches to catalog-only mode. Skills are
instructions, not executable tools.

## 7. Context compaction

`ContextCompactor` triggers on either an estimated-token budget
(`AGENT_CONTEXT_TOKEN_BUDGET`, default 30,000) or a character budget
(`AGENT_CONTEXT_CHAR_BUDGET`, default 120,000). The token estimator treats
ASCII as roughly four characters per token and non-ASCII as one token per
character, then adds per-message and tool-call overhead.

The pipeline:

1. Pin the system prompt at index 0 and the first user task at index 1.
2. Keep the group containing the latest user message; the newest three groups
   are always retained.
3. Micro-compact tool results larger than 6,000 characters: keep 3,000 leading
   and 1,000 trailing characters, insert an omission marker, and leave the
   assistant tool call intact.
4. Drop oldest complete assistant/tool groups until the budget is met.
5. If groups were dropped, call `ContextSummarizer` (production:
   `LlmContextSummarizer`) to turn the dropped groups into a pinned system
   message. The call is tool-free, uses `ResponseFormat.TEXT`, has a 1,200-token
   output cap, and counts its usage in the run.
6. If the summary call fails, insert a deterministic note. If the result is
   still over budget, truncate long non-pinned contents. Tool-call messages are
   never truncated, so a single oversized recent call can still exceed the
   budget.

The compactor never leaves a tool result without its assistant call, because
DeepSeek rejects orphaned tool messages. The `compaction` event includes
`removed`, `microCompacted`, `truncated`, before/after chars and tokens,
`summarized`, and `summaryTokens`.


## 7.1 Goal review

Goal mode pins the original task in the system prompt. The plan remains a
mutable checklist (`update_plan`), while `LlmGoalReviewer` performs one
read-only, tool-free model call before success. It receives the original task,
plan, file list, verification summary, and final answer, and returns strict JSON
`{aligned, missing, issues}`. A rejection appends feedback as a user message and
allows another attempt; three rejections set `FAILED`. The review call is never
allowed to modify the workspace, and it is skipped when `AGENT_GOAL_MODE=false`.

## 8. Ask user gate

`ask_user` is a blocking decision tool. It creates a `QuestionRequest`, sets
`WAITING_INPUT`, emits `question.request`, and waits up to 30 minutes. The Web
API resolves the request with selected option ids or free text. The tool returns
the answer as its result and the turn resumes. Answers are audited in
`.agent/questions.jsonl`. This is a UX decision point, not an execution
permission; `PermissionPolicy` auto-allows the tool.

## 9. Permission gate

Before a tool executes, `AgentLoop` calls `PermissionManager.authorize(tool, args)`.
The manager evaluates `PermissionPolicy` against the session's grants:

- read-only tools and safe commands return `ALLOW` immediately;
- a first write, generated-code execution, dangerous command, or network call
  returns `ASK` and blocks that tool call;
- hard-denied commands return `DENY` without an approval path.

An `ASK` registers a `PermissionRequest` with `PermissionBroker`, changes the run
to `WAITING_APPROVAL`, emits `permission.request`, and waits up to 300 seconds.
The web UI or CLI resolves it with `allow|deny` and scope `once|session`.
`permission.resolved` is emitted, the decision is appended to
`.agent/permissions.jsonl`, and the tool either executes or returns a tool error.
A denial is fed back to the model as a tool result; the run is not failed. A
timeout and cancellation are denials.

Permission sessions are per run and in-memory: a restart loses session grants.
The layer is authorization and audit, not a kernel sandbox; an approved command
still runs with the user's host permissions.

## 9.1 MCP tools

`McpManager` loads `mcp.json` (or `JADE_MCP_CONFIG`) and connects each enabled
server before the service starts accepting runs. Two transports are supported:

- **stdio** — child process with newline-delimited JSON-RPC; stderr is drained
  for diagnostics; a timeout closes the child.
- **Streamable HTTP** — one POST per JSON-RPC message, JSON or SSE responses,
  `Mcp-Session-Id` retained, `MCP-Protocol-Version` sent after initialization.

After `initialize`/`notifications/initialized`, Jade calls `tools/list` and
registers each tool as `mcp_<server>_<tool>` (sanitized, collision-suffixed,
64 chars max). `tools/call` results are flattened to text plus
`structuredContent`; `isError` becomes a tool error. Namespacing avoids
collisions with built-ins and ServiceLoader tools.

If the `initialize` result advertises `capabilities.resources`, `McpManager`
registers three more read-only tools per server: `mcp_<server>_list_resources`,
`mcp_<server>_list_resource_templates`, and `mcp_<server>_read_resource`
(`McpConnection.java:111-205`; `McpResourceTool.java:20`). Lists follow
`nextCursor` up to 200 entries. `resources/read` returns `contents[]`; text is
passed through, `blob` becomes a size placeholder so binary payloads never enter
the transcript. Servers without the capability keep exactly their old surface.

MCP tools are external actions, so `PermissionPolicy` sees an unknown tool and
asks in `balanced` mode. Approval scope is per run; a GitHub write therefore
requires an explicit decision. A failed server is recorded in `/api/mcp` and
skipped; it never prevents the rest of Jade from starting. Tokens are expanded
from environment variables at load time and are never written to events.

Startup connects use bounded backoff for HTTP servers: 5 attempts, 500 ms
initial delay, 30 s cap, no retry on HTTP 4xx or an invalid URL. `/api/mcp`
reports `attempts` per server. Stdio servers keep a single attempt.

MCP v1 deliberately covers tools and resources: no prompts, sampling,
elicitation, OAuth flow, or automatic server installation.

## 10. Durable history and SSE

`RunEventLog` writes every event to `EventStore` (`MySqlEventStore` in
production). The MySQL access layer is MyBatis 3.5.19: `SchemaMapper` creates
the tables, `SessionMapper` reads/writes snapshots, and `EventMapper` handles
event upserts, pages, and full-payload lookups. SQL is in mapper XML; the store
opens pooled `SqlSession`s and no longer shares a JDBC connection.
`GET /api/runs/{id}/history` reads at most 500 events before a
cursor and returns a compact projection: `llm.request.body` is replaced by a
2,000-character preview, `tool.start.arguments` is capped at 4,000 characters,
and `tool.end.output` at 8,000. The UI loads the newest 150 events, then
prepends earlier pages without re-running live state transitions. Full LLM
payloads are fetched only by `GET /api/runs/{id}/history/{seq}`.

Legacy `.agent/events.jsonl` files are imported once when the MySQL store is
empty. Restored snapshots come from `jade_sessions`; a `RUNNING` snapshot left
by a previous process is converted to `FAILED`.

## 11. Event model and SSE

`RunEventLog` is append-only and sequence-numbered from 1. `RunEvent` carries
`seq`, `timestamp`, `type`, and a non-null `data` map. The log supports:

- `snapshot()` for a current copy
- `after(seq)` for replay
- `awaitAfter(seq, timeout)` for streaming
- `subscribe(listener)` / `unsubscribe(listener)` for wake-ups after `emit`
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

Live model output arrives on a second, non-durable channel. `LiveDeltaBus`
holds the throttled `assistant.delta` frames (50 ms or 512 characters,
whichever comes first) and the SSE stream interleaves them with the durable
events:

```
event: assistant.delta
data: {"type":"assistant.delta","data":{"text":"Hello from ","reasoning":null}}
```

Delta frames carry no `id:` line and are never written to `RunEventLog`, MySQL,
or `.agent/events.jsonl`: `seq` counts durable events only. A reconnecting
client resumes at its last durable sequence and loses the deltas it missed; the
`assistant` event that follows the same model response is the authoritative
transcript entry, so the UI drops its live bubble when that event arrives.

Key events:

| Event | Data |
|-------|------|
| `run.started` | initial `snapshot` |
| `fork.created` | `parentRunId`, `parentSeq`, `workspace`, copied event count |
| `turn.started` | follow-up `message`, `snapshot` |
| `phase` | `phase`, `label`, `description`, `detail`, `step`, `maxSteps` |
| `llm.request` | exact request `body`, `step`, `messages`, `tools`, `chars` |
| `assistant` | `content`, `reasoning`, `finishReason`, `toolCalls` |
| `tool.start` | `id`, `name`, `arguments` |
| `tool.end` | `id`, `name`, `ok`, `output`, `durationMs`, `data` |
| `usage` | cumulative `usage` |
| `plan` / `workspace` | `plan` / `files` |
| `verification` | `ok`, `skipped`, `summary`, `command`, `output`, `durationMs`, `step` |
| `compaction` | `removed`, `microCompacted`, `truncated`, `beforeChars`, `afterChars`, `beforeTokens`, `afterTokens`, `summarized`, `summaryTokens` |
| `goal.review` | `aligned`, `missing`, `issues`, `usage` |
| `goal.rejected` | `attempt`, `missing`, `issues` |
| `reflection.requested` | `message`, `noProgressTurns`, `threshold`, `usage` |
| `reflection.recorded` | `step`, `arguments`, `usage` |
| `permission.request` | `id`, `runId`, `tool`, `kind`, `risk`, `summary`, `detail`, `target`, `options`, `createdAt`, `expiresAt` |
| `permission.resolved` | `id`, `decision`, `scope`, `reason` |
| `question.request` | `id`, `runId`, `question`, `detail`, `mode`, `options`, `createdAt`, `expiresAt` |
| `question.resolved` | `id`, `requestId`, `selected`, `text`, `timedOut`, `cancelled`, `question` |
| `run.finished` | final `snapshot` |

Data values are never null; `AgentLoop.eventMap` filters nulls because
`RunEventLog.emit` uses `Map.copyOf`.

The `llm.request` body is the same JSON string posted by `DeepSeekClient`; it
contains messages, tools, model, temperature, and token limits. Authorization
headers are not part of the body and are never emitted. The web trace renders
this event as a collapsed `<details>` element so the conversation remains
readable until the user expands it.

Terminal sessions are replayed from `.agent/events.jsonl`. If an older run has
no `run.finished` event, loading synthesizes one from `transcript.json` so the
restored timeline still ends with a final card. Restored logs start closed; a
follow-up calls `open()` and appends new events, while the UI reconnects from
its last sequence number. `stream_end` is sent only when the service shuts the
log down, not after each turn.

`ChatRequest` optionally carries `ResponseFormat` (`text` or `json_object`) and
each `ToolSpec` carries a `strict` flag. `DeepSeekClient` serializes
`response_format` and the function-level `strict` field. Strict tool calling is
a DeepSeek beta feature; `LlmSettings.endpoint(boolean)` routes the official
`api.deepseek.com` base through `/beta/chat/completions` when any tool is
strict, while custom gateways keep their configured path. These are transport
constraints only: `javac` and the verification gate still decide whether an
artifact works.

## 11. Web and concurrency

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

## 12. Failure behavior

| Failure | Result |
|---------|--------|
| Missing/invalid API key | run fails with a setup message; server still starts |
| HTTP 401 | no retry; `LlmException` status 401 |
| HTTP 429/5xx/IO timeout | up to 3 attempts, exponential backoff with jitter, `Retry-After` honored when ≤10s |
| Compile failure | verification failure fed back; run continues |
| Tool argument error | error tool message; run continues |
| Tool runtime exception | error tool result; run continues |
| User cancel | interruption, `CANCELLED`, transcript preserved |
| Turn cap reached (positive cap) | `MAX_STEPS`, transcript/workspace preserved |
| Repeated identical tool calls | `LOOP_DETECTED`, blocked calls get tool errors, transcript preserved |
| 8 tool turns with no file change and no new verification | `reflection.requested`; model gets a reflection prompt once per streak |
| 16 tool turns with no file change and no new verification | `LOOP_DETECTED`; no-progress guard; transcript preserved |
| Same command shape 8× in last 10 calls | `LOOP_DETECTED`; numeric arguments normalised |
| Session token budget exhausted | `BUDGET_EXCEEDED`, transcript preserved |
| Artifact write failure | warning; run still returns its snapshot |

## 13. Security boundaries

- `Workspace.resolve` rejects absolute paths, NUL, `..` escapes, and symlinks
  that resolve outside the root.
- File reads are limited to 512 KiB and strict UTF-8; binary files are rejected.
- Writes are atomic and limited to 1 MiB.
- `CommandRunner` captures stdout/stderr separately, caps each at 200,000
  characters, and kills on timeout.
- On macOS, `CommandSandbox` wraps shell commands in `sandbox-exec` with a
  write jail: writes are limited to the run workspace, `.agent-build/tmp`, and
  `/dev/null`; network is denied unless `AGENT_SANDBOX_NETWORK=true`.
  `AGENT_SANDBOX=required` fails closed if the jail is unavailable.
- Reads, process state, and the kernel remain shared with the host. This is not
  a container or VM; documentation and the UI call this out.
- `.toolchain`, `.sdk`, `.sdkman`, `.nvm`, `.rustup`, `.cargo`, `vendor`, and
  `third_party` are excluded from source detection, so an SDK unpacked into the
  workspace cannot be mistaken for project source.
- The web server is unauthenticated and localhost-bound by default.

## 14. Why these choices

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

## 15. Test strategy

The suite uses no real API key:

- unit tests for workspace, skills, tools, and compaction
- a local `HttpServer` fake for DeepSeek request/response shape
- a scripted `LlmClient` for the full loop, including a real `javac` failure
  and repair
- sandbox write denial and SDK-directory exclusion
- a real `WebServer` + `AgentService` + fake model for REST/SSE integration

`mvn test` is the authoritative check. `mvn package` adds the shaded jar.

## 10. Long-term memory (Mem0)

Jade treats memory as an optional sidecar, never as a second transcript. The
`memory` package defines `MemoryService`, with `Mem0HttpMemoryService` for the
local OSS REST API and `NoopMemoryService` for the fallback. `MemoryPolicy`
allows only explicit user preferences and project decisions; credentials and
small talk are rejected. `MemoryContext` injects the recalled block into the
first system message behind a fixed marker and labels it untrusted, so stale
memories cannot silently override the current task.

Recall runs in the Context phase and is bounded by the configured timeout. A
failed recall emits a metric event and continues. Writes run after a successful
checkpoint on a virtual thread, so Mem0 latency never extends the run. The
default scope is project isolation; global items are limited to explicit
preferences and are capped during selection. `AGENT_MEMORY_MODE` supports `on`,
`shadow` (retrieve and log without injecting), and `off`.

## 16. Team mode and TeamBoard

Team mode is session-scoped: a root run owns an enabled flag and a MySQL-backed
task board, and can spawn writable teammates that share its workspace. It is
off by default (`AGENT_TEAM_ENABLED=false`); the Team dialog or
`POST /api/runs/{id}/team` toggles it. A teammate is a normal `AgentRun` with
the `team-` prefix, its own event log and permission broker, and a persistent
same-session loop. It is not a one-shot read-only subagent.

`TeamService` owns the member registry and task operations. Tasks use a
revision CAS (`expectedRevision`), blocked-by dependencies, tombstones, and
advisory workspace-relative write scopes. They do not wake teammates; only
`send_message` does. Eight tools are registered only while the root is
enabled: `team_task_create`, `team_task_list`, `team_task_get`,
`team_task_update`, `team_spawn`, `list_agents`, `send_message`, `wait_agent`.
Teammates get write/compile/run tools; the Lead remains responsible for final
verification.

The board is exposed at `GET /api/runs/{id}/team`, with task and permission
sub-routes.

`wait_agent` is an observation-only wait. It returns early when a teammate
has a pending permission request, and on timeout it returns active
(`stillRunning`/`queued`) and idle (`completed`/`failed`/`idle`) member groups;
it does not cancel child runs or mutate task ownership. The Team policy tells
the Lead not to reassign or spawn duplicates for active members. The UI is intentionally read-only, like the reference
implementation: the Lead creates tasks through tools, and the dialog displays
members, task state, and pending child approvals. A child's permission request
is resolved through the Lead's Team dialog; scope `session` applies to that
child's run.

Membership is runtime-only. The task board and enabled flag persist in MySQL,
but after a restart teammates are not resurrected; tasks remain available for a
new team. Team children are hidden from the top-level session list.
