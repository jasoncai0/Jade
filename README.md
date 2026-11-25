# Jade — a local coding agent

Repository: <https://github.com/jasoncai0/Jade>

Jade is a small, runnable coding agent written in Java. It calls the DeepSeek
chat-completions API, runs locally, and works on real files. A run can create a
Java, Python, JavaScript, or Go project, compile or syntax-check it with the real
toolchain, execute it, read the real stdout, repair failures, and preserve the
transcript.

It is deliberately not Spring Boot. The whole application is a shaded jar with
one external runtime dependency (Jackson) plus the JDK HTTP server and client,
so startup is one command.

## Requirements

- JDK 21 or newer (`java`, `javac` on PATH)
- Maven 3.9+
- Python 3 (`python3` on PATH) for Python workspaces
- Node.js 18+ (`node` on PATH) for JavaScript workspaces
- Go 1.22+ (`go` on PATH) for Go workspaces
- A DeepSeek API key for real model calls

The project was developed and tested with JDK 23.0.2 and Maven 3.9.9.

## Quick start

```bash
cd java-coding-agent
cp .env.example .env
# edit .env and set DEEPSEEK_API_KEY=sk-...
docker compose up -d   # MySQL 8.4 on 127.0.0.1:3307
./run.sh
```

Open <http://127.0.0.1:8080>. `run.sh` packages `target/coding-agent.jar` when
the source is newer than the jar, then starts the web console.

Check the environment before starting:

```bash
./run.sh --check
./run.sh --ping          # one real API round-trip; requires a key
```

If the key is missing, the console still starts, but the Start button is
disabled. The key is read by the server only; it is never sent to the browser
or written to logs.

## Headless use

```bash
./run.sh --task "Create a Go CLI at main.go that sorts a slice and prints it, then build and run it"
./run.sh --task-file task.txt
./run.sh --task "..." --workspace prime-demo
./run.sh --task "..." --max-steps 6
```

Exit code is `0` only when the run reaches `SUCCEEDED`. `--workspace` chooses
the directory name under `agent-workspaces/`; without it, Jade creates
`agent-workspaces/run-YYYYMMDD-HHmmss-xxxxxx`.

Other modes:

```bash
./run.sh --help
./run.sh --version
./run.sh --check
./run.sh --ping
./run.sh --port 9090
```

## What the agent can do

### Ten-phase loop

Every model turn executes these ten phases, in order:

| # | Phase | Responsibility |
|---|-------|----------------|
| 01 | Intake | Validate the task and initialize the run |
| 02 | Context | Load workspace state and the current transcript |
| 03 | Skills | Select relevant `SKILL.md` playbooks |
| 04 | Plan | Publish or update the visible plan |
| 05 | Model | Call DeepSeek with tool definitions |
| 06 | Tools | Execute requested tools, one result per call |
| 07 | Observe | Feed real output back into the transcript |
| 08 | Verify | Compile and run changed artifacts |
| 09 | Compact | Token-budgeted pinned memory, tool-output micro-compaction, model summary |
| 10 | Checkpoint | Persist transcript, events, and final answer |

There is no default model-turn cap: `AGENT_MAX_STEPS=0` means unlimited. A
positive value remains an optional explicit cap (up to 10000). Long runs are
bounded by a repeated-call guard and, optionally, a token budget. The budget
(`AGENT_TOKEN_BUDGET`) defaults to `0` = unlimited; set a positive value only
for unattended runs that need a hard cost ceiling. A loop guard stops four
identical tool calls with the same arguments, or eight occurrences of the same
command shape (numbers normalised) in the last ten calls. After eight no-progress
tool turns the framework injects a reflection checkpoint; after sixteen it stops
with `LOOP_DETECTED`. A guard stop is `LOOP_DETECTED` or `BUDGET_EXCEEDED`; the
workspace and transcript remain available for a follow-up. Ten phases still
describe exactly one model turn.

### Context compaction

Compaction triggers when either the estimated token budget
(`AGENT_CONTEXT_TOKEN_BUDGET`, default 30,000) or the character budget
(`AGENT_CONTEXT_CHAR_BUDGET`, default 120,000) is exceeded.

The pipeline is deterministic until the summary step:

1. Pin the system prompt, the first user task, and the group containing the
   latest user message.
