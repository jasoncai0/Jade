# Jade — a local coding agent

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
| 09 | Compact | Trim old context inside the character budget |
| 10 | Checkpoint | Persist transcript, events, and final answer |

The model-turn cap is a separate hard limit: `AGENT_MAX_STEPS=10`. A run may
choose to finish in fewer turns; it cannot exceed ten model turns by default.
This is intentional: ten phases describe one turn, while ten turns bound the
whole task.

### Session history

Runs are retained on disk and come back after a server restart. The web console
lists up to the 200 most recent saved sessions, and an old session can still be
selected to inspect its plan, trace, verification output, files, and final
answer. The durable files are:

```
agent-workspaces/<name>/
  src/...                 artifact files
  .agent/transcript.json  run snapshot + model conversation
  .agent/events.jsonl     full replayable event stream
  .agent/final.md         final answer, when present
```

`AgentService` restores terminal sessions at startup and rehydrates each event
log with its original sequence numbers, so SSE replay works after a restart.
A session that was still `RUNNING` when the previous process died comes back as
`FAILED` with an interruption message instead of pretending to be live. Restored
sessions are read-only history; starting a new task creates a new isolated
workspace. Delete a workspace directory to remove that session permanently.

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

Twelve built-in tools are registered at startup:

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

`ToolRegistry` also discovers external tools through `ServiceLoader`. See
[Extending Jade](#extending-jade).

### Skills

Skills are markdown playbooks with YAML frontmatter. Jade discovers every
`SKILL.md` under `skills/` (depth 3), puts a compact catalog in the system
prompt, preloads the two best matches for the task, and exposes `load_skill` for
on-demand loading. Six playbooks ship with the demo:

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
│   └── AgentLoop                 ten phases per turn, max ten turns
│       ├── DeepSeekClient        OpenAI-compatible tool calling
│       ├── ToolRegistry          built-ins + ServiceLoader
│       ├── SkillRegistry         SKILL.md discovery and ranking
│       ├── ContextCompactor      budgeted, tool-pair-safe trimming
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
failure paths, event model, and design trade-offs.

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
| GET | `/api/skills` | discovered skills |
| GET | `/api/runs` | run snapshots, newest first |
| POST | `/api/runs` | start `{task, workspace?}`; returns 202 |
| GET | `/api/runs/{id}` | one run snapshot |
| POST | `/api/runs/{id}/cancel` | request cancellation |
| GET | `/api/runs/{id}/files` | workspace file listing |
| GET | `/api/runs/{id}/file?path=...` | read one text file |
| GET | `/api/runs/{id}/events` | SSE stream with replay |

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
| `AGENT_MAX_STEPS` | `10` | model turns per run |
| `AGENT_MAX_TOOL_CALLS_PER_STEP` | `8` | executed calls per turn |
| `AGENT_CONTEXT_CHAR_BUDGET` | `120000` | compaction budget |
| `AGENT_COMMAND_TIMEOUT_SECONDS` | `120` | default command timeout |
| `AGENT_AUTO_VERIFY` | `true` | compile gate before final answer |
| `AGENT_SANDBOX` | `auto` | `auto`, `off`, or `required`; macOS write jail |
| `AGENT_SANDBOX_NETWORK` | `false` | allow network inside the sandbox |
| `AGENT_HOST` | `127.0.0.1` | HTTP bind address |
| `AGENT_PORT` | `8080` | HTTP port, `0` chooses a free port |
| `AGENT_SKILLS_DIR` | `skills` | skill root |
| `AGENT_WORKSPACES_DIR` | `agent-workspaces` | run root |

## Tests

```bash
mvn test
```

The suite has 32 tests:

- workspace traversal, symlink escape, binary/oversize rejection
- skill discovery, frontmatter parsing, ranking, duplicate handling
- tool registration, duplicate rejection, `ServiceLoader` discovery
- context compaction without orphaned tool messages
- DeepSeek request shape, JSON mode, strict tools, beta routing, and 401 behavior
  against a local mock HTTP server
- language detection and aliases; real Python `py_compile`, JavaScript
  `node --check`, Java `javac`, Go `go build`, and execution through the
  generic tools
- the full agent loop with a scripted model: real `javac`, `run_java`, a Python
  `run_code` path, Go build/run, and a compile-failure repair cycle
- command sandbox write denial and SDK-directory exclusion
- HTTP API and SSE replay against a real local server and a fake model
- restart retention: a finished run is reloaded with its events, transcript, and files;
  an interrupted run is restored as `FAILED`

`mvn package` builds `target/coding-agent.jar`.

## Security and limits

- Every run is isolated under `agent-workspaces/`; file access is path-jailed,
  including symlink resolution.
- File reads are capped at 512 KiB, writes at 1 MiB, command output at 200,000
  characters per stream, and tool output at 30,000 characters in the prompt.
- On macOS, commands run through `sandbox-exec` with writes confined to the run
  workspace and a private temp directory; network is denied by default. Set
  `AGENT_SANDBOX=required` to refuse startup when the jail is unavailable, or
  `off` to disable it. Reads, process state, and the kernel are shared with the
  host; this is a write/network jail, not a container or VM.
- The shell still has a sanitized environment and a small destructive-command
  blocklist (`sudo`, `mkfs`, `dd` to block devices, fork bombs, `rm -rf /`).
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
- No container or VM isolation. The macOS sandbox is a write/network jail;
  reads and process state remain shared with the host.
- No debugger integration (`jdb`, `pdb`, Node inspector). Debugging means
  reading compiler/runtime output, reproducing the failure, and repairing the
  source.
- No database or multi-session chat: completed runs are retained as read-only
  history; a follow-up starts a new isolated session rather than appending to
  the old transcript.
- The live DeepSeek call is not part of the automated suite because it needs a
  user key; the HTTP layer is tested against a local fake endpoint instead.
