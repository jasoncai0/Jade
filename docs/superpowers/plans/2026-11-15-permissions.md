# Permission Checks Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: use `executing-plans` to implement task-by-task. Do not start until the decisions in `docs/PERMISSIONS.md` are confirmed.

**Goal:** Before Jade executes a workspace write, dangerous command, code run, or network request, ask the user for an explicit decision, block the run while waiting, record the decision, and resume without silently weakening the local sandbox.

**Architecture:** A `PermissionManager` evaluates each tool call against a `PermissionPolicy` and the run's `PermissionSession`. Actions that need consent create a `PermissionRequest` and block on a `PermissionBroker`; the Web UI or CLI resolves it through an API or terminal prompt. `AgentLoop.executeTools` consults the manager before calling `ToolRegistry.execute`. A denial becomes a tool error, not an immediate run failure. Audit entries go to `.agent/permissions.jsonl`; they never contain secrets or file bodies.

**Tech Stack:** Java 21, Jackson, JDK `HttpServer`, JUnit 5, vanilla JS. No new runtime dependency.

**Design source:** `docs/PERMISSIONS.md` (draft, uncommitted).

---

## Global Constraints

- Permission checks are authorization UX, not a security boundary. Do not describe them as a sandbox.
- Local mode remains the default; Docker is still an optional plugin.
- Do not write to global memory; follow `AGENTS.md` and update `docs/BLOG_MEMORY.md` only if this work yields a reusable pitfall.
- Never put API keys, `.env` contents, environment variables, or full file bodies into audit logs, SSE, or UI.
- Workspace path jail stays in force. A permission grant never authorizes a path outside the run workspace.
- Hard-deny commands cannot be approved.
- Preserve all 34 existing tests; add tests before claiming completion.
- Every new behavior must be visible in `--check`, `/api/config`, and docs where relevant.
- Do not add a database. Session grants are in memory; audit is append-only JSONL.

## Approved-default proposal

These are the recommended answers to the five open questions; confirm before implementation:

1. `balanced` asks on the first workspace write per session; subsequent writes are allowed for that session. Sensitive paths always ask.
2. `run_code` / `run_java` ask on first execution per session; `compile_code` is auto-allowed.
3. Network asks per host/domain, with “allow this host for this session”; local OS network remains denied unless the request is approved and the sandbox wrapper is opened for that command.
4. v1 supports only `once` and `session`; no cross-run permanent allowlist.
5. Non-TTY CLI defaults to deny and returns an actionable message.

---

## Task 1: Permission domain model

**Files:**
- Create `src/main/java/io/github/jasoncai0/agent/permission/PermissionMode.java`
- Create `src/main/java/io/github/jasoncai0/agent/permission/PermissionKind.java`
- Create `src/main/java/io/github/jasoncai0/agent/permission/PermissionScope.java`
- Create `src/main/java/io/github/jasoncai0/agent/permission/PermissionDecision.java`
- Create `src/main/java/io/github/jasoncai0/agent/permission/PermissionRequest.java`
- Create `src/main/java/io/github/jasoncai0/agent/permission/PermissionResult.java`
- Test `src/test/java/io/github/jasoncai0/agent/permission/PermissionModelTest.java`

**Interfaces:**
- `PermissionMode { BALANCED, STRICT, READ_ONLY, BYPASS }`, `fromId`, `id`
- `PermissionKind { FILE_WRITE, COMMAND, EXECUTE, NETWORK }`
- `PermissionScope { ONCE, SESSION }`
- `PermissionDecision { ALLOW, DENY }`
- `PermissionRequest` record: id, runId, tool, kind, risk, summary, detail, target, options, createdAt, expiresAt
- `PermissionResult` record: requestId, decision, scope, reason

- [ ] Model validation: request id `perm-<8hex>`, expiry in the future, nonblank summary.
- [ ] Add `PermissionMode.fromId` aliases (`readonly` → `READ_ONLY`), reject unknown values.
- [ ] Add `PermissionRequest.publicView()` that omits internal fields.
- [ ] Tests for enum parsing, validation, immutable copies.

**Acceptance:** pure unit tests pass without server/LLM.

## Task 2: Command and path classifier

**Files:**
- Create `src/main/java/io/github/jasoncai0/agent/permission/CommandClassifier.java`
- Create `src/main/java/io/github/jasoncai0/agent/permission/CommandClassification.java`
- Create `src/main/java/io/github/jasoncai0/agent/permission/NetworkTarget.java`
- Test `src/test/java/io/github/jasoncai0/agent/permission/CommandClassifierTest.java`

**Interfaces:**
- `CommandClassification classify(String command)` → category, risk, reason, network targets
- `CommandClassification` categories: `READ`, `WORKSPACE_WRITE`, `EXECUTE`, `DANGEROUS`, `NETWORK`, `UNKNOWN`
- `NetworkTarget` record: host, port, scheme, display
- `boolean isHardDenied(String command)`, `String hardDenyReason(String command)`

Detection rules:

- Split compound commands on `&&`, `||`, `;`, `|` before classification; if parsing is ambiguous, return `UNKNOWN`.
- `rm -rf`, `git reset --hard`, `git clean -fdx`, `git push --force`, `chmod`, `chown`, `kill`, `pkill`, `crontab`, `launchctl`, `docker` → `DANGEROUS` unless clearly scoped to a harmless workspace path; still ask.
- `sudo`, `su`, `doas`, `mkfs`, `fdisk`, `parted`, `dd` to block device, fork bomb, `shutdown`, `reboot`, writes to `/etc`, `/System`, `/Library`, `~/.ssh` → hard deny.
- `curl`, `wget`, `git clone/fetch/pull/push`, `npm/pip/go` → `NETWORK` with extracted host.
- `bash -c`, `sh -c`, `eval`, `$()`, backticks, `python -c`, `node -e`, `curl | sh` → `UNKNOWN` (ask).
- `javac`, `mvn test`, `go build`, `npm test`, `pytest` → `WORKSPACE_WRITE` (build outputs) and auto-allowed under a write grant.
- `ls`, `cat`, `head`, `tail`, `grep`, `find` without `-delete/-exec`, `git status/diff/log` → `READ`.

- [ ] Implement a tokenizer that preserves quoted strings and redacts obvious secrets from the summary.
- [ ] Hard-deny checks run before permission requests.
- [ ] Tests for compound commands, quoted `&&`, `rm -rf` variants, piped shells, URL hosts, package registries, unknown scripts.
- [ ] Tests assert no classification ever returns ALLOW for a hard-denied command.

**Acceptance:** classifier is conservative; unknown asks; hard deny cannot be approved.

## Task 3: Policy and session grants

**Files:**
- Create `src/main/java/io/github/jasoncai0/agent/permission/PermissionPolicy.java`
- Create `src/main/java/io/github/jasoncai0/agent/permission/PermissionSession.java`
- Create `src/main/java/io/github/jasoncai0/agent/permission/PermissionDecisionResult.java`
- Test `src/test/java/io/github/jasoncai0/agent/permission/PermissionPolicyTest.java`

**Interfaces:**
- `PermissionPolicy.forMode(PermissionMode)`
- `PermissionSession` holds per-run grants: `workspaceWrites`, `codeExecution`, `networkHosts`, `commandPatterns`
- `Decision evaluate(ToolCall, ToolContext, PermissionSession)` → `ALLOW`, `ASK`, `DENY`
- `void grant(PermissionRequest, PermissionScope)`

Default policy:

| Action | balanced | strict | read-only | bypass |
|---|---|---|---|---|
| read | allow | allow | allow | allow |
| first workspace write | ask | ask every time | deny | allow |
| write after session grant | allow | ask | deny | allow |
| sensitive write | ask | ask | deny | ask |
| compile | allow | ask | deny | allow |
| run code | ask first | ask every time | deny | allow |
| dangerous command | ask | ask | deny | allow unless hard deny |
| network | ask per host | ask per host | deny | allow |
| unknown | ask | ask | deny | ask unless hard deny |

- [ ] Sensitive path predicate: `.env`, `.env.*`, `.git/**`, `.ssh/**`, `AGENTS.md`, `*.pem`, `*.key`, `id_rsa*`.
- [ ] Session grants are scoped and cannot apply to sensitive paths.
- [ ] `read-only` surfaces a clear denial, not an approval prompt.
- [ ] Tests for every mode and scope.

**Acceptance:** policy is deterministic and covered by unit tests.

## Task 4: AgentLoop integration and waiting state

**Files:**
- Modify `src/main/java/io/github/jasoncai0/agent/agent/RunStatus.java`
- Modify `src/main/java/io/github/jasoncai0/agent/agent/RunState.java`
- Modify `src/main/java/io/github/jasoncai0/agent/agent/AgentLoop.java`
- Create `src/main/java/io/github/jasoncai0/agent/agent/PermissionBroker.java`
- Create `src/main/java/io/github/jasoncai0/agent/agent/PermissionManager.java`
- Test `src/test/java/io/github/jasoncai0/agent/agent/PermissionLoopTest.java`

**Interfaces:**
- `RunStatus.WAITING_APPROVAL`; `terminal()` remains false.
- `RunState.awaitPermission(PermissionRequest)`, `resolvePermission(PermissionResult)`, `clearPermission(id)`, `pendingPermission()`
- `PermissionBroker.request(PermissionRequest, Duration)` blocks; `resolve(id, result)`; `cancelRun(runId)`; `close()`
- `PermissionManager.enforce(ToolCall, ToolContext)` returns a result or throws a controlled denial

Integration:

- [ ] Before `ToolRegistry.execute`, call `PermissionManager`.
- [ ] On `ASK`: set WAITING_APPROVAL, emit `permission.request`, block up to 300s, then restore RUNNING or execute denial.
- [ ] On `DENY`: emit `permission.resolved`, return `ToolResult.error("Permission denied: ...")`; continue the loop.
- [ ] On timeout/cancel: audit `timeout`/`cancelled`, return denial.
- [ ] On service restart: pending requests become `interrupted`; run becomes FAILED (existing nonterminal rule).
- [ ] Do not hold a lock while waiting; other runs must proceed.
- [ ] Audit each request/decision to `.agent/permissions.jsonl`; no content/secrets.
- [ ] Tests: allow once executes; deny prevents side effect; session grant skips second ask; sensitive path asks again; timeout; cancel; restart.