2. Micro-compact large tool outputs older than the recent window: keep the
   first 3,000 and last 1,000 characters, and mark how much was omitted.
3. Drop oldest complete assistant/tool groups, keeping at least three recent
   groups.
4. Ask the model, without tools, to summarize the dropped groups into pinned
   memory. The summary prompt asks for constraints, decisions, files, commands,
   failures, and remaining work. If the summary call fails, a deterministic
   note is used instead.
5. Truncate remaining long message contents if the budget is still exceeded.

The `compaction` event reports removed messages, micro-compacted tool outputs,
truncated messages, before/after characters and tokens, and summary token
usage. A summary call costs one extra model request only when compaction drops
history; it never calls tools or modifies the workspace.

### Session history and follow-ups

Sessions are retained on disk and come back after a server restart. The web
console lists up to the 200 most recent saved sessions, and an old session can
still be selected to inspect its plan, trace, verification output, files, and
final answer. A selected terminal session accepts follow-ups in the same
workspace: type in the composer, send, and the next turn appends to the same
transcript and event stream. The durable files are:

```
agent-workspaces/<name>/
  src/...                 artifact files
  .agent/transcript.json  run snapshot + model conversation
  .agent/events.jsonl     full replayable event stream
  .agent/final.md         final answer, when present
```

Session history is stored in MySQL 8.4 (started by `docker compose up -d`;
host port `3307`). The access layer is MyBatis 3.5.19: `SchemaMapper` owns the
DDL, `SessionMapper` owns session snapshots, and `EventMapper` owns event
pagination and lookups. SQL lives in mapper XML under
`src/main/resources/io/github/jasoncai0/agent/history/mapper/`; the store uses
a pooled `SqlSessionFactory` instead of a shared JDBC connection.

Startup imports legacy `.agent/events.jsonl` files once, then restores snapshots
from `jade_sessions`. Live events are appended to `jade_events`; the compact
projection strips large LLM bodies and tool payloads, so loading a long session
only fetches a bounded tail. The Live Trace offers
`Load earlier events` for older pages and `Load full request body` for one
archived request. `AgentService` restores terminal sessions at startup and
rehydrates each event log with its original sequence numbers, so SSE replay
works after a restart.
A session that was still `RUNNING` when the previous process died comes back as
`FAILED` with an interruption message instead of pretending to be live.
Restored sessions can be continued: sending a follow-up reopens the event log,
appends a user message to the transcript, and runs another bounded turn in the
same workspace. `New run` creates a new isolated workspace. Delete a workspace
directory to remove that session permanently.

A terminal session can be forked: `POST /api/runs/{id}/fork` with an optional
`{"name"}` copies the workspace and transcript into a new session and returns
`201` with `{run, parentRunId, eventsUrl}`. The fork keeps the parent's history,
plan, verification and spilled tool output, but future turns diverge: edits in
the fork never touch the parent. Build output, VCS metadata and dependency
directories are not copied, and the fork starts idle until the first follow-up
message. The console exposes this as the `Fork` button on a finished session;
the parent link is shown in the session list.

Delegation is the other way to keep a session small: the parent model can call
`delegate_task` with a question and a time budget. The child runs the same
ten-phase loop with `READ_ONLY` permissions, 40 steps and a 30k-token budget;
the parent receives one tool result (status, tokens, files read, summary) and
never the child's transcript. Child sessions appear in `/api/runs` with a
`sub-` id, so they can be inspected or streamed independently. They are
in-memory only when Jade runs without MySQL; with MySQL their snapshots survive
a restart like any other run.


### Goal mode

Goal mode is enabled by default (`AGENT_GOAL_MODE=true`). The original task is
pinned in the system prompt, and the plan stays visible through `update_plan`.
Before a run can become `SUCCEEDED`, an independent, tool-free review call
compares the final answer, plan, file list, and verification result with the
original task. A rejection is fed back with the missing requirements and the
model gets another attempt; three rejections end the run as `FAILED`. Disable
it with `AGENT_GOAL_MODE=false` for experiments that do not need the extra
review call. This costs one extra model request per successful completion.

### Permission checks

Tool calls are authorized before they execute. The default `balanced` mode
asks once per session before the first workspace write and the first generated
program run. Writes to sensitive paths (`.env`, `.git/`, `.ssh/`, `AGENTS.md`)
always ask. Dangerous commands and network access are classified separately.
A denied call returns a tool error to the model; it does not kill the run.

