import { test, expect } from '@playwright/test';
import http from 'node:http';
import fs from 'node:fs';
import os from 'node:os';
import path from 'node:path';
import net from 'node:net';
import { spawn } from 'node:child_process';
import { fileURLToPath } from 'node:url';

const __dirname = path.dirname(fileURLToPath(import.meta.url));
const repoRoot = path.resolve(__dirname, '..');
const jarPath = path.join(repoRoot, 'target', 'coding-agent.jar');

function freePort() {
  return new Promise((resolve, reject) => {
    const srv = net.createServer();
    srv.listen(0, '127.0.0.1', () => {
      const address = srv.address();
      const port = typeof address === 'object' && address ? address.port : 0;
      srv.close((err) => (err ? reject(err) : resolve(port)));
    });
    srv.on('error', reject);
  });
}

function startMockDeepSeek() {
  let callIndex = 0;
  const server = http.createServer((req, res) => {
    let raw = '';
    req.on('data', (chunk) => { raw += chunk; });
    req.on('end', () => {
      let body = {};
      try { body = JSON.parse(raw || '{}'); } catch {}
      const messages = Array.isArray(body.messages) ? body.messages : [];
      const allText = messages.map((m) => String(m.content || '')).join('\n');
      const isStream = Boolean(body.stream);

      // Goal reviewer / JSON mode calls
      if (body.response_format && body.response_format.type === 'json_object') {
        const reply = JSON.stringify({ aligned: true, missing: [], issues: [] });
        res.writeHead(200, { 'Content-Type': 'application/json' });
        res.end(JSON.stringify({
          id: 'chatcmpl-goal',
          choices: [{ index: 0, message: { role: 'assistant', content: reply }, finish_reason: 'stop' }],
          usage: { prompt_tokens: 20, completion_tokens: 10, total_tokens: 30 }
        }));
        return;
      }

      // Memory admission strict tool calls
      const tools = Array.isArray(body.tools) ? body.tools : [];
      if (tools.some((t) => t?.function?.name === 'emit_memory')) {
        res.writeHead(200, { 'Content-Type': 'application/json' });
        res.end(JSON.stringify({
          id: 'chatcmpl-mem',
          choices: [{
            index: 0,
            message: {
              role: 'assistant',
              content: '',
              tool_calls: [{
                id: 'call-mem-1',
                type: 'function',
                function: {
                  name: 'emit_memory',
                  arguments: JSON.stringify({ action: 'skip', reason: 'test task' })
                }
              }]
            },
            finish_reason: 'tool_calls'
          }],
          usage: { prompt_tokens: 15, completion_tokens: 10, total_tokens: 25 }
        }));
        return;
      }

      // Permission test trigger: if user prompt asks for permission_probe, request run_command curl
      const hasToolResult = messages.some((m) => m.role === 'tool');
      const toolCount = messages.filter((m) => m.role === 'tool').length;
      callIndex += 1;

      let assistantMsg;
      let finishReason = 'stop';

      if (allText.includes('PERMISSION_PROBE') && !hasToolResult) {
        finishReason = 'tool_calls';
        assistantMsg = {
          role: 'assistant',
          content: 'Running mutating shell command.',
          tool_calls: [{
            id: `call-perm-${callIndex}`,
            type: 'function',
            function: {
              name: 'run_command',
              arguments: JSON.stringify({ command: 'touch approved.txt' })
            }
          }]
        };
      } else if (allText.includes('MULTI_STEP_TRACE')) {
        if (toolCount === 0) {
          finishReason = 'tool_calls';
          assistantMsg = {
            role: 'assistant',
            content: 'Turn 1: creating hello.py.',
            tool_calls: [{
              id: `call-step1-${callIndex}`,
              type: 'function',
              function: {
                name: 'write_file',
                arguments: JSON.stringify({ path: 'hello.py', content: 'print("hello trace")\n' })
              }
            }]
          };
        } else if (toolCount === 1) {
          finishReason = 'tool_calls';
          assistantMsg = {
            role: 'assistant',
            content: 'Turn 2: reading hello.py.',
            tool_calls: [{
              id: `call-step2-${callIndex}`,
              type: 'function',
              function: {
                name: 'read_file',
                arguments: JSON.stringify({ path: 'hello.py' })
              }
            }]
          };
        } else if (toolCount === 2) {
          finishReason = 'tool_calls';
          assistantMsg = {
            role: 'assistant',
            content: 'Turn 3: checking list_files.',
            tool_calls: [{
              id: `call-step3-${callIndex}`,
              type: 'function',
              function: {
                name: 'list_files',
                arguments: JSON.stringify({})
              }
            }]
          };
        } else {
          assistantMsg = {
            role: 'assistant',
            content: 'Completed multi-step trace task with hello.py verified.'
          };
        }
      } else {
        assistantMsg = {
          role: 'assistant',
          content: 'Done! Task handled cleanly.'
        };
      }

      if (!isStream) {
        res.writeHead(200, { 'Content-Type': 'application/json' });
        res.end(JSON.stringify({
          id: `chatcmpl-${callIndex}`,
          choices: [{ index: 0, message: assistantMsg, finish_reason: finishReason }],
          usage: { prompt_tokens: 40, completion_tokens: 20, total_tokens: 60 }
        }));
        return;
      }

      res.writeHead(200, {
        'Content-Type': 'text/event-stream',
        'Cache-Control': 'no-cache',
        Connection: 'keep-alive'
      });
      const delta = { role: 'assistant' };
      if (assistantMsg.content) delta.content = assistantMsg.content;
      if (assistantMsg.tool_calls) {
        delta.tool_calls = assistantMsg.tool_calls.map((tc, idx) => ({
          index: idx,
          id: tc.id,
          type: 'function',
          function: { name: tc.function.name, arguments: tc.function.arguments }
        }));
      }
      res.write(`data: ${JSON.stringify({
        id: `chatcmpl-${callIndex}`,
        choices: [{ index: 0, delta, finish_reason: null }]
      })}\n\n`);
      res.write(`data: ${JSON.stringify({
        id: `chatcmpl-${callIndex}`,
        choices: [{ index: 0, delta: {}, finish_reason: finishReason }],
        usage: { prompt_tokens: 40, completion_tokens: 20, total_tokens: 60 }
      })}\n\n`);
      res.write('data: [DONE]\n\n');
      res.end();
    });
  });

  return new Promise((resolve, reject) => {
    server.listen(0, '127.0.0.1', () => {
      const addr = server.address();
      const port = typeof addr === 'object' && addr ? addr.port : 0;
      resolve({
        url: `http://127.0.0.1:${port}`,
        close: () => new Promise((r) => server.close(() => r()))
      });
    });
    server.on('error', reject);
  });
}