**Acceptance:** a blocked run is observable and does not freeze the server.

## Task 5: Web API and SSE

**Files:**
- Modify `src/main/java/io/github/jasoncai0/agent/web/WebServer.java`
- Modify `src/main/java/io/github/jasoncai0/agent/agent/AgentService.java`
- Modify `src/main/java/io/github/jasoncai0/agent/agent/AgentRun.java`
- Test `src/test/java/io/github/jasoncai0/agent/web/PermissionWebTest.java`

Endpoints:

```text
GET  /api/runs/{id}/permissions
POST /api/runs/{id}/permissions/{requestId}
```

- [ ] GET returns pending + recent audit entries, without secrets.
- [ ] POST body `{decision,scope}`; validate 400, not-waiting 409, wrong-run 404.
- [ ] Duplicate approval returns 409 and does not execute twice.
- [ ] SSE events `permission.request`, `permission.resolved`; payload follows design doc.
- [ ] Run snapshot includes `pendingPermission` for refresh recovery.
- [ ] Tests: API status codes, event sequence, pending survives reload, denial prevents tool.

**Acceptance:** API and SSE tests pass with a fake LLM.

## Task 6: UI approval card

**Files:**
- Modify `src/main/resources/web/app.js`
- Modify `src/main/resources/web/styles.css`
- Modify `src/main/resources/web/index.html`

Behavior:

- [ ] Add `permission.request`/`permission.resolved` to `EVENT_TYPES`.
- [ ] Render a blocking card above Live Trace with risk/tool/summary/detail.
- [ ] For file writes: path, bytes, diff/preview; for commands: full command; for network: host/port.
- [ ] Buttons: Deny, Allow once, Allow for this session; disabled while POSTing.
- [ ] Status: `WAITING FOR APPROVAL`; composer disabled.
- [ ] On reload: `GET /api/runs/{id}/permissions` restores pending card.
- [ ] After resolve: retain `APPROVED`/`DENIED` trace entry.
- [ ] Keyboard A/S/D when focus is not in a form control.
- [ ] `node --check` passes; no untrusted `innerHTML`.

**Acceptance:** browser test with a fake LLM exercises all three actions and reload recovery.

## Task 7: CLI approvals

**Files:**
- Modify `src/main/java/io/github/jasoncai0/agent/cli/CliOptions.java`
- Modify `src/main/java/io/github/jasoncai0/agent/CodingAgentApplication.java`
- Modify `src/main/java/io/github/jasoncai0/agent/cli/ConsoleRenderer.java`
- Test `src/test/java/io/github/jasoncai0/agent/cli/CliOptionsTest.java`

- [ ] Add `--permission-mode balanced|strict|read-only|bypass`.
- [ ] Add `--dangerously-skip-permissions` with a visible warning.
- [ ] TTY prompt: `[a]llow once / [s]ession / [d]eny`.
- [ ] Non-TTY: deny, print actionable message, do not hang.
- [ ] `--check` includes mode and policy summary.
- [ ] Tests: option parsing, conflicting flags, non-TTY denial.

**Acceptance:** CLI never blocks forever without a TTY.

## Task 8: Docs, config, and release

**Files:**
- Modify `README.md`
- Modify `docs/ARCHITECTURE.md`
- Modify `docs/DEMO.md`
- Modify `.env.example`
- Update `docs/PERMISSIONS.md` status after approval
- Update `docs/BLOG_MEMORY.md` if a reusable pitfall emerges

- [ ] Document modes, approval UI, audit file, and the “not a sandbox” boundary.
- [ ] Document `permission.request`/`permission.resolved` and the two API routes.
- [ ] Add a demo path: first write ask → deny → model tries safer path → allow once.
- [ ] Confirm ignored files (`target/`, `agent-workspaces/`, `.env`).
- [ ] Run `mvn -q test`; 34 existing tests + new tests pass.
- [ ] Package, restart Jade on 8081, browser smoke.
- [ ] Commit and push only after approval.

## Verification Gate

Do not claim completion until:

- [ ] `mvn -q test` green.
- [ ] `node --check src/main/resources/web/app.js` green.
- [ ] Web API integration test green.
- [ ] Browser shows pending card on reload and resolves correctly.
- [ ] Hard deny cannot be approved.
- [ ] No secrets in `.agent/permissions.jsonl`.
- [ ] Existing continuous-session, sandbox, Go, and web tests still pass.

## Out of Scope

- Kernel-level/domain-level network filtering; Docker proxy is a separate feature.
- Cross-run/global allowlists.
- Permission prompts for read-only tools.
- Protecting the host from an approved arbitrary script; that requires the Docker plugin.

## Approval

Waiting for confirmation of the five decisions in `docs/PERMISSIONS.md` before starting Task 1.