Modes:

| Mode | Behavior |
|------|----------|
| `balanced` | First write and first execution ask; dangerous/network calls ask by risk |
| `strict` | Every write, command, and network call asks |
| `read-only` | Writes, commands, and execution are denied |
| `bypass` | Policy allows automatically; hard-denied commands still fail |

Set `AGENT_PERMISSION_MODE` or pass `--permission-mode`. The CLI also accepts
`--dangerously-skip-permissions` for `bypass`; it refuses automatically when
there is no TTY.

While a request is pending the run is `WAITING_APPROVAL` and the web console
shows an inline approval card. The API is:

```http
GET  /api/runs/{id}/permissions
POST /api/runs/{id}/permissions/{requestId}
{"decision":"allow","scope":"session"}
```

`scope` is `once` or `session`. Requests expire after 300 seconds and are
denied. Every decision is appended to `.agent/permissions.jsonl`; paths,
commands, and hosts are recorded, file contents and environment variables are
not. This is an authorization UX, not a security boundary: an approved command
can still read what your user account can read. The optional macOS sandbox can
add a write/network jail, but it is off by default for local execution.

### LLM request trace

Every model call emits a `llm.request` event with the exact JSON body sent to
DeepSeek: model, messages, tool schemas, temperature, and token limits. The web
console shows it as a collapsed **request body** card; click to expand. Bodies
are also persisted in the run's `.agent/events.jsonl`.

The trace contains no API key. Authorization is sent in an HTTP header outside
the JSON body and is never emitted or stored. The trace does contain the task,
conversation, and tool output, so treat a run directory as sensitive if the task
does.

### Structured output and strict tools

`ChatRequest` can request DeepSeek JSON mode and per-tool strict schemas:

```java
ChatRequest request = new ChatRequest(
    messages,
    List.of(new ToolSpec("emit_result", "Return the final result.",
        schema, /* strict */ true)),
    0.2,
    1024,
    ResponseFormat.JSON_OBJECT);
```

- `ResponseFormat.JSON_OBJECT` sends `response_format: {"type":"json_object"}`.
  It guarantees syntactically valid JSON, not your schema. The prompt must
  contain the word `json` and an example.
- `ToolSpec(..., true)` sends `"strict": true` inside that function. DeepSeek
  strict mode is a beta feature: all object properties must be required and
  `additionalProperties` must be `false`. Modal constraints such as `minLength`
  and `minItems` are not supported.
- When strict tools are present, the client routes an official
  `https://api.deepseek.com` base to `/beta/chat/completions` automatically.
  A custom gateway is left on its configured path; point it at a
  beta-compatible endpoint yourself.
- Set `strict` on every tool in the request when you want DeepSeek strict mode.
  The fourth argument defaults to `false`, so existing tools are unchanged.

JSON mode and strict schemas are transport constraints. They do not replace
post-write `javac` verification or runtime tests.

### Languages

Jade supports Java, Python, JavaScript, and Go workspaces. The primary language
is detected from the source files (most files; ties prefer Java, then Python,
then JavaScript, then Go), and one run targets one primary language:

| Language | Syntax/build check | Default entry | Generic tools |
|----------|--------------------|---------------|---------------|
| Java | `javac` or Maven | `Main` class | `compile_code`, `run_code` |
| Python | `python3 -m py_compile` | `main.py` | `compile_code`, `run_code` |
| JavaScript | `node --check` per file | `main.js` | `compile_code`, `run_code` |
| Go | `go build ./...` | `go run .` | `compile_code`, `run_code` |

`compile_java` and `run_java` remain as Java-only aliases. `run_code` can also
take an explicit `language` and `entry`, which is useful when a workspace has
several source files. Python, Node, and Go are discovered from `PATH`;
override them with the `PYTHON`, `NODE`, and `GO` environment variables if
needed. When a Go workspace has no `go.mod`, Jade creates a minimal module
(`module jade.local/workspace`, `go 1.22`) before build/run, and also uses
`GO111MODULE=off` as a fallback.

### Tools

Fifteen built-in tools are registered at startup:

| Tool | Purpose |
|------|---------|
| `list_files` | List workspace files as a tree |
| `read_file` | Read a text file with line numbers |
| `write_file` | Atomically create or replace a file |
| `edit_file` | Replace one exact fragment; fails on ambiguity |
| `search_text` | Literal or regex search with line numbers |
| `run_command` | Run a shell command with timeout and output capture |
| `compile_code` | Compile or syntax-check Java, Python, JavaScript, or Go |
| `run_code` | Compile if needed, then run the language entry point |
| `compile_java` | Java-only alias for the javac/Maven verifier |
| `run_java` | Java-only alias for running a main class |
| `update_plan` | Publish plan items and statuses |
| `load_skill` | Load a full skill playbook on demand |
| `reflect` | Record a structured diagnosis, evidence, attempts, next action, and stop decision |
| `ask_user` | Ask for a single/multiple choice or free-text decision and wait for the user |
| `read_tool_output` | Read a spilled tool output by path and character window |

Parent runs additionally get `delegate_task`, which is created per run rather
than registered globally: it starts one bounded, read-only child investigation
in the same workspace and returns only a summary plus the child run id. The
child sees `list_files`, `read_file`, `search_text`, `load_skill` and `reflect`;
it cannot write, run commands, compile, ask the user, or delegate again. At most
two children run per parent, depth is one, and each child has its own transcript
under `.agent/subagents/<childRunId>/` and its own SSE stream at
`/api/runs/<childRunId>/events`.

