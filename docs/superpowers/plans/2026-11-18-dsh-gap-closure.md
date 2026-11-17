# DSH Gap Closure Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: use `executing-plans` to implement task-by-task. Do not start until the user has reviewed `docs/DSH-GAP-ANALYSIS.md` and approved this plan. This round writes no implementation code.

**Goal:** Close the five gaps that affect correctness and core interaction — context overflow safety, parallel read-only tools, streaming, session fork, and read-only subagent delegation — without turning Jade into a DSH clone.

**Architecture:** Keep the existing ten-phase `AgentLoop`, `ToolRegistry`, `PermissionManager`, MySQL `EventStore`, and local HTTP/SSE server. Add narrow seams: a tool-output spill store, an overflow-recovery path around the model call, a `concurrencySafe` tool flag, a streaming method on `LlmClient`, a fork operation on `AgentService`, and a read-only child-run service. No Cordis, no new runtime dependency, no storage rewrite.

**Tech Stack:** Java 21, Jackson, JDK `HttpServer`/`HttpClient`, MyBatis + MySQL 8.4, JUnit 5, vanilla JS.

**Design source:** `docs/DSH-GAP-ANALYSIS.md` (this round).

---

## Global Constraints

- Do not add a runtime dependency. Test-only libraries stay out unless already in `pom.xml`.
- Preserve all 73 existing `@Test`; every task adds tests before claiming completion.
- MySQL changes are additive and idempotent. No destructive migration, no table rename, no data backfill that blocks startup.
- Never write API keys, `.env` values, environment variables, or full file bodies into spill files, SSE, logs, or audit.
- Spill paths must stay under the run workspace; reject absolute paths, `..`, symlinks, and NUL bytes.
- Permission checks stay on the main loop thread. Parallelism must not bypass `PermissionManager`.
- Keep the existing SSE event names (`tool.start`, `tool.end`, `assistant`, `usage`) for compatibility; new events are additive.
- `mcp_<server>_<tool>` naming stays. Do not rename to DSH's `mcp__server__tool`.
- Each task is a separate commit/PR-sized change; do not combine tasks.
- This round: docs only. No Java/JS/Python, config, blog, or database changes.

---

## What to review now (P0 only)

**Scope for the first iteration:** Phase 1 = S1–S8 (Task 1 + Task 2), about 3 engineering days. No P1 code until this review and the Phase 1 report. The P1 designs below stay as proposals.

**Five decisions to approve or change:**