async function waitForHealth(baseUrl, timeoutMs = 15_000) {
  const deadline = Date.now() + timeoutMs;
  while (Date.now() < deadline) {
    try {
      const resp = await fetch(`${baseUrl}/api/health`);
      if (resp.ok) return;
    } catch {}
    await new Promise((r) => setTimeout(r, 150));
  }
  throw new Error(`Timed out waiting for Jade server at ${baseUrl}`);
}

let mockLlm;
let jadeProc;
let baseUrl;
let tempDir;

test.beforeAll(async () => {
  if (!fs.existsSync(jarPath)) {
    throw new Error(`Missing ${jarPath}; run 'mvn -B package -DskipTests' before Playwright E2E`);
  }
  mockLlm = await startMockDeepSeek();
  const webPort = await freePort();
  tempDir = fs.mkdtempSync(path.join(os.tmpdir(), 'jade-playwright-'));
  baseUrl = `http://127.0.0.1:${webPort}`;

  jadeProc = spawn('java', ['-jar', jarPath, '--port', String(webPort), '--host', '127.0.0.1'], {
    cwd: repoRoot,
    env: {
      ...process.env,
      DEEPSEEK_API_KEY: 'sk-test-playwright-dummy',
      DEEPSEEK_BASE_URL: mockLlm.url,
      AGENT_WORKSPACES_DIR: path.join(tempDir, 'workspaces'),
      JADE_MYSQL_URL: 'off',
      AGENT_MEMORY_MODE: 'off',
      AGENT_PERMISSION_MODE: 'balanced',
      AGENT_GOAL_MODE: 'false'
    },
    stdio: ['ignore', 'pipe', 'pipe']
  });
  await waitForHealth(baseUrl);
});

test.afterAll(async () => {
  if (jadeProc && !jadeProc.killed) {
    jadeProc.kill('SIGTERM');
    await new Promise((resolve) => {
      const timer = setTimeout(() => {
        try { jadeProc.kill('SIGKILL'); } catch {}
        resolve();
      }, 3_000);
      jadeProc.once('exit', () => {
        clearTimeout(timer);
        resolve();
      });
    });
  }
  if (mockLlm) await mockLlm.close();
  if (tempDir) fs.rmSync(tempDir, { recursive: true, force: true });
});