`ToolRegistry` also discovers external tools through `ServiceLoader`. See
[Extending Jade](#extending-jade).

### Skills

Skills are markdown playbooks with YAML frontmatter. Jade discovers every
`SKILL.md` under `skills/` (depth 3), puts a compact catalog in the system
prompt, preloads the two best matches for the task (each capped at 4,000
characters), and exposes `load_skill` for on-demand loading. Set
`AGENT_SKILL_PRELOAD=false` to keep only the catalog and load every body on
demand. Six playbooks ship with the demo:

- `skills/java-cli/SKILL.md` — build, compile, and execute a Java CLI
- `skills/java-tdd/SKILL.md` — failing check first, then implementation
- `skills/java-debug/SKILL.md` — compiler/runtime debugging discipline
- `skills/python-cli/SKILL.md` — py_compile and execute a Python CLI
- `skills/javascript-cli/SKILL.md` — node --check and execute a JavaScript CLI
- `skills/go-cli/SKILL.md` — go build, go test, and go run a Go CLI

Minimal skill:

```markdown
---
name: java-cli
description: Build and verify a standalone Java command-line program.
tags: [java, cli, javac]
triggers:
  - cli
  - command line
  - main method
---

# Java CLI

1. Inspect the workspace before writing.
2. Put the entry point in `src/Main.java`.
3. Call `compile_java`.
4. Call `run_java` with `mainClass=Main`.
5. Report only output you actually observed.
```

### Verification gate

A final answer is not trusted by itself. When the model stops calling tools and
any source was edited, Jade runs the real toolchain for the detected language:

- Java with `pom.xml`: `mvn -q -DskipTests compile` (or `./mvnw`)
- Java otherwise: `javac -encoding UTF-8 -d .agent-build/classes <sources>`
- Python: `python3 -m py_compile <sources>`
- JavaScript: `node --check <file>` for every `.js` source

If compilation or syntax checking fails, the tool output is appended as a user
message and the loop continues. The model must repair the source and finish
again. `run_code` (and the Java alias `run_java`) also compiles first when the
workspace is dirty, then executes the entry point and returns the real
stdout/stderr. A shell command invalidates an earlier verification, so `mvn
test` followed by a source edit cannot silently pass.

## Architecture

```
CodingAgentApplication
├── CLI (--task / --check / --ping / serve)
├── AgentService                  run registry, per-run workspace, virtual threads
│   └── AgentLoop                 ten phases per turn, bounded turns
│       ├── DeepSeekClient        OpenAI-compatible tool calling
│       ├── ToolRegistry          built-ins + ServiceLoader
│       ├── SkillRegistry         SKILL.md discovery and ranking
│       ├── ContextCompactor      budgeted memory pinning, tool-output micro-compaction, model summary
│       ├── Verifier              detect language; javac/Maven, py_compile, node --check
│       └── Workspace             path jail and bounded file IO
└── WebServer                     JDK HttpServer, REST envelope, SSE, static UI
```

A run is executed on a virtual thread. `RunEventLog` is the single source of
truth for the UI and the transcript; events are replayed from sequence number
`after`/`Last-Event-ID`. Each run gets its own directory under
`agent-workspaces/`; the API never exposes an absolute host path as a file
handle.

See [docs/ARCHITECTURE.md](docs/ARCHITECTURE.md) for the loop pseudocode,
failure paths, event model, and design trade-offs. The development retrospective
is in [docs/RETROSPECTIVE.md](docs/RETROSPECTIVE.md); the public version is
[the coding-agent pitfalls article](https://www.jasoncai.site/posts/coding-agent-pitfalls.html).

## Ask user decisions

The model can call `ask_user` when a product choice genuinely needs the user.
The run enters `WAITING_INPUT`, and the web console shows a fixed decision card
with radio buttons, checkboxes, or a free-text field. The answer is returned as
the tool result and the same session continues. Requests expire after 30
minutes; an unanswered request returns a tool error rather than failing the
run. Answers are appended to `.agent/questions.jsonl`.

The API is `GET /api/runs/{id}/questions` and
`POST /api/runs/{id}/questions/{requestId}` with
`{"selected":["opt-1"],"text":""}`. In a headless CLI, a TTY is required for
an answer; non-interactive runs cancel the run rather than guess.

## MCP servers and GitHub

Jade is an MCP **client**. Put a Claude-compatible `mcp.json` at the project
root (or point `JADE_MCP_CONFIG` at another file); the file is ignored by git.
MCP tools are discovered at startup and exposed to the model with stable names
such as `mcp_github_create_or_update_file`. Every MCP call still goes through
the permission gate, so a GitHub write requires an explicit approval.

```bash
cp mcp.json.example mcp.json
export GITHUB_PERSONAL_ACCESS_TOKEN=github_pat_...
# or put GITHUB_PERSONAL_ACCESS_TOKEN=... in the project .env
./run.sh
```

The example uses GitHub's remote Streamable HTTP server
`https://api.githubcopilot.com/mcp/`. A token needs repository access and the
toolset you intend to use; the agent can list repositories and create or update
files, but cannot do anything the token is not allowed to do. For a local stdio
server use `"type":"stdio"`, `"command":"..."`, and `"args":[...]` instead.
`GET /api/mcp` reports configured servers, connection errors and tool counts;
the Tools dialog lists each discovered MCP tool. When a server advertises the
MCP `resources` capability, Jade also registers three read-only tools:
`mcp_<server>_list_resources`, `mcp_<server>_list_resource_templates`, and
`mcp_<server>_read_resource` (URI required). Resource reads are capped at 30,000
characters; binary resources are reported as a base64-size placeholder.

MCP scope in this version is tools and resources (no prompts, sampling or
OAuth login). A server failure is reported and does not prevent Jade from
starting. Do not put a literal token in `mcp.json`; use `${VAR}` references.


## Long-term memory (Mem0)

Jade can recall durable project facts and explicit preferences across sessions
through a local Mem0 OSS sidecar. Memory is on by default
(`AGENT_MEMORY_MODE=on`); a missing or unreachable sidecar falls back to a
no-op, so a memory outage never blocks a run.

```bash
# Local sidecar: docker/mem0 (Mem0 API + FastEmbed BGE)
# API: http://127.0.0.1:8888
cd docker/mem0 && docker compose up -d
AGENT_MEMORY_MODE=on
AGENT_MEMORY_URL=http://127.0.0.1:8888
AGENT_MEMORY_USER_ID=jade-local-user
```

At the start of every run Jade searches project-scoped memories and injects a
small, clearly marked untrusted block into the system prompt. At a successful
checkpoint it extracts only explicit preferences/project decisions from the
user's message; credentials, `.env` content, and small talk are rejected.
`shadow` mode retrieves and logs candidates without injecting them; `off`
disables both paths. Project isolation is the default; global memories are
limited to explicit preferences and surfaced in the memory management dialog.

The Memory dialog lists, exports, and deletes memories. `GET /api/memory` and
`GET /api/memory/export` return the current project scope; `DELETE
/api/memory/{id}` removes one. Health is at `GET /api/memory/health`.

## Team and TeamBoard

Team mode turns a root session into a small agent team. It is **off by
default** and is enabled per session from the Team dialog or
`POST /api/runs/{id}/team`. Teammates share the root workspace, run in the
background, and have write/compile/run tools; the Lead keeps final
verification and approves child permission requests.

```bash
AGENT_TEAM_ENABLED=false   # default for new root sessions; toggle per session
```

The board is MySQL-backed (`jade_teams`, `jade_team_tasks`). Tasks have
`pending/in_progress/completed/deleted` status, revision-checked updates,
blocked-by dependencies, optional owner, and advisory write scopes. The eight
team tools are registered only while team mode is on:
`team_task_create`, `team_task_list`, `team_task_get`, `team_task_update`,
`team_spawn`, `list_agents`, `send_message`, `wait_agent`. Task changes do not
wake a teammate; messages do. The `Team` dialog is a read-only board with
members, task state, pending child approvals, and the enable toggle.
`wait_agent` has an observation-only timeout (default 600s, max 1800s): it does
not cancel or reassign work, and its result separates still-running/queued
members from completed/failed ones. It returns early when a teammate has a
pending permission request, so the Lead can surface the request instead of
waiting for the 300s approval timeout.

Team children share the workspace and are intentionally hidden from the
top-level session rail. Members are runtime-only: after a server restart, the
task board and enabled flag are restored from MySQL, but teammate processes are
not (the same cold-member limitation as the reference implementation).

## HTTP API

All JSON responses use:

```json
{"code": 0, "message": "ok", "data": {}, "traceId": "..."}
```

Errors use the HTTP status as `code` and `data: null`.

| Method | Path | Purpose |
|--------|------|---------|
| GET | `/api/health` | status, model, key presence, run count |
| GET | `/api/config` | non-secret runtime configuration |
| GET | `/api/tools` | registered tools and JSON schemas |
| GET | `/api/mcp` | MCP server status and discovered tool count |
| GET | `/api/runs/{id}/team` | team state, members, tasks, pending approvals |
| POST | `/api/runs/{id}/team` | toggle `{enabled}` for this session |
| GET | `/api/runs/{id}/team/tasks/{taskId}` | one team task |
| POST | `/api/runs/{id}/team/permissions/{requestId}` | Lead resolves `{decision, scope}` |
| GET | `/api/memory/health` | Mem0 sidecar health and mode |
| GET | `/api/memory` | project-scoped memories |
| GET | `/api/memory/export` | export project memories as JSON |
| DELETE | `/api/memory/{id}` | delete one memory |
| GET | `/api/skills` | discovered skills |
| GET | `/api/runs` | run snapshots, newest first |
| POST | `/api/runs` | start `{task, workspace?}`; returns 202 |
| POST | `/api/runs/{id}/messages` | follow up `{message}` in the same session; returns 202 |
| POST | `/api/runs/{id}/fork` | fork a finished session `{name?}`; returns 201 with `{run, parentRunId, eventsUrl}` |
| GET | `/api/runs/{id}/permissions` | pending approval requests |
| POST | `/api/runs/{id}/permissions/{requestId}` | resolve `{decision, scope}` |
| GET | `/api/runs/{id}/questions` | pending ask_user decision |
| POST | `/api/runs/{id}/questions/{requestId}` | resolve `{selected:[ids], text}` |
| GET | `/api/runs/{id}` | one run snapshot |
| POST | `/api/runs/{id}/cancel` | request cancellation |
| GET | `/api/runs/{id}/files` | workspace file listing |
| GET | `/api/runs/{id}/file?path=...` | read one text file |
| GET | `/api/runs/{id}/history?before=&limit=&compact=` | bounded event history page |
| GET | `/api/runs/{id}/history/{seq}` | one full archived event payload |
| GET | `/api/runs/{id}/events` | SSE stream with replay, plus live `assistant.delta` frames |

The server binds `127.0.0.1` by default. Do not expose it on a public interface:
there is no authentication and the agent can run shell commands in its
workspace.

## Extending Jade

### External tool provider

Implement the SPI and register it with `ServiceLoader`:

```java
package example;

import io.github.jasoncai0.agent.tool.*;
import io.github.jasoncai0.agent.llm.ToolSpec;
import java.util.List;
import java.util.Map;

public final class ClockTool implements Tool {
    @Override
    public ToolSpec spec() {
        return new ToolSpec("clock", "Return the current time.",
                Map.of("type", "object", "properties", Map.of()));
    }

    @Override
    public ToolResult execute(ToolContext context, Map<String, Object> args) {
        return ToolResult.ok(java.time.Instant.now().toString());
    }
}

public final class ClockProvider implements ToolProvider {
    @Override
    public List<Tool> tools() {
        return List.of(new ClockTool());
    }
}
```

Add this line to the provider jar:

```
META-INF/services/io.github.jasoncai0.agent.tool.ToolProvider
```

with the implementation class name. `ToolRegistry.discover()` runs at startup;
duplicate names are rejected instead of silently overwriting a built-in.

When testing an external provider jar, start with an explicit classpath instead
of `-jar` so `ServiceLoader` can see it:

```bash
java -cp target/coding-agent.jar:my-provider.jar \
  io.github.jasoncai0.agent.CodingAgentApplication
```

### New skill

Create `skills/<name>/SKILL.md` with the frontmatter above. Restart the server
to rediscover skills; the catalog and auto-selection include it immediately.

## Configuration

Environment variables take precedence over `.env`:

| Variable | Default | Meaning |
|----------|---------|---------|
| `DEEPSEEK_API_KEY` | empty | API key; required for real runs |
| `DEEPSEEK_BASE_URL` | `https://api.deepseek.com` | OpenAI-compatible base URL |
| `DEEPSEEK_MODEL` | `deepseek-chat` | model id |
| `DEEPSEEK_TIMEOUT_SECONDS` | `180` | request timeout |
| `DEEPSEEK_MAX_OUTPUT_TOKENS` | `8192` | response token cap |
| `AGENT_MAX_STEPS` | `0` | model turns per run; `0` = unlimited |
| `AGENT_MAX_TOOL_CALLS_PER_STEP` | `8` | executed calls per turn |
| `AGENT_TOKEN_BUDGET` | `0` | optional cumulative session token budget; `0` = unlimited |
| `AGENT_CONTEXT_CHAR_BUDGET` | `120000` | secondary character budget for compaction |
| `AGENT_CONTEXT_TOKEN_BUDGET` | `30000` | primary estimated-token budget; `0` disables the token trigger |
| `AGENT_COMMAND_TIMEOUT_SECONDS` | `120` | default command timeout |
| `AGENT_AUTO_VERIFY` | `true` | compile gate before final answer |
| `AGENT_GOAL_MODE` | `true` | independent goal review before success |
| `AGENT_SANDBOX` | `off` | `off` (default local execution), `auto`, or `required`; macOS write jail |
| `AGENT_SANDBOX_NETWORK` | `false` | allow network inside the sandbox |
| `AGENT_HOST` | `127.0.0.1` | HTTP bind address |
| `AGENT_PORT` | `8080` | HTTP port, `0` chooses a free port |
| `AGENT_SKILLS_DIR` | `skills` | skill root |
| `AGENT_SKILL_PRELOAD` | `true` | preload top-2 skill bodies (≤4,000 chars each); `false` = catalog only, load on demand |
| `AGENT_WORKSPACES_DIR` | `agent-workspaces` | run root |
| `AGENT_TEAM_ENABLED` | `false` | default for new root sessions; per-session toggle |
| `AGENT_MEMORY_MODE` | `on` | `on`, `shadow`, or `off`; missing sidecar falls back to no-op |
| `AGENT_MEMORY_URL` | `http://127.0.0.1:8888` | Mem0 OSS REST endpoint |
| `AGENT_MEMORY_PROVIDER` | `mem0-oss` | provider label |
| `AGENT_MEMORY_API_KEY` | empty | optional `X-API-Key` for Mem0 |
| `AGENT_MEMORY_USER_ID` | `jade-local-user` | Mem0 user scope |
| `AGENT_MEMORY_TOP_K` | `5` | max recalled memories |
| `AGENT_MEMORY_RECALL_TIMEOUT_MS` | `500` | recall timeout |
| `AGENT_MEMORY_WRITE_TIMEOUT_MS` | `2000` | async write timeout |
| `AGENT_MEMORY_TELEMETRY` | `true` | emit memory metric events |
| `JADE_MCP_CONFIG` | `mcp.json` | MCP server config; missing file disables MCP |
| `GITHUB_PERSONAL_ACCESS_TOKEN` | empty | GitHub PAT referenced by the example MCP config |

## Tests

```bash
mvn test
```

The suite has 163 tests:

- workspace traversal, symlink escape, binary/oversize rejection
- skill discovery, frontmatter parsing, ranking, duplicate handling
- tool registration, duplicate rejection, `ServiceLoader` discovery
- context compaction without orphaned tool messages; pinned memory, tool-output micro-compaction, and model summary
- goal review JSON parsing and rejection feedback before success
- MCP stdio/HTTP initialization, tool discovery, namespaced registration, calls, and env-expanded config
- DeepSeek request shape, JSON mode, strict tools, beta routing, and 401 behavior
  against a local mock HTTP server
- language detection and aliases; real Python `py_compile`, JavaScript
  `node --check`, Java `javac`, Go `go build`, and execution through the
  generic tools
- the full agent loop with a scripted model: real `javac`, `run_java`, a Python
  `run_code` path, Go build/run, and a compile-failure repair cycle
- unbounded-turn completion, four-repeat loop detection, command-shape
  normalisation, eight-turn reflection request + sixteen-turn no-progress stop,
  token-budget stop, and an explicit positive turn cap
- command sandbox write denial and SDK-directory exclusion
- HTTP API and SSE replay against a real local server and a fake model
- Team task CAS/dependencies/toggle, team spawn/wait/follow-up, and team API toggle
- Mem0 HTTP health/recall/write, API key propagation, scoped selection, secret rejection, and prompt-block replacement
- MySQL/MyBatis event-store pagination, compact LLM bodies, full-payload lookup, and batch import
  (skipped automatically when the Docker MySQL instance is not running)
- team children are hidden from the top-level run list
- restart retention: a finished run is reloaded with its events, transcript, and files;
  an interrupted run is restored as `FAILED`

`mvn package` builds `target/coding-agent.jar`.

## Security and limits

- Every run is isolated under `agent-workspaces/`; file access is path-jailed,
  including symlink resolution.
- File reads are capped at 512 KiB, writes at 1 MiB, command output at 200,000
  characters per stream, and tool output at 30,000 characters in the prompt.
- Local execution is the default: `AGENT_SANDBOX=off`. Commands run as your
  user without an OS jail, so Maven, Spring Boot, and localhost servers behave
  normally. Set `AGENT_SANDBOX=auto` to opt into the macOS `sandbox-exec` write
  jail (writes confined to the run workspace and a private temp directory,
  network denied unless enabled), or `required` to refuse startup when the jail
  is unavailable. The jail is not a container or VM; reads, process state, and
  the kernel are shared with the host.
- The shell still has a sanitized environment and a small destructive-command
  blocklist (`sudo`, `mkfs`, `dd` to block devices, fork bombs, `rm -rf /`).
  The Web UI shows a banner whenever the sandbox is not `workspace-write`, so
  local execution is visible rather than implicit.
- API keys are redacted from structured logs and omitted from `/api/config`.
- The HTTP server is unauthenticated; keep it on localhost.

## Project layout

```
src/main/java/io/github/jasoncai0/agent/
  CodingAgentApplication.java     entry point and modes
  agent/                          ten-phase loop, state, events, artifacts, verifier
  cli/                            argument parsing and headless console
  config/                          env/.env, limits, logging
  language/                       Java/Python/JavaScript/Go detection and entry points
  llm/                            DeepSeek client and wire records
  skill/                          SKILL.md discovery and ranking
  tool/                           registry, SPI, schemas, built-in tools
  web/                            JDK HTTP server, REST, SSE, static assets
src/main/resources/web/           console UI (no CDN)
skills/                           six skill playbooks
docs/                             architecture and demo script
```

## Limitations

- No authentication or multi-user tenancy; this is a local demo.
- No container or VM isolation. Local commands run as your user by default;
  the optional macOS sandbox is only a write/network jail, and reads/process
  state remain shared with the host. Permission prompts are an
  audit/authorization layer, not a kernel security boundary.
- No debugger integration (`jdb`, `pdb`, Node inspector). Debugging means
  reading compiler/runtime output, reproducing the failure, and repairing the
  source.
- Forks are copies, not branches: there is no diff, merge or reconciliation
  between a parent and its fork, and sibling forks cannot be combined
  automatically. A fork also copies the workspace at the moment it is created,
  so later parent edits are not visible to it.
- The live DeepSeek call is not part of the automated suite because it needs a
  user key; the HTTP layer is tested against a local fake endpoint instead.