1. **Spill retention:** keep every tool-output file under `.agent/tool-results/<runId>/`, no auto-delete in v1; document a cleanup command. Alternative: 7 days / 100 MB per run.
2. **Inline window:** 20,000 head + 9,000 tail characters (30k total, matching today's cap). Alternative: 16k + 8k if 30k is too much context.
3. **Overflow policy:** one compaction + one retry per step; aggressive compaction may drop older detail. Alternative: fail fast and ask the user to start a new session.
4. **Parallel policy:** limit 4, whitelist `list_files/read_file/search_text/load_skill`; mutating, unknown, and MCP tools keep the whole batch serial. Alternative: limit 8, or only `read_file`.
5. **Rollback:** one commit per slice; keep a `parallelToolCalls` setting so flipping it restores the serial path; spill and overflow recovery have their own flags.

**Phase 1 success metrics** (reported after S8):

- 120k-char tool output: full text retrievable, head+tail inline, no hard failure.
- Simulated `context_length_exceeded` 400: one recovery, run succeeds; second overflow fails with an actionable message.
- 4 × 200ms read-only calls: parallel ≤300ms vs ~800ms serial; 5 calls never exceed 4 concurrent.
- `mvn -q test`: all existing 73 + new tests green; no new dependency; no SQL migration; no SSE rename.

**Reply with approve / changes.** Execution starts only after that.

---

## Task 1: Context safety net (P0)

**Goal:** A run survives a context-window error, large tool output keeps its tail, and the full output remains retrievable.

**Why first:** This is the only P0 that can turn a long, otherwise-successful run into a hard failure. It also removes the “test failure is in the truncated tail” class of wrong fixes.

### 1.1 Tool output spill + `read_tool_output`

**Design.**
- When a tool result exceeds `toolInlineChars` (default 30,000 characters, matching the existing `MAX_TOOL_OUTPUT_CHARS`), write the full UTF-8 text to:
  `<run-workspace>/.agent/tool-results/<runId>/<seq>-<tool>.txt`
- Inline result = head 20,000 chars + marker + tail 9,000 chars. Marker format:
  `\n... [output truncated: <originalChars> chars; full text: .agent/tool-results/<runId>/<seq>-<tool>.txt; read with read_tool_output] ...\n`
- If the spill write fails, keep the current head-only behavior and append `[spill failed: <reason>]`; never fail the tool call because of spill.
- Add `read_tool_output` built-in tool:
  - args: `path` (must start `.agent/tool-results/`), `startChar` (0-based, default 0, max 5,000,000), `maxChars` (1..65,536, default 16,384)
  - reads with `BufferedReader.skip`/`read`; does not load the whole file
  - returns text plus `{path, startChar, returnedChars, totalChars}`
  - resolves the path through `ToolContext.workspace()` and enforces the `.agent/tool-results/` prefix; no store injection is needed
- Retention: keep all files for now; document a manual cleanup command. Do not add a background reaper in this task.

**Files.**
- Create `J/src/main/java/io/github/jasoncai0/agent/agent/ToolOutputStore.java`
- Create `J/src/main/java/io/github/jasoncai0/agent/tool/builtin/ReadToolOutputTool.java`
- Modify `J/src/main/java/io/github/jasoncai0/agent/tool/builtin/BuiltInTools.java:13-27`
- Modify `J/src/main/java/io/github/jasoncai0/agent/agent/AgentLoop.java:37,508`
- Modify `J/src/main/java/io/github/jasoncai0/agent/tool/ToolContext.java` (optional accessor for the store)
- Test `J/src/test/java/io/github/jasoncai0/agent/agent/ToolOutputStoreTest.java`
- Test `J/src/test/java/io/github/jasoncai0/agent/tool/builtin/ReadToolOutputToolTest.java`

**Interfaces.**
```java
record ToolOutputRef(String relativePath, long bytes, int originalChars, boolean spilled) {}
final class ToolOutputStore {
    ToolOutputRef save(String runId, long seq, String toolName, String output);
    String read(String relativePath, long startChar, int maxChars);
    String inline(String output, ToolOutputRef ref);
}
```
`read` throws `WorkspaceException` for paths outside `.agent/tool-results/` and for non-regular files.

**Tests / acceptance.**
- 40k-char output spills; inline contains the first 20k, the last 9k, and the marker.
- The last line of a simulated test failure is present inline.
- `read_tool_output` returns characters 30,000–31,000 exactly; rejects `../`, absolute paths, and a symlink.
- Spill file permissions are owner-only where POSIX is available; path uses `CREATE_NEW` and does not follow links.
- Spill failure leaves the tool result usable.
- Existing tools and 73 tests unchanged.

**Risks.**
- Disk growth: document; do not silently delete. A later task can add retention.
- Secrets in tool output: spill is inside `.agent` (already git-ignored/skipped); do not add it to SSE. `read_tool_output` is model-facing, so the model can read what it already produced.
- Windows path handling: use `Path.resolve` + normalized prefix check; tests run on CI OS if available.
- Tool name is part of the filename: sanitize to `[A-Za-z0-9_.-]` and cap at 40 chars; fall back to `tool`.

**Dependencies:** none.

**Effort:** 1 day.

### 1.2 Overflow detection + compact-and-retry

**Design.**
- Extend `LlmException` with an optional `code` and a helper:
  ```java
  LlmException(String message, int statusCode, String code, Throwable cause)
  String code()
  boolean isContextOverflow()
  ```
  `isContextOverflow()` returns true for code `context_length_exceeded`, `CONTEXT_WINDOW_EXCEEDED`, or a message matching (case-insensitive) `context length|maximum context|context window|too many tokens|token limit`.
- `DeepSeekClient` parses `error.code`/`error.type` from a non-2xx JSON body (best effort) and passes it to `LlmException`. 400/401 remain non-retryable at the HTTP layer.
- Wrap the model call in `AgentLoop` (`AgentLoop.java:196-201`) with a one-shot recovery per step:
  1. catch `LlmException`;
  2. if `settings.overflowRecovery()` and `e.isContextOverflow()` and not already retried:
     - emit `context.overflow` with `{step, message, beforeTokens, beforeChars}`;
     - call `compactor.compactForOverflow(messages, summarizer)`;
     - emit `context.compacted` with `{removed, microCompacted, beforeTokens, afterTokens, summaryTokens}`;
     - retry the same model request once;
  3. otherwise rethrow.
- `ContextCompactor.compactForOverflow` (new method):
  - micro-compact every `TOOL` message over 2,000 chars to head 1,000 + tail 500;
  - drop oldest groups down to system + first user + latest user group (may keep fewer than 3 groups);
  - summarize dropped groups with `LlmContextSummarizer` (existing 1,200-token cap);
  - truncate non-tool messages to 4,000 chars;
  - if still over the configured token budget, return the best effort and let the retry decide.
- Add `overflowRecovery` to `AgentSettings` (default `true`, env `AGENT_OVERFLOW_RECOVERY`) and expose it in `--check`/`/api/config`. Preserve existing constructors with overloads.
- If the retry also fails, fail with the original message plus: `Context still exceeds the model window after one compaction; reduce the task or start a new session.`

**Files.**
- Modify `J/src/main/java/io/github/jasoncai0/agent/llm/LlmException.java`
- Modify `J/src/main/java/io/github/jasoncai0/agent/llm/DeepSeekClient.java:97-122`
- Modify `J/src/main/java/io/github/jasoncai0/agent/agent/AgentLoop.java:196-201,399-402`
- Modify `J/src/main/java/io/github/jasoncai0/agent/agent/ContextCompactor.java`
- Modify `J/src/main/java/io/github/jasoncai0/agent/config/AgentSettings.java`, `AppConfig.java`
- Test `J/src/test/java/io/github/jasoncai0/agent/agent/AgentLoopOverflowRecoveryTest.java`
- Test `J/src/test/java/io/github/jasoncai0/agent/agent/ContextCompactorTest.java` (new cases)
- Test `J/src/test/java/io/github/jasoncai0/agent/llm/DeepSeekClientErrorCodeTest.java`

**Tests / acceptance.**
- Fake `LlmClient` throws `LlmException(..., 400, "context_length_exceeded")` once, then returns a normal assistant message; the run succeeds; `context.overflow` and `context.compacted` are emitted exactly once.
- A second overflow fails the run; no third call.
- A non-overflow 400 still fails immediately; no compaction.
- A 429 still retries through the existing HTTP retry; overflow recovery does not double-retry.
- Aggressive compaction keeps the latest user message and the first user task; summary failure falls back to the deterministic note.
- `--check` prints `overflowRecovery=true/false`.

**Risks.**
- Provider error text varies; the regex is a heuristic. Keep the code check first; log the raw `code` but not the whole body if it may contain secrets.
- Aggressive compaction can lose detail; it is a last resort and must be visible in the event log.
- Infinite loop: one retry per step, hard-coded; test it.

**Dependencies:** none; 1.1 and 1.2 share `AgentLoop`/`ContextCompactor` and land in one PR.

**Effort:** 1 day.

### 1.3 Tail-preserving truncation at the loop boundary

**Design.**
- Replace `truncate(result.output(), MAX_TOOL_OUTPUT_CHARS)` at `AgentLoop.java:508` with `ToolOutputStore.inline(...)`; the old `truncate` remains for `llm.request` bodies and event fields.
- For outputs that do not spill, keep full text.
- For outputs that spill, head 20k + tail 9k as above.

**Tests / acceptance.**
- A 35k output contains both the first line and the final error line.
- A 29k output is unchanged.
- `tool.end` SSE payload uses the same inline text as the model message.

**Effort:** included in 1.1.

**Task 1 acceptance:** all above + `mvn -q test` green; manual smoke with a local mock returning one overflow.

---

## Task 2: Parallel read-only tool calls (P0)

**Goal:** When every tool call in a step is read-only, execute up to four concurrently; otherwise keep the current serial path.

**Design.**
- Add to `Tool`:
  ```java
  default boolean concurrencySafe() { return false; }
  ```
  Override `true` only for `ListFilesTool`, `ReadFileTool`, `SearchTextTool`, `LoadSkillTool`.
  Do not use `mutatesWorkspace() == false` as the predicate: `compile_code`/`run_code`/`compile_java`/`run_java` do not override it but mutate build outputs or verification state.
- Add `ToolRegistry.concurrencySafe(String name)`; unknown tool → false.
- Refactor `AgentLoop.executeTools`:
  - Phase A (main thread): apply `maxToolCallsPerStep`, parse args, emit `tool.start`, authorize, and build a list of `PreparedCall` (call, args, permission).
  - If `prepared.size() > 1` and every `tools.concurrencySafe(name)` is true and `prepared.size() <= 4`: submit to a fixed pool of 4; otherwise execute serially with the existing code.
  - Phase B (parallel): each worker calls `tools.execute(name, args, context)` and returns a `ToolOutcome` record. Do not call `context.setNetworkAllowed`, `markDirty`, `markTouched`, `markVerified`, `updatePlan`, or `refreshFiles` in workers.
  - Phase C (main thread): add tool messages in the original order, emit `tool.end` in the original order, update stats, call `context.refreshFiles()` once.
- If any worker throws, convert to `ToolResult.error` exactly as `ToolRegistry.execute` does; do not abort siblings.
- Cancellation: `control.checkCancelled()` before submit; workers check `control.isCancelled()` before starting. A cancelled run still waits for started reads to finish (bounded by file I/O); do not interrupt threads.
- Keep `maxToolCallsPerStep` semantics: skipped calls still produce the same `tool.end` error.

**Files.**
- Modify `J/src/main/java/io/github/jasoncai0/agent/tool/Tool.java:7-14`
- Modify `J/src/main/java/io/github/jasoncai0/agent/tool/ToolRegistry.java`
- Modify `J/src/main/java/io/github/jasoncai0/agent/agent/AgentLoop.java:458-529`
- Modify four built-ins: `ListFilesTool.java`, `ReadFileTool.java`, `SearchTextTool.java`, `LoadSkillTool.java`
- Test `J/src/test/java/io/github/jasoncai0/agent/agent/ParallelToolExecutionTest.java`

**Interfaces.**
```java
record PreparedCall(int index, ToolCall call, Map<String,Object> args) {}
record ToolOutcome(int index, ToolResult result, long durationMs) {}
```
The public `Tool`/`ToolSpec` JSON schema does not change.

**Tests / acceptance.**
- Two slow read-only fake tools with a `CyclicBarrier` both enter before either returns; elapsed < serial baseline.
- Five read-only calls run at most 4 at a time (track a max counter).
- One mutating call in the batch forces the entire batch serial (assert no overlap).
- Result messages and `tool.end` events remain in model order even when completion order differs.
- A denied permission is still denied before any worker starts; denied calls do not consume a worker slot.
- A worker exception becomes an error tool result; siblings still return.
- Cancellation before submit starts no worker.
- Existing 73 tests pass.

**Risks.**
- `ToolContext` is shared; the whitelist must not call mutable helpers. Enforce with a test that asserts the four tools only read `workspace`/`skills`.
- `RunEventLog.emit` is `synchronized` (`RunEventLog.java:54`), so events are safe; ordering is handled in Phase C.
- `SearchTextTool` may use a shared `Workspace`; `Workspace` is stateless aside from root, so concurrent reads are safe. Verify no cached state.
- MCP tools stay serial: `StdioMcpConnection` request/response correlation is not proven thread-safe.

**Dependencies:** Task 1 for the same `executeTools` block (sequencing only, no semantic dependency).

**Effort:** 1 day.

---

## Phase 1 execution slices (P0, review-gated)

This is the execution view of Task 1 + Task 2. **Do not start P1 until the user reviews the Phase 1 report.** The slice estimates sum to ~3 engineering days; the task-level estimates remain 2 days (Task 1) + 1 day (Task 2). A slice is one commit; if a slice exceeds its estimate by 50%, stop and report instead of expanding scope.

**Protocol for every slice**

1. Write the failing test first; run it and capture the failure.
2. Implement the smallest change that passes the slice tests.
3. Run the slice tests, then `mvn -q test` (all 73 + new).
4. Commit with `feat: <slice>` or `fix: <slice>`; no unrelated formatting.
5. Update this plan's checkboxes only after the full suite is green.

### S1 — Spill store, no wiring (3h)

**Deliverable:** `ToolOutputStore` can save and inline a large string; `AgentLoop` behavior is unchanged.

- Create `J/src/main/java/io/github/jasoncai0/agent/agent/ToolOutputStore.java`.
- Constructor takes the run workspace `Path`; `save(runId, seq, toolName, output)` creates `.agent/tool-results/<runId>/`, writes UTF-8 with `CREATE_NEW`, sets owner-only permissions when POSIX is available, and returns `ToolOutputRef`.
- `inline(output, ref)` returns `head 20,000 + marker + tail 9,000` when `output.length() > 30_000`; otherwise returns `output` unchanged.
- `read(relativePath, startChar, maxChars)` reads only the requested window; rejects paths outside `.agent/tool-results/`, `..`, absolute paths, NUL, symlinks, and non-regular files.
- Tests: 40k spills; 29,999 does not; marker contains chars/path; 40k inline starts with the first line and ends with the last line; sanitized tool name; spill failure is reported but does not throw from `inline`.

**Files:** `ToolOutputStore.java`; `ToolOutputStoreTest.java`.
**Gate:** `mvn -q -Dtest=ToolOutputStoreTest test`; no production wiring.
**Rollback:** delete the new class; no other code references it.

### S2 — `read_tool_output` tool (3h)

**Deliverable:** A model can page through a spill file without loading it whole.

- Create `ReadToolOutputTool`; register in `BuiltInTools.all()` (15th tool).
- Args: `path` (required), `startChar` (0..5,000,000), `maxChars` (1..65,536, default 16,384).
- Resolve via `ToolContext.workspace()`; enforce `.agent/tool-results/` prefix after normalization; return `{path, startChar, returnedChars, totalChars}` plus text.
- Tests: read chars 30,000–31,000 exactly; default window; start beyond EOF returns empty text + totalChars; `../`, absolute path, symlink, directory rejected; unknown tool output path rejected.

**Files:** `ReadToolOutputTool.java`; `BuiltInTools.java:13-27`; `ReadToolOutputToolTest.java`.
**Gate:** `mvn -q -Dtest=ReadToolOutputToolTest,BuiltInToolsTest test`.
**Rollback:** remove registration; the class can stay unused.

### S3 — Wire spill into the loop (3h)

**Deliverable:** Large tool outputs keep head + tail inline and the full text is retrievable; `tool.end` matches the model message.

- In `AgentLoop.executeTools`, after `tools.execute` and before `truncate`, call `spill.save(run.id(), eventSeq, call.name(), result.output())`; use `spill.inline(...)` for the tool message and `tool.end`.
- Keep `truncate` only for `llm.request` bodies/event fields.
- If `spill.save` fails, keep the current head-only truncation and append `[spill failed: ...]`.
- Tests: 35k output contains first and final error line; 29k unchanged; `tool.end.data.output` equals the model-visible inline text; no extra file for small output; spill failure path still returns a usable result.

**Files:** `AgentLoop.java:37,508`; `ToolOutputStore.java`; `AgentLoopSpillIntegrationTest.java`.
**Gate:** `mvn -q -Dtest=AgentLoopSpillIntegrationTest,AgentLoopTest test`.
**Rollback:** remove the two call sites; old `truncate` path is intact.

### S4 — Model error code (2h)

**Deliverable:** `LlmException` carries a provider code; overflow is recognizable.

- Add `code` + `isContextOverflow()`; keep the existing constructors as overloads.
- `DeepSeekClient` reads at most 8 KB of a non-2xx body, parses `error.code`/`error.type`, and passes it; never log the body wholesale.
- Keep 400/401 non-retryable; 429/5xx behavior unchanged.
- Tests: JSON `{"error":{"code":"context_length_exceeded"}}`; plain text body; 429 still retries; 401 no retry; message-only fallback.

**Files:** `LlmException.java`; `DeepSeekClient.java:97-122`; `DeepSeekClientErrorCodeTest.java`.
**Gate:** `mvn -q -Dtest=DeepSeekClientErrorCodeTest test`.
**Rollback:** add `code` as default null; `isContextOverflow()` returns false.

### S5 — Aggressive compaction (3h)

**Deliverable:** A one-shot compactor for overflow recovery, separate from the normal end-of-turn policy.

- `ContextCompactor.compactForOverflow(messages, summarizer)`: micro-compact tool messages over 2,000 chars to 1,000 + 500; drop oldest groups down to system + first user + latest user; summarize dropped groups; truncate non-tool messages to 4,000; return the same `Result` type.
- Add `overflowRecovery` to `AgentSettings` (default true, env `AGENT_OVERFLOW_RECOVERY`); preserve constructors with overloads; print in `--check`.
- Tests: latest user and first task survive; 3-group minimum is not enforced; summary failure yields deterministic note; 200k input returns under budget or best effort; normal `compact()` unchanged.

**Files:** `ContextCompactor.java`; `AgentSettings.java`; `AppConfig.java`; `ContextCompactorTest.java`.
**Gate:** `mvn -q -Dtest=ContextCompactorTest test`.
**Rollback:** leave the method unused; settings flag defaults true but only S6 reads it.

### S6 — One-shot overflow recovery (3h)

**Deliverable:** A 400 context-overflow triggers one compaction and one retry; a second overflow fails with a clear message.

- Extract `ChatResult callModel(...)` in `AgentLoop`; Task 3 later swaps its body to `llm.stream`, so recovery wraps one call site.
- Catch `LlmException`; if `overflowRecovery && isContextOverflow() && !retried`, emit `context.overflow`, call `compactForOverflow`, emit `context.compacted`, retry once.
- No retry on 401/403/429; no recursion.
- Tests: fake client throws overflow once then succeeds; events exactly once; second overflow fails; non-overflow 400 fails immediately; 429 still uses HTTP retry; latest user message is in the retried request.

**Files:** `AgentLoop.java:196-201,399-402`; `AgentLoopOverflowRecoveryTest.java`.
**Gate:** `mvn -q -Dtest=AgentLoopOverflowRecoveryTest test`.
**Rollback:** set `overflowRecovery=false`; behavior returns to today's fail-fast.

### S7 — Concurrency metadata (2h)

**Deliverable:** Tools declare safety; registry exposes it; the whitelist is explicit.

- Add `default boolean concurrencySafe() { return false; }` to `Tool`.
- Override true only in `ListFilesTool`, `ReadFileTool`, `SearchTextTool`, `LoadSkillTool`.
- Add `ToolRegistry.concurrencySafe(name)`.
- Tests: exact whitelist; all other built-ins false; unknown false; `mutatesWorkspace` is not used as the predicate.

**Files:** `Tool.java:7-14`; `ToolRegistry.java`; four built-ins; `ToolConcurrencyTest.java`.
**Gate:** `mvn -q -Dtest=ToolConcurrencyTest test`.
**Rollback:** remove overrides; default false keeps serial behavior.

### S8 — Parallel batch executor (5h)

**Deliverable:** All-safe batches of 2–4 run concurrently; any mutating/unknown/MCP tool keeps the batch serial.

- Refactor `executeTools` into Phase A (main thread: limit, parse, `tool.start`, authorize, `PreparedCall`), Phase B (fixed pool of 4, only if all safe and size ≤4), Phase C (main thread: append messages, emit `tool.end`, stats, `refreshFiles` once).
- Workers call only `tools.execute`; no `markDirty`/`markTouched`/`markVerified`/`updatePlan`/`refreshFiles`/`setNetworkAllowed`.
- Cancellation: check before submit and before each worker; wait for started reads; never interrupt.
- Tests: barrier proves 2 slow reads overlap; max 4 concurrent for 5 reads; one mutating call forces serial; model order preserved; denied call does not consume a slot; worker exception becomes error; cancel starts no worker; all 73 pass.

**Files:** `AgentLoop.java:458-529`; `ToolRegistry.java`; `Tool.java`; `ParallelToolExecutionTest.java`.
**Gate:** `mvn -q -Dtest=ParallelToolExecutionTest,AgentLoopTest test`; then full suite.
**Rollback:** keep the serial branch behind a `parallelToolCalls=false` setting (default true only after the test gate); flipping it restores today's path.

### Phase 1 stop point

After S8, report these numbers and **wait**:

- Full suite: `<n> tests, 0 failures` (expect 73 + new).
- Spill smoke: 120k-char output; full text retrievable; inline head/tail correct.
- Overflow smoke: one simulated 400; run succeeds; events once; second 400 fails with the actionable message.
- Parallel smoke: 4 × 200ms mock reads; serial ~800ms, parallel ≤300ms; 5 reads max 4 concurrent.
- Diff: no `pom.xml` dependency change, no SQL migration, no SSE breaking rename.

---

## Phase 2 status (P1 streaming — shipped 2026-11-17)

Executed as three slices on local `main` (not pushed): `1c49e2a` (client SSE +
`StreamListener`), `b4852ec` (`LiveDeltaBus`, log wake-ups, SSE merge), `2d2644e`
(`app.js` live bubble + docs). Full suite 127 tests, 0 failures; verified
end-to-end against a mock SSE upstream. Deviations from the plan below: deltas
are throttled at 50 ms / 512 chars, `stream_options` fallback retries once with
`include_usage=false`, and a mid-stream failure discards the partial bubble
instead of persisting it.


Only after Phase 1 review: 3 slices — (a) `LlmClient.stream` + `DeepSeekClient` SSE parser with a mock server; (b) `LiveDeltaBus` + `AgentLoop` delta publishing + SSE merge; (c) `app.js` live bubble + reconnect/final reconciliation. Estimated 2.5 days. Key decisions for the review: live deltas are not persisted (confirmed in Task 3), custom gateways may reject `stream_options`, and partial output is dropped on mid-stream failure.

---

## Task 3: Streaming output (P1)

**Goal:** DeepSeek responses arrive as deltas; the Web UI shows text as it is generated; cancel closes the stream.

**Design.**
- Add to `LlmClient`:
  ```java
  interface StreamListener {
      void onText(String delta);
      void onReasoning(String delta);
      void onToolCallDelta(int index, String id, String name, String argumentsDelta);
      void onUsage(Usage usage);
  }
  default ChatResult stream(ChatRequest request, StreamListener listener) throws LlmException {
      ChatResult result = chat(request);
      if (result.content() != null) listener.onText(result.content());
      if (result.reasoningContent() != null) listener.onReasoning(result.reasoningContent());
      if (result.usage() != null) listener.onUsage(result.usage());
      return result;
  }
  ```
- `DeepSeekClient.stream`:
  - same request body as `chat`, but `stream=true`; add `stream_options.include_usage=true` only for the official `api.deepseek.com` endpoint (custom gateways may reject it; if rejected, retry once without it and keep stream=true);
  - `BodyHandlers.ofInputStream()` + `BufferedReader` over UTF-8;
  - parse `data:` lines; `[DONE]` ends; ignore comments/blank lines;
  - accumulate `delta.content`, `delta.reasoning_content`, `delta.tool_calls[]` by `index` (id/name/arguments fragments);
  - `finish_reason` and `usage` captured; build `ChatResult` with concatenated text and merged tool calls;
  - non-2xx: read the body (bounded 8k), parse `error.code`, throw `LlmException`;
  - mid-stream `error` object: throw `LlmException` with the streamed partial text in the message? No — throw; the caller decides; do not append a partial assistant message;
  - abort: `RunControl` cancellation closes the response stream; `InterruptedIOException` maps to cancellation, not retry.
- `AgentRun` gets a `LiveDeltaBus` (listener list + `publish(text, reasoning)`); deltas are **not** written to `RunEventLog`/MySQL and consume no `seq`. `WebServer`'s SSE handler subscribes to both the durable event log and the live bus, forwarding deltas as `assistant.delta` without an SSE `id`. On reconnect, deltas are lost; the durable final `assistant` event remains the source of truth.
- `AgentLoop`:
  - replace `llm.chat(request)` with `llm.stream(request, listener)`;
  - listener publishes text/reasoning deltas; throttle to at most one publish per 50ms or 512 chars, whichever first; always flush the last delta before `assistant`;
  - keep the final `assistant` event and message exactly as today; no duplicate content in the transcript.
- This keeps MySQL rows per turn roughly unchanged; a test asserts the event count does not grow with delta count.
- Web (`app.js`):
  - add `assistant.delta` to the subscribed event list (`app.js:20`);
  - `case 'assistant.delta'`: create/find the live assistant bubble, append text/reasoning; auto-scroll only if the user is at the bottom;
  - on `assistant`, replace the live bubble with the final content (or keep it if identical);
  - on `stream_end`, clear the live bubble;
  - do not render deltas for restored historical runs.
- `ChatRequest` and `ToolSpec` do not change; trace uses a `requestBody(boolean stream)` variant (or equivalent) so the recorded body shows the actual `stream` value.

**Files.**
- Modify `J/src/main/java/io/github/jasoncai0/agent/llm/LlmClient.java`
- Modify `J/src/main/java/io/github/jasoncai0/agent/llm/DeepSeekClient.java`
- Modify `J/src/main/java/io/github/jasoncai0/agent/agent/AgentLoop.java:196-215`
- Modify `J/src/main/java/io/github/jasoncai0/agent/agent/AgentRun.java` (live delta bus)
- Modify `J/src/main/java/io/github/jasoncai0/agent/web/WebServer.java` (merge live deltas into SSE)
- Modify `J/src/main/resources/web/app.js:20,713-730,808-816`
- Test `J/src/test/java/io/github/jasoncai0/agent/llm/DeepSeekClientStreamTest.java`
- Test `J/src/test/java/io/github/jasoncai0/agent/agent/StreamingEventTest.java`

**Tests / acceptance.**
- Mock SSE server sends: text `Hello`, ` world`, a tool call split across three chunks (id/name/args), usage, `[DONE]`. Client returns `Hello world`, one merged tool call, usage.
- Text + reasoning deltas both arrive in order; final `assistant` content equals concatenation.
- Non-2xx with `{"error":{"code":"context_length_exceeded"}}` yields `LlmException.isContextOverflow() == true`.
- Mid-stream disconnect throws; no partial assistant message is appended.
- Cancellation during streaming stops reading; the run ends `CANCELLED`, not `FAILED`.
- Web: a 3-chunk response renders progressively; final text has no duplication; restored history does not replay deltas.
- `assistant.delta` does not increase `jade_events` rows; a 3-chunk response adds zero durable events beyond the final `assistant`.
- `mvn -q test` green; no real API key in tests.

**Risks.**
- SSE framing: providers may send CRLF, multiple `data:` lines, or keep-alive comments. Parser must handle all; tests cover.
- JDK `HttpClient` may buffer if the server does not flush; the mock server must flush each chunk with a small delay.
- Live deltas bypass the durable log; the SSE writer must not block the model thread (bounded queue, drop oldest delta if the client is slow). `assistant` remains the source of truth.
- Tool-call argument fragments are not valid JSON until complete; do not parse until `finish_reason`/`[DONE]`.

**Dependencies:** none semantically; land after Task 1/2 for a clean merge in `AgentLoop`.

**Effort:** 2.5 days.

---

## Task 4: Session fork (P1)

**Status (2026-11-17): delivered in `5d9977e`.** 132 tests pass. Deviations from the design below:
- No MySQL DDL: `parentRunId`/`forkSeq` live in the existing `jade_sessions.snapshot` JSON, so old rows deserialize with nulls and no migration is needed.
- The endpoint returns `201` (created), not `200`.
- The fork workspace suffix is 6 hex characters, not 8 (`<name>-fork-<6hex>`); the run id keeps `fork-<8hex>`.
- Parent events are copied with `RunEventLog.append`, preserving `seq`/`timestamp`; the event payload's `id`/`runId`/`workspace` are rewritten recursively (including records such as `RunSnapshot`). `fork.created` also carries `task`.
- Workspace copy additionally keeps `.agent/tool-results` (so spill references resolve) and skips `.agent` itself; the cap is 20,000 files / 200 MiB.
- `RunArtifacts` persists and restores the lineage fields, and `normalize()` preserves them, so a follow-up after restart keeps `parentRunId`.
- Tests are `AgentServiceForkTest`, `WorkspaceForkTest`, `WebServerForkTest` instead of `SessionForkTest`; the MySQL integration path remains untested without a live DB.

**Goal:** Fork a completed run into an independent run with a copied transcript and workspace; the parent is untouched.

**Design.**
- MySQL additive migration in `SchemaMapper.java`:
  - `jade_sessions.parent_run_id VARCHAR(64) NULL`, `jade_sessions.fork_seq BIGINT NULL`, index `idx_sessions_parent`;
  - guarded by `information_schema.columns` check; run once at store init; no data backfill.
- `RunSnapshot` add `parentRunId` and `forkSeq` (nullable). Add a 17-arg overload that delegates to the new 19-arg canonical constructor with nulls, so existing call sites compile; old rows deserialize with nulls.
- `EventStore` does not need a new method: use `page(runId, before, 500, false)` in a loop + `importRun(newSnapshot, copiedEvents)`; `importRun` already writes snapshot + events in one batch (`MySqlEventStore.java:209-227`).
- `WorkspaceFork.copy(Path source, Path target)`:
  - `Files.walkFileTree`, skip `.git`, `.agent`, `.agent-build`, `target`, `build`, `out`, `node_modules`, `dist`, `.venv`, `venv`, `__pycache__`, `.idea`;
  - reject symlinks (do not follow);
  - cap 20,000 files / 200 MiB; abort and delete the partial target on overflow;
  - preserve relative paths; copy file attributes best-effort.
- `AgentService.fork(String parentRunId, String name)`:
  - load parent snapshot; reject `RUNNING` (409) and missing parent (404);
  - new id `fork-<8hex>`; new workspace `agent-workspaces/<slug>-fork-<8hex>`;
  - copy workspace; create new snapshot with parent fields and the parent's messages; copy all events with original seq; append `fork.created` with `{parentRunId, parentSeq, workspace}`;
  - register `AgentRun` (not started); return `{id, parentRunId, workspace}`;
  - on any failure, delete the new workspace and do not write the snapshot.
- Web:
  - `POST /api/runs/{id}/fork` with optional `{name}`; 200 returns the new run;
  - a “Fork” button on a terminal run; the new run appears in the session list with a parent badge;
  - the fork is not auto-started; the user sends the first prompt.
- CLI: no new flag in v1; Web/API only. Document a `curl` example.

**Interfaces.**
```java
record ForkResult(String runId, String parentRunId, String workspace) {}
```

**Files.**
- Modify `J/src/main/java/io/github/jasoncai0/agent/history/mapper/SchemaMapper.java:7-29`
- Modify `J/src/main/java/io/github/jasoncai0/agent/history/mapper/SessionMapper.java` (if column list is explicit)
- Modify `J/src/main/java/io/github/jasoncai0/agent/agent/RunSnapshot.java`
- Modify `J/src/main/java/io/github/jasoncai0/agent/agent/AgentService.java`
- Modify `J/src/main/java/io/github/jasoncai0/agent/history/MySqlEventStore.java` (idempotent column check)
- Create `J/src/main/java/io/github/jasoncai0/agent/workspace/WorkspaceFork.java`
- Modify `J/src/main/java/io/github/jasoncai0/agent/web/WebServer.java`
- Modify `J/src/main/resources/web/app.js`
- Test `J/src/test/java/io/github/jasoncai0/agent/workspace/WorkspaceForkTest.java`
- Test `J/src/test/java/io/github/jasoncai0/agent/agent/SessionForkTest.java` (fake store; MySQL integration optional)

**Tests / acceptance.**
- Fork a terminal run: new snapshot id, `parentRunId`/`forkSeq` set, messages equal parent, events prefix identical, `fork.created` appended once.
- Parent snapshot/events/workspace unchanged (hash before/after).
- Fork a `RUNNING` run → 409; missing → 404.
- Workspace copy excludes `.agent/.git/target/node_modules`; preserves nested files; rejects symlinks; over-cap aborts with no partial directory.
- Two forks of the same parent have distinct ids/workspaces and do not share files.
- Old snapshots without parent fields still load.
- MySQL migration is idempotent on restart; `information_schema` guard works on 8.4.
- Web list shows parent badge; fork + first prompt works against a mock LLM.

**Risks.**
- Disk usage: cap + document cleanup; do not fork huge repos silently.
- Schema migration on an existing DB: additive only; test on a copy. `information_schema` query must use the configured schema.
- Secrets in copied files: the workspace is the user's own; still exclude `.env`? No — the user may need it. Document that fork copies the workspace as-is except the excluded dirs.
- Concurrent parent changes: reject if `RUNNING`; a terminal run's files can still change outside Jade. Record `forkSeq` and workspace hash in `fork.created`.

**Dependencies:** none semantically; land after Task 1 to keep `AgentLoop`/snapshot churn low.

**Effort:** 2 days.

---

## Task 5: Read-only subagent delegation (P1)

**Status (2026-11-17): delivered in `18bebe4`.** 136 tests pass. Deviations from the design below:
- `delegate_task` is registered per parent run in `AgentService.toolsForRun`, not in `BuiltInTools`; the startup tool count stays 15.
- The child's guessed write tool is rejected by the `READ_ONLY` permission broker before registry lookup, so the transcript says `Permission denied` rather than `Unknown tool`. The child's advertised tool list still excludes every writer; both layers are tested.
- `subagent.end` records `tokens` and `filesRead` instead of `durationMs`.
- No nested Web subagent list was added. Child runs are visible in `/api/runs` with `sub-` ids and stream at the existing `/api/runs/{id}/events`; the parent tool result carries `artifactPath`.
- Child snapshots are persisted only when MySQL is configured; without a store they live in memory for the process lifetime.
- `timeoutSeconds` default is 600 (min 60, max 900), matching the design.

**Goal:** The parent can delegate a bounded, read-only investigation; the child has its own transcript/budget; the parent receives a compact summary.

**Design.**
- New built-in `delegate_task` (only registered in parent runs):
  - args: `task` (required, 1..8,000 chars), `name` (optional, kebab-case), `timeoutSeconds` (60..900, default 600)
  - result: summary (≤8,000 chars), `childRunId`, `status`, `tokens`, `filesRead` (up to 20 paths), `artifactPath` (`.agent/subagents/<childRunId>/transcript.json`)
- `SubagentService`:
  - `start(parentRun, task, name, timeout)`; `Semaphore(2)` per parent; depth 1 (child registry has no `delegate_task`);
  - child `AgentRun` uses the same workspace (read-only), a fresh `RunState`, its own `RunEventLog`/SSE endpoint, and `parent_run_id` (reuse Task 4 column);
  - child tools: `list_files`, `read_file`, `search_text`, `load_skill`, `reflect`; **no** write/edit/run/compile/ask_user/MCP;
  - child permission mode `READ_ONLY`; no approval prompts; any denied action becomes a tool error;
  - child `AgentSettings`: `maxSteps=40`, `maxToolCallsPerStep=8`, `goalMode=false`, `contextTokenBudget=30000`, `overflowRecovery=true`;
  - timeout: cancel child, return `status=timeout`; parent cancellation cancels all children;
  - usage: child `Usage` added to parent `RunState.subagentUsage` and to the parent's `usage` event (additive field).
- Parent events: `subagent.start` `{childRunId, name, task}`, `subagent.end` `{childRunId, status, tokens, durationMs}`. Child events stay in the child log; the parent transcript gets only the tool result.
- Web: a nested “Subagents” list under the parent run; clicking opens the child transcript/SSE. If nested UI is too much for v1, show the child id + artifact path and a read-only transcript link.
- Cleanup: keep child artifacts; no auto-delete.

**Interfaces.**
```java
record DelegationResult(String childRunId, String status, String summary, Usage usage, List<String> filesRead) {}
DelegationResult start(AgentRun parent, String task, String name, int timeoutSeconds);
```

**Files.**
- Create `J/src/main/java/io/github/jasoncai0/agent/agent/SubagentService.java`
- Create `J/src/main/java/io/github/jasoncai0/agent/tool/builtin/DelegateTaskTool.java`
- Modify `J/src/main/java/io/github/jasoncai0/agent/tool/builtin/BuiltInTools.java`
- Modify `J/src/main/java/io/github/jasoncai0/agent/agent/AgentService.java`
- Modify `J/src/main/java/io/github/jasoncai0/agent/agent/AgentLoop.java` (register the tool only for parent; pass `SubagentService` via `ToolContext`)
- Modify `J/src/main/java/io/github/jasoncai0/agent/agent/RunState.java` (subagent usage)
- Modify `J/src/main/java/io/github/jasoncai0/agent/web/WebServer.java`, `app.js`
- Test `J/src/test/java/io/github/jasoncai0/agent/agent/SubagentServiceTest.java`
- Test `J/src/test/java/io/github/jasoncai0/agent/tool/builtin/DelegateTaskToolTest.java`

**Tests / acceptance.**
- Child cannot see `write_file`, `edit_file`, `run_command`, `compile_code`, `delegate_task` in its tool list.
- Child writes are denied even if it guesses a tool name (registry lookup fails).
- Depth: a child cannot delegate; `maxDepth=1` enforced.
- Concurrency: 3 simultaneous delegations → at most 2 run; the third waits on the semaphore (no busy error), and a delegation that exceeds `timeoutSeconds` returns `status=timeout`.
- Timeout: a child stuck in a fake slow LLM is cancelled; parent gets `status=timeout`, run continues.
- Parent cancellation cancels children; no orphan threads.
- Summary cap 8k; full transcript on disk; parent context grows by one tool result only.
- Usage accounting: parent `subagentUsage` equals sum of child usage.
- Web shows child status; child SSE works.
- Existing 73 tests pass.

**Risks.**
- Cost explosion: cap 2 concurrent + 40 steps + 10 min; document.
- Prompt injection in child-read files: child is read-only and cannot exfiltrate except through the summary; still cap summary and warn in the tool description.
- Shared workspace read while parent writes: read-only child may see a partial file. Accept for v1; record `workspaceHash` in `subagent.start`.
- Thread leaks: use a dedicated executor and close it on run end; test.
- Permission bypass: child registry is constructed from a whitelist, not by filtering names at execution time; test the actual list.

**Dependencies:** Tasks 1 and 4 (parent link + context safety).

**Effort:** 4 days.

---

## Task 6: MCP resources (P2, delivered)

**Status (2026-11-17): delivered in `854ba42`.** 137 tests pass.

**Goal:** let the model browse and read MCP resources without turning every one into a tool.

**Design:** `McpConnection.initialize` records `capabilities.resources`; only then does `McpManager` register `mcp_<server>_list_resources`, `mcp_<server>_list_resource_templates`, and `mcp_<server>_read_resource` (URI required). Lists follow `nextCursor` up to 200 entries. `resources/read` returns text contents; `blob` becomes a size placeholder and never enters the transcript. Tool output is capped at 30,000 characters. All three tools are read-only and concurrency-safe.

**Tests:** `McpResourcesTest` covers capability gating (3 tools, not 4), list/template/read output, and missing `uri`. Servers without the capability keep the old tool count (`McpHttpIntegrationTest`).

**Not done:** `resources/subscribe`, prompts, and auto-injection of resource bodies.

**Effort:** ~1 day (actual: one session).

---

## Implementation order and dependencies

| Order | Task | Depends on | Effort | Why this order |
|-------|------|------------|--------|----------------|
| 1 | Context safety net | — | 2 days | Correctness; unblocks long runs |
| 2 | Parallel read-only tools | Task 1 touches `executeTools` | 1 day | Small, high latency win; avoid merge conflict by doing after Task 1 |
| 3 | Streaming | — | 2.5 days | UX; independent of 1/2, but touches `AgentLoop` model call |
| 4 | Session fork | — | 2 days | Delivered `5d9977e`; lineage in snapshot JSON, reused by Task 5 |
| 5 | Subagent delegation | Tasks 1, 4 | 4 days | Delivered `18bebe4`; per-run registry, read-only child, 2-per-parent cap |
| 6 | MCP resources | — | 1 day | Delivered `854ba42`; capability-gated, 3 read-only tools per server |

Total: ~11.5 engineering days, plus review. The first iteration is only Phase 1 (S1–S8, ~3 days); stop after S8 for review. Do not parallelize Tasks 1/2/3 on the same files; they all touch `AgentLoop.java`.

---

## Out of scope this round

- MCP prompts, resource subscriptions, MCP reconnect state machine, OAuth.
- Skill catalog-only injection, chokidar hot reload.
- Linux/Windows sandbox backends; changing the default from off.
- Cordis plugin tree, profile/bundle, plugin market, desktop shell, WebSocket mux.
- Multi-provider routing/failover; model cost tables.
- Long-term memory/vector store.
- DSH session format, zstd, FTS5, telemetry/OTel.
- Schedule, Agent Team, Workflow, Office/Browser tools.
- Rewriting MySQL to JSONL.
- Any UI framework change; `app.js` stays vanilla.

---

## Acceptance for the whole plan

1. `mvn -q test` passes with 137 tests; no skipped new tests except OS-specific ones with an explicit assumption.
2. `java -jar target/coding-agent.jar --check` shows `overflowRecovery`, `toolInlineChars`, and (after Task 4) fork capability; no secrets.
3. Manual smoke against a local mock server: overflow → compaction → success; 5 parallel reads; streaming text; fork + prompt; delegate read-only task.
4. Real DeepSeek smoke (manual, not CI): one streaming call and one forced-overflow call; record the actual error code in `docs/DSH-GAP-ANALYSIS.md` if it differs from the heuristic.
5. No change to the SSE event contract for existing clients; new events are additive.
6. Workspace paths and spill paths cannot escape the run workspace; tests cover traversal/symlink.
7. Parent run is unchanged after fork; parent transcript has only one tool result per delegation.

---

## Handoff notes

- Read `docs/DSH-GAP-ANALYSIS.md` and this plan before coding. If a DSH behavior is cited, use the installed asar path (`P/...`) and re-check the line; versions may differ.
- Phase 1: one commit per slice (S1–S8), with the slice id in the message. Later: one task per PR. Do not mix formatting-only changes.
- For Task 1, write the spill test with a temporary workspace, not the repo root.
- For Task 3, do not use a real API key in tests; the mock server is the contract.
- For Task 4, test the migration against a disposable MySQL database; never run it on the user's `jade` DB during development.
- For Task 5, the child whitelist is a security boundary; test the actual `ToolRegistry` contents, not a filtered view.
- If a design decision changes during implementation, update this plan in the same PR and note why.