test('renders empty state, drawers/modals, multi-turn Trace folding, Session/Trace switch, and Team toggle', async ({ page }) => {
  await page.goto(baseUrl);
  await expect(page.locator('#emptyState h1')).toContainText('What should Jade build?');
  await expect(page.locator('#keyText')).toHaveText('key ready');

  // Context drawer opens and closes cleanly on Escape
  await page.click('#contextBtn');
  await expect(page.locator('body')).toHaveClass(/context-open/);
  await page.keyboard.press('Escape');
  await expect(page.locator('body')).not.toHaveClass(/context-open/);

  // Multi-step run -> verify older turns auto-collapse in Trace mode while Session view stays intact
  await page.fill('#taskInput', 'MULTI_STEP_TRACE build and inspect hello.py');
  await page.click('#startBtn');

  await expect(page.locator('#runView')).toBeVisible();
  // In balanced mode, the first write_file requests session-scoped approval
  const firstPerm = page.locator('#permissionCard');
  await expect(firstPerm).toBeVisible({ timeout: 10_000 });
  await firstPerm.getByRole('button', { name: 'Allow for session' }).click();
  await expect(page.locator('#runStatusText')).toHaveText('succeeded', { timeout: 20_000 });

  // Switch to Trace tab and verify per-turn collapsible groups
  await page.click('#traceTab');
  await expect(page.locator('body')).toHaveClass(/trace-mode/);
  const turnGroups = page.locator('.trace-turn-group');
  const groupCount = await turnGroups.count();
  expect(groupCount).toBeGreaterThanOrEqual(3);

  // Older turns auto-collapsed; latest turn stays open
  await expect(turnGroups.first()).toHaveClass(/collapsed/);
  await expect(turnGroups.last()).not.toHaveClass(/collapsed/);

  // Expand all / Collapse all button toggles all turns
  await page.click('#traceToggleAllBtn');
  await expect(turnGroups.first()).not.toHaveClass(/collapsed/);
  await page.click('#traceToggleAllBtn');
  await expect(turnGroups.first()).toHaveClass(/collapsed/);

  // Clicking individual turn header expands it
  await turnGroups.first().locator('.trace-turn-header').click();
  await expect(turnGroups.first()).not.toHaveClass(/collapsed/);

  // Switching back to Session tab preserves conversation entries without reset
  await page.click('#sessionTab');
  await expect(page.locator('body')).not.toHaveClass(/trace-mode/);
  await expect(page.locator('.finish-box')).toBeVisible();

  // Team modal toggles session team mode on
  await page.click('#teamBtn');
  await expect(page.locator('#modalTitle')).toHaveText('Team');
  const teamCheckbox = page.locator('#modalBody .team-toggle input[type="checkbox"]');
  await expect(teamCheckbox).not.toBeChecked();
  await teamCheckbox.check();
  await expect(page.locator('#modalBody .modal-lede')).toContainText('Lead approval on');
  await page.keyboard.press('Escape');

  // Memory modal displays Active and Pending tabs
  await page.click('#memoryBtn');
  await expect(page.locator('#modalTitle')).toHaveText('Memory');
  const memTabs = page.locator('#modalBody .memory-tabs button');
  await expect(memTabs).toHaveCount(2);
  await expect(memTabs.nth(0)).toContainText('Active');
  await expect(memTabs.nth(1)).toContainText('Pending');
  await memTabs.nth(1).click();
  await expect(page.locator('#modalBody')).toContainText('No candidate memories waiting for review');
  await page.keyboard.press('Escape');
});

test('shows permission approval card for mutating command and resumes run on approval', async ({ page }) => {
  await page.goto(baseUrl);
  await expect(page.locator('#keyText')).toHaveText('key ready');
  await page.click('#newRunBtn');
  await expect(page.locator('#emptyState')).toBeVisible();
  await expect(page.locator('#startBtn')).toContainText('Start agent');
  await page.fill('#taskInput', 'PERMISSION_PROBE verify approval flow');
  await page.click('#startBtn');

  const permCard = page.locator('#permissionCard');
  await expect(permCard).toBeVisible({ timeout: 15_000 });
  await expect(permCard).toContainText('APPROVAL REQUIRED');

  await permCard.getByRole('button', { name: 'Allow once' }).click();
  await expect(permCard).toBeHidden();
  await expect(page.locator('#runStatusText')).toHaveText('succeeded', { timeout: 15_000 });
});
