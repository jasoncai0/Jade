'use strict';

const PHASES = [
  { id: 'intake', label: 'Intake', detail: 'Validate the task and prepare the run.' },
  { id: 'context', label: 'Context', detail: 'Load workspace state and the conversation budget.' },
  { id: 'skills', label: 'Skills', detail: 'Select relevant SKILL.md playbooks for the task.' },
  { id: 'plan', label: 'Plan', detail: 'Publish or update the execution plan.' },
  { id: 'model', label: 'Model', detail: 'Ask DeepSeek for the next tool call or final answer.' },
  { id: 'tools', label: 'Tools', detail: 'Execute filesystem, shell, compile, and run tools.' },
  { id: 'observe', label: 'Observe', detail: 'Turn tool output into the next observation.' },
  { id: 'verify', label: 'Verify', detail: 'Compile and run the actual artifacts.' },
  { id: 'compact', label: 'Compact', detail: 'Keep the transcript inside its context budget.' },
  { id: 'checkpoint', label: 'Checkpoint', detail: 'Persist the transcript and workspace snapshot.' }
];

const PHASE_LABEL = Object.fromEntries(PHASES.map((phase) => [phase.id, phase.label]));
const PHASE_DETAIL = Object.fromEntries(PHASES.map((phase) => [phase.id, phase.detail]));

const EVENT_TYPES = [
  'run.started', 'turn.started', 'phase', 'llm.request', 'assistant', 'tool.start', 'tool.end', 'usage', 'plan',
  'workspace', 'verification', 'compaction', 'run.succeeded', 'run.failed',
  'run.cancelled', 'run.max_steps', 'run.finished'
];

const TERMINAL = new Set(['SUCCEEDED', 'FAILED', 'CANCELLED', 'MAX_STEPS']);
const STATUS_TEXT = {
  QUEUED: 'queued',
  RUNNING: 'running',
  SUCCEEDED: 'succeeded',
  FAILED: 'failed',
  CANCELLED: 'cancelled',
  MAX_STEPS: 'max steps'
};

const state = {
  config: null,
  tools: [],
  skills: [],
  runs: [],
  selectedId: null,
  status: 'IDLE',
  es: null,
  selectionToken: 0,
  currentStep: 0,
  maxSteps: 10,
  visited: new Set(),
  activePhase: null,
  phaseDetail: '',
  timelineCount: 0,
  toolNodes: new Map(),
  terminal: false,
  finishRendered: false,
  starting: false,
  startedAt: 0,
  finishedAt: 0,
  lastSeq: 0,
  elapsedTimer: null,
  fileTimer: null,
  toastTimer: null,
  usage: { promptTokens: 0, completionTokens: 0, totalTokens: 0 },
  verification: null,
  lastFocused: null
};

const $ = (id) => document.getElementById(id);
const el = (tag, className, text) => {
  const node = document.createElement(tag);
  if (className) node.className = className;
  if (text !== undefined && text !== null) node.textContent = String(text);
  return node;
};
async function api(path, options = {}) {
  const headers = { Accept: 'application/json' };
  if (options.body !== undefined) headers['Content-Type'] = 'application/json';
  const response = await fetch(path, { ...options, headers: { ...headers, ...(options.headers || {}) } });
  let payload;
  try {
    payload = await response.json();
  } catch (error) {
    throw new Error(`Server returned ${response.status} with a non-JSON response.`);
  }
  if (!response.ok || !payload || payload.code !== 0) {
    throw new Error(payload && payload.message ? payload.message : `Request failed (${response.status}).`);
  }
  return payload.data;
}

function normalizeStatus(status) {
  return String(status || 'QUEUED').toUpperCase();
}

function statusClass(status) {
  return normalizeStatus(status).toLowerCase();
}

function isTerminal(status) {
  return TERMINAL.has(normalizeStatus(status));
}

function syncStartButton() {
  const keyReady = Boolean(state.config && state.config.apiKeyConfigured);
  const status = normalizeStatus(state.status);
  const busy = Boolean(state.selectedId) && (status === 'RUNNING' || status === 'QUEUED');
  $('startBtn').disabled = state.starting || !keyReady || busy;
  if (!state.starting) {
    const label = $('startBtn').querySelector('span');
    if (label) {
      label.textContent = busy ? 'Agent running…' : state.selectedId ? 'Send follow-up' : 'Start agent';
    }
  }
}

function updateCharCount() {
  const length = $('taskInput').value.length;
  $('charCount').textContent = `${length.toLocaleString()} / 20,000`;
}

function setKeyState(config) {
  const pill = $('keyPill');
  const text = $('keyText');
  pill.classList.remove('ok', 'bad');
  if (config.apiKeyConfigured) {
    pill.classList.add('ok');
    text.textContent = 'key ready';
    $('keyWarning').classList.add('hidden');
  } else {
    pill.classList.add('bad');
    text.textContent = 'key missing';
    $('keyWarning').classList.remove('hidden');
  }
  syncStartButton();
}

function applyConfig(config) {
  state.config = config;
  $('modelPill').textContent = config.model || 'deepseek-chat';
  $('modelPill').title = config.baseUrl || '';
  $('emptyTools').textContent = config.toolCount ?? state.tools.length;
  $('emptySkills').textContent = config.skillCount ?? state.skills.length;
  setKeyState(config);
  if (!$('runView').classList.contains('hidden')) return;
  if (config.maxSteps) {
    state.maxSteps = config.maxSteps;
    $('runTurn').textContent = `0 / ${config.maxSteps}`;
  }
}

function toast(message, isError = false) {
  const node = $('toast');
  node.textContent = message;
  node.classList.toggle('error', isError);
  node.classList.remove('hidden');
  clearTimeout(state.toastTimer);
  state.toastTimer = setTimeout(() => node.classList.add('hidden'), 4800);
}

function shortId(id) {
  return id || '';
}

function timeLabel(timestamp) {
  if (!timestamp) return '';
  return new Date(timestamp).toLocaleTimeString([], { hour: '2-digit', minute: '2-digit', second: '2-digit' });
}

function formatElapsed(milliseconds) {
  const total = Math.max(0, Math.floor(milliseconds / 1000));
  const minutes = Math.floor(total / 60);
  const seconds = total % 60;
  return `${String(minutes).padStart(2, '0')}:${String(seconds).padStart(2, '0')}`;
}

function formatSize(bytes) {
  if (bytes === null || bytes === undefined) return '';
  if (bytes < 1024) return `${bytes} B`;
  if (bytes < 1024 * 1024) return `${(bytes / 1024).toFixed(1)} KB`;
  return `${(bytes / (1024 * 1024)).toFixed(1)} MB`;
}

function formatDuration(milliseconds) {
  if (milliseconds === null || milliseconds === undefined) return '';
  if (milliseconds < 1000) return `${milliseconds} ms`;
  return `${(milliseconds / 1000).toFixed(1)} s`;
}

function truncate(value, max = 110) {
  const text = String(value ?? '');
  return text.length > max ? `${text.slice(0, max - 1)}…` : text;
}

/* ------------------------------------------------------------------ runs */

async function loadInitial() {
  const config = await api('/api/config');
  applyConfig(config);
  const [runs, tools, skills] = await Promise.all([
    api('/api/runs'),
    api('/api/tools').catch(() => []),
    api('/api/skills').catch(() => [])
  ]);
  state.runs = Array.isArray(runs) ? runs : [];
  state.tools = Array.isArray(tools) ? tools : [];
  state.skills = Array.isArray(skills) ? skills : [];
  $('emptyTools').textContent = config.toolCount ?? state.tools.length;
  $('emptySkills').textContent = config.skillCount ?? state.skills.length;
  renderRunList();
  if (state.runs.length > 0) {
    selectRun(state.runs[0].id);
  } else {
    showEmpty();
  }
}

async function refreshRuns() {
  try {
    const runs = await api('/api/runs');
    if (Array.isArray(runs)) {
      state.runs = runs;
      renderRunList();
    }
  } catch (error) {
    // A background poll must not interrupt the active view.
  }
}

function showEmpty() {
  $('runView').classList.add('hidden');
  $('emptyState').classList.remove('hidden');
  $('cancelBtn').hidden = true;
  setStreamState(null);
  state.toolNodes.clear();
  state.verification = null;
  state.currentStep = 0;
  state.usage = { promptTokens: 0, completionTokens: 0, totalTokens: 0 };
  clearTimeline();
  renderPlan([]);
  renderFiles([]);
  renderUsage();
  $('statTurns').textContent = '0';
  renderVerificationPending();
  $('artifactLine').classList.add('hidden');
  state.status = 'IDLE';
  syncStartButton();
}

function renderRunList() {
  const list = $('runList');
  list.replaceChildren();
  const runs = [...state.runs].sort((a, b) => (b.startedAt || 0) - (a.startedAt || 0));
  $('runCount').textContent = String(runs.length);
  if (runs.length === 0) {
    list.appendChild(el('p', 'empty-note', 'No saved sessions yet. Start one above; it will survive a restart.'));
    return;
  }
  for (const run of runs) {
    const status = normalizeStatus(run.status);
    const button = el('button', `run-item${run.id === state.selectedId ? ' active' : ''}`);
    button.type = 'button';
    button.title = run.task || run.id;
    const task = el('span', 'run-item-task', run.task || '(untitled task)');
    const meta = el('span', 'run-item-meta');
    const id = el('span', 'run-item-id', shortId(run.id));
    const chip = el('span', `mini-status ${statusClass(status)}`, STATUS_TEXT[status] || status.toLowerCase());
    meta.append(id, chip);
    button.append(task, meta);
    button.addEventListener('click', () => selectRun(run.id));
    list.appendChild(button);
  }
}

async function selectRun(id) {
  if (!id) return;
  const token = ++state.selectionToken;
  stopStream();
  stopTimer();
  state.selectedId = id;
  state.toolNodes.clear();
  state.visited.clear();
  state.currentStep = 0;
  state.activePhase = null;
  state.phaseDetail = '';
  state.timelineCount = 0;
  state.terminal = false;
  state.finishRendered = false;
  state.startedAt = 0;
  state.finishedAt = 0;
  state.lastSeq = 0;
  state.usage = { promptTokens: 0, completionTokens: 0, totalTokens: 0 };
  state.verification = null;
  clearTimeline();
  renderPlan([]);
  renderFiles([]);
  renderUsage();
  renderVerificationPending();
  $('artifactLine').classList.add('hidden');
  $('emptyState').classList.add('hidden');
  $('runView').classList.remove('hidden');
  $('runStatus').className = 'status-badge';
  $('runStatusText').textContent = 'loading';
  $('runTask').textContent = 'Loading run…';
  $('runId').textContent = id;
  $('runWorkspace').textContent = '—';
  $('runTurn').textContent = `0 / ${state.maxSteps}`;
  $('runElapsed').textContent = '00:00';
  $('streamState').className = 'stream-state';
  $('streamState').textContent = 'connecting';
  $('cancelBtn').hidden = true;
  renderPhases();
  renderRunList();

  try {
    const snapshot = await api(`/api/runs/${encodeURIComponent(id)}`);
    if (token !== state.selectionToken) return;
    applySnapshot(snapshot, true);
    connect(id, 0);
    loadFiles();
    syncStartButton();
  } catch (error) {
    if (token !== state.selectionToken) return;
    toast(error.message, true);
    showEmpty();
  }
}

function applySnapshot(snapshot, initial = false) {
  if (!snapshot) return;
  const status = normalizeStatus(snapshot.status);
  state.status = status;
  state.startedAt = snapshot.startedAt || state.startedAt;
  state.finishedAt = snapshot.finishedAt || 0;
  state.maxSteps = snapshot.maxSteps || state.maxSteps;
  state.terminal = isTerminal(status);

  $('runTask').textContent = snapshot.task || '(untitled task)';
  $('runId').textContent = snapshot.id || '';
  $('runWorkspace').textContent = snapshot.workspace || '—';
  $('runTurn').textContent = `${snapshot.step || 0} / ${state.maxSteps}`;
  $('turnReadout').textContent = `Turn ${snapshot.step || 0} / ${state.maxSteps}`;
  if (snapshot.step && snapshot.step > state.currentStep) {
    state.currentStep = snapshot.step;
  }
  if (initial && snapshot.phase) {
    state.activePhase = snapshot.phase;
    state.phaseDetail = snapshot.phaseLabel ? `${snapshot.phaseLabel} — ${snapshot.step || 0}` : '';
  }
  setRunStatus(status);
  renderPlan(snapshot.plan || []);
  renderUsage(snapshot.usage || state.usage);
  if (initial) {
    state.verification = null;
    renderVerificationSnapshot(snapshot.verification);
  }
  if (snapshot.transcriptPath) {
    $('artifactLine').classList.remove('hidden');
    $('artifactPath').textContent = snapshot.transcriptPath;
  } else if (initial) {
    $('artifactLine').classList.add('hidden');
  }
  renderPhases();
  renderPhaseDetail();
  updateElapsed();
  if (state.terminal) stopTimer();
  if (state.terminal && initial) {
    $('streamState').className = 'stream-state';
    $('streamState').textContent = 'replaying';
  }
}

function setRunStatus(status) {
  const normalized = normalizeStatus(status);
  state.status = normalized;
  const badge = $('runStatus');
  badge.className = `status-badge ${statusClass(normalized)}`;
  $('runStatusText').textContent = STATUS_TEXT[normalized] || normalized.toLowerCase();
  const active = normalized === 'RUNNING' || normalized === 'QUEUED';
  $('cancelBtn').hidden = !active;
  $('telemetryLive').classList.toggle('on', normalized === 'RUNNING');
  if (normalized === 'RUNNING') {
    startTimer();
  } else if (isTerminal(normalized)) {
    stopTimer();
  }
  syncStartButton();
}

/* ------------------------------------------------------------------ plan / files / telemetry */

function renderPlan(plan) {
  const list = $('planList');
  list.replaceChildren();
  const items = Array.isArray(plan) ? plan : [];
  $('planCount').textContent = String(items.length);
  if (items.length === 0) {
    const note = el('li', 'empty-note', 'The agent will publish its plan here.');
    list.appendChild(note);
    return;
  }
  items.forEach((item, index) => {
    const status = String(item.status || 'pending').toLowerCase();
    const li = el('li', `plan-item ${status}`);
    const mark = el('span', 'plan-mark', status === 'completed' ? '✓' : status === 'in_progress' ? '·' : String(index + 1).padStart(2, '0'));
    const text = el('span', 'plan-text', item.step || '');
    li.append(mark, text);
    list.appendChild(li);
  });
}

async function loadFiles() {
  const id = state.selectedId;
  if (!id) return;
  try {
    const files = await api(`/api/runs/${encodeURIComponent(id)}/files`);
    if (id !== state.selectedId) return;
    renderFiles(Array.isArray(files) ? files : []);
  } catch (error) {
    // Workspace may be empty or the run may have just started; the next event retries.
  }
}

function scheduleFileRefresh() {
  clearTimeout(state.fileTimer);
  state.fileTimer = setTimeout(loadFiles, 450);
}

function renderFiles(files) {
  const list = $('fileList');
  list.replaceChildren();
  const entries = (Array.isArray(files) ? files : []).filter((file) => !file.directory);
  $('fileCount').textContent = String(entries.length);
  if (entries.length === 0) {
    list.appendChild(el('p', 'empty-note', 'No files yet.'));
    return;
  }
  for (const file of entries) {
    const button = el('button', 'file-item');
    button.type = 'button';
    button.title = file.path;
    const ext = file.path.includes('.') ? file.path.split('.').pop().toLowerCase() : '';
    const glyph = el('span', 'file-glyph',
      ext === 'java' ? 'J'
        : ext === 'py' ? 'PY'
          : ext === 'js' ? 'JS'
            : ext === 'go' ? 'GO'
            : ext === 'md' ? 'M'
              : ext === 'json' ? '{}'
                : ext === 'xml' ? '<>'
                  : 'TXT');
    const path = el('span', 'file-path', file.path);
    const size = el('span', 'file-size', formatSize(file.size));
    button.append(glyph, path, size);
    button.addEventListener('click', () => openFile(file.path));
    list.appendChild(button);
  }
}

function renderUsage(usage) {
  const value = usage || state.usage;
  state.usage = {
    promptTokens: Number(value.promptTokens || 0),
    completionTokens: Number(value.completionTokens || 0),
    totalTokens: Number(value.totalTokens || 0)
  };
  $('statPrompt').textContent = state.usage.promptTokens.toLocaleString();
  $('statCompletion').textContent = state.usage.completionTokens.toLocaleString();
  $('statTotal').textContent = state.usage.totalTokens.toLocaleString();
  $('statTurns').textContent = String(state.currentStep || 0);
}

function renderVerificationPanel(ok, skipped, summary, command) {
  const panel = $('verifyPanel');
  panel.replaceChildren();
  const line = el('div', 'verify-line');
  let mark;
  let label;
  if (skipped) {
    panel.className = 'verify-panel';
    mark = el('span', 'verify-mark pending', '–');
    label = 'Verification skipped';
  } else if (ok) {
    panel.className = 'verify-panel ok';
    mark = el('span', 'verify-mark', '✓');
    label = 'Build verified';
  } else {
    panel.className = 'verify-panel fail';
    mark = el('span', 'verify-mark', '×');
    label = 'Verification failed';
  }
  line.append(mark, el('span', null, label));
  panel.appendChild(line);
  panel.appendChild(el('p', null, summary || 'The verification gate runs after source edits.'));
  if (command) {
    const commandLine = el('p', 'verify-command', truncate(command, 180));
    panel.appendChild(commandLine);
  }
}

function renderVerificationPending() {
  const panel = $('verifyPanel');
  panel.className = 'verify-panel';
  panel.replaceChildren();
  const line = el('div', 'verify-line');
  line.append(el('span', 'verify-mark pending', '○'), el('span', null, 'Not verified yet'));
  panel.appendChild(line);
  panel.appendChild(el('p', null, 'Auto-verify runs after source edits. A failed build goes back to the model.'));
}

function renderVerificationSnapshot(summary) {
  if (!summary) {
    renderVerificationPending();
    return;
  }
  const panel = $('verifyPanel');
  panel.className = 'verify-panel';
  panel.replaceChildren();
  const line = el('div', 'verify-line');
  line.append(el('span', 'verify-mark pending', '·'), el('span', null, 'Verification gate'));
  panel.appendChild(line);
  panel.appendChild(el('p', null, summary));
}

/* ------------------------------------------------------------------ phase rail */

function renderPhases() {
  const grid = $('phaseGrid');
  grid.replaceChildren();
  for (const phase of PHASES) {
    const cell = el('div', 'phase-cell');
    cell.title = phase.detail;
    if (phase.id === state.activePhase) cell.classList.add('active');
    else if (state.visited.has(phase.id)) cell.classList.add('done');
    const index = el('span', 'phase-index', String(PHASES.indexOf(phase) + 1).padStart(2, '0'));
    const name = el('span', 'phase-name', phase.label);
    const dot = el('span', 'phase-state');
    cell.append(index, name, dot);
    grid.appendChild(cell);
  }
  $('runTurn').textContent = `${state.currentStep || 0} / ${state.maxSteps}`;
  $('turnReadout').textContent = `Turn ${state.currentStep || 0} / ${state.maxSteps}`;
  $('statTurns').textContent = String(state.currentStep || 0);
}

function renderPhaseDetail() {
  const detail = $('phaseDetail');
  const label = $('phaseDetail').querySelector('.phase-detail-label');
  detail.classList.toggle('active', Boolean(state.activePhase));
  if (state.activePhase) {
    label.textContent = PHASE_LABEL[state.activePhase] || state.activePhase;
    $('phaseDetailText').textContent = state.phaseDetail || PHASE_DETAIL[state.activePhase] || 'Working…';
  } else {
    label.textContent = 'waiting';
    $('phaseDetailText').textContent = 'The agent has not started yet.';
  }
}

/* ------------------------------------------------------------------ event stream */

function setStreamState(value) {
  const node = $('streamState');
  node.className = 'stream-state';
  if (!value) {
    node.textContent = 'idle';
    return;
  }
  if (value === 'live') {
    node.classList.add('connected');
    node.textContent = 'live';
  } else if (value === 'closed') {
    node.classList.add('closed');
    node.textContent = 'closed';
  } else if (value === 'error') {
    node.classList.add('error');
    node.textContent = 'reconnecting';
  } else {
    node.textContent = value;
  }
}

function connect(id, after = 0) {
  stopStream();
  state.lastSeq = after;
  const suffix = after > 0 ? `?after=${after}` : '';
  const es = new EventSource(`/api/runs/${encodeURIComponent(id)}/events${suffix}`);
  state.es = es;
  setStreamState('connecting');

  for (const type of EVENT_TYPES) {
    es.addEventListener(type, (event) => {
      if (state.es !== es) return;
      try {
        const payload = JSON.parse(event.data);
        state.lastSeq = Number(payload.seq || state.lastSeq || 0);
        handleEvent(payload.type || type, payload.data || {}, payload.timestamp);
      } catch (error) {
        console.error('Cannot parse event', error);
      }
    });
  }

  es.addEventListener('stream_end', () => {
    if (state.es !== es) return;
    setStreamState('closed');
    es.close();
    state.es = null;
    if (!state.terminal) {
      syncSnapshot();
    }
  });

  es.onopen = () => {
    if (state.es !== es) return;
    setStreamState('live');
  };

  es.onerror = () => {
    if (state.es !== es) return;
    if (state.terminal) {
      setStreamState('closed');
      es.close();
      state.es = null;
    } else {
      setStreamState('error');
    }
  };
}

function stopStream() {
  if (state.es) {
    const es = state.es;
    state.es = null;
    es.close();
  }
}

async function syncSnapshot() {
  const id = state.selectedId;
  if (!id) return;
  try {
    const snapshot = await api(`/api/runs/${encodeURIComponent(id)}`);
    if (id === state.selectedId) {
      applySnapshot(snapshot, false);
      if (state.terminal) finalizeRun(snapshot);
    }
  } catch (error) {
    toast(error.message, true);
  }
}

function handleEvent(type, data, timestamp) {
  switch (type) {
    case 'run.started':
      if (data.snapshot) applySnapshot(data.snapshot, true);
      break;

    case 'turn.started':
      beginFollowUpTurn(data, timestamp);
      break;

    case 'phase': {
      const step = Number(data.step || 0);
      if (step !== state.currentStep) {
        state.currentStep = step;
        state.visited.clear();
      }
      if (data.maxSteps) state.maxSteps = Number(data.maxSteps);
      if (data.phase) {
        state.activePhase = data.phase;
        state.visited.add(data.phase);
      }
      state.phaseDetail = data.detail || '';
      setRunStatus('RUNNING');
      renderPhases();
      renderPhaseDetail();
      renderUsage();
      break;
    }

    case 'assistant':
      addAssistant(data, timestamp);
      break;

    case 'llm.request':
      addLlmRequest(data, timestamp);
      break;

    case 'tool.start':
      addToolStart(data, timestamp);
      break;

    case 'tool.end':
      addToolEnd(data, timestamp);
      scheduleFileRefresh();
      break;

    case 'usage':
      if (data.usage) renderUsage(data.usage);
      break;

    case 'plan':
      renderPlan(data.plan || []);
      break;

    case 'workspace':
      scheduleFileRefresh();
      break;

    case 'verification':
      state.verification = data;
      renderVerificationPanel(
        Boolean(data.ok),
        Boolean(data.skipped),
        data.summary || '',
        data.command || ''
      );
      addVerification(data, timestamp);
      break;

    case 'compaction':
      addCompaction(data, timestamp);
      break;

    case 'run.succeeded':
      setRunStatus('SUCCEEDED');
      break;

    case 'run.failed':
      setRunStatus('FAILED');
      break;

    case 'run.cancelled':
      setRunStatus('CANCELLED');
      break;

    case 'run.max_steps':
      setRunStatus('MAX_STEPS');
      break;

    case 'run.finished':
      if (data.snapshot) {
        finalizeRun(data.snapshot);
      }
      break;

    default:
      break;
  }
}

function beginFollowUpTurn(data, timestamp) {
  state.terminal = false;
  state.finishRendered = false;
  state.visited.clear();
  state.currentStep = 0;
  state.activePhase = null;
  state.phaseDetail = '';
  state.verification = null;
  if (data.snapshot) applySnapshot(data.snapshot, false);
  setRunStatus('RUNNING');
  renderVerificationPending();
  addUserMessage(data.message || 'Follow-up', timestamp);
  renderPhases();
  renderPhaseDetail();
  if (!state.startedAt) state.startedAt = timestamp || Date.now();
  startTimer();
}

function addUserMessage(message, timestamp) {
  const { entry } = traceEntry('user', timestamp);
  const label = el('div', 'user-label', 'you');
  const body = el('div', 'user-text');
  renderMarkdown(body, String(message || ''));
  entry.append(label, body);
  scrollTimeline(true);
}

function finalizeRun(snapshot) {
  applySnapshot(snapshot, false);
  state.terminal = true;
  state.finishedAt = snapshot.finishedAt || Date.now();
  stopTimer();
  updateElapsed();
  if (!state.finishRendered) {
    addFinish(snapshot);
    state.finishRendered = true;
  }
  state.runs = state.runs.map((run) => (run.id === snapshot.id ? { ...run, ...snapshot } : run));
  renderRunList();
  // The session log stays open after a turn so a follow-up can reuse this SSE
  // connection. Only a closing server emits stream_end.
  if (state.es) setStreamState('live');
  syncStartButton();
}

/* ------------------------------------------------------------------ timeline */

function clearTimeline() {
  const timeline = $('timeline');
  timeline.replaceChildren();
  const empty = el('div', 'timeline-empty');
  empty.id = 'timelineEmpty';
  empty.append(el('span', 'scanner'), document.createTextNode('Waiting for the first event…'));
  timeline.appendChild(empty);
  $('traceCount').textContent = '0 events';
}

function ensureTimelineReady() {
  const empty = $('timelineEmpty');
  if (empty) empty.remove();
}

function traceEntry(kind, timestamp) {
  ensureTimelineReady();
  const timeline = $('timeline');
  const stick = timeline.scrollTop + timeline.clientHeight >= timeline.scrollHeight - 80;
  const entry = el('div', `trace-entry ${kind}`);
  const dot = el('div', 'trace-dot');
  const top = el('div', 'trace-top');
  const kindLabel = kind === 'verify' ? 'verification' : kind === 'tool' ? 'tool call' : kind === 'llm' ? 'llm request' : kind === 'user' ? 'you' : kind;
  const label = el('span', 'trace-kind', kindLabel);
  const time = el('span', 'trace-time', timeLabel(timestamp));
  top.append(label, time);
  entry.append(dot, top);
  timeline.appendChild(entry);
  state.timelineCount += 1;
  $('traceCount').textContent = `${state.timelineCount} ${state.timelineCount === 1 ? 'event' : 'events'}`;
  if (stick) timeline.scrollTop = timeline.scrollHeight;
  return { entry, top };
}

function scrollTimeline(force = false) {
  const timeline = $('timeline');
  const stick = timeline.scrollTop + timeline.clientHeight >= timeline.scrollHeight - 140;
  if (force || stick) {
    requestAnimationFrame(() => {
      timeline.scrollTop = timeline.scrollHeight;
    });
  }
}

function appendInline(parent, text) {
  const parts = String(text ?? '').split(/(`[^`]+`|\*\*[^*]+\*\*)/g);
  for (const part of parts) {
    if (!part) continue;
    if (part.startsWith('`') && part.endsWith('`') && part.length > 2) {
      parent.appendChild(el('code', null, part.slice(1, -1)));
    } else if (part.startsWith('**') && part.endsWith('**') && part.length > 4) {
      parent.appendChild(el('strong', null, part.slice(2, -2)));
    } else {
      parent.appendChild(document.createTextNode(part));
    }
  }
}

function appendTextBlock(parent, block) {
  const lines = block.split('\n');
  const meaningful = lines.filter((line) => line.trim().length > 0);
  if (meaningful.length === 0) return;

  if (meaningful.every((line) => /^[-*]\s+/.test(line.trim()))) {
    for (const line of meaningful) {
      const item = el('span', 'md-li');
      appendInline(item, line.trim().replace(/^[-*]\s+/, ''));
      parent.appendChild(item);
    }
    return;
  }

  if (meaningful.length === 1 && /^#{1,4}\s+/.test(meaningful[0].trim())) {
    const heading = el('span', 'md-h');
    appendInline(heading, meaningful[0].trim().replace(/^#{1,4}\s+/, ''));
    parent.appendChild(heading);
    return;
  }

  const paragraph = el('span');
  appendInline(paragraph, block.trim());
  parent.appendChild(paragraph);
  parent.appendChild(document.createTextNode('\n'));
}

function renderMarkdown(parent, text) {
  const source = String(text ?? '');
  if (!source.trim()) return;
  const fence = /```([^\n`]*)\n([\s\S]*?)```/g;
  let cursor = 0;
  let match;
  while ((match = fence.exec(source)) !== null) {
    const before = source.slice(cursor, match.index).trim();
    if (before) appendTextBlock(parent, before);
    const code = el('code', null, match[2].replace(/\s+$/, ''));
    const pre = el('pre', 'md-code');
    pre.appendChild(code);
    parent.appendChild(pre);
    cursor = fence.lastIndex;
  }
  const tail = source.slice(cursor).trim();
  if (tail) appendTextBlock(parent, tail);
}

function addLlmRequest(data, timestamp) {
  const raw = typeof data.body === 'string' ? data.body : '';
  if (!raw.trim()) return;
  const { entry, top } = traceEntry('llm', timestamp);
  const messages = Number(data.messages || 0);
  const tools = Number(data.tools || 0);
  const chars = Number(data.chars || raw.length);
  top.appendChild(el('span', 'trace-tag', `${messages} messages`));

  const details = el('details', 'llm-request');
  const summary = el('summary', 'llm-request-summary');
  summary.append(
    el('span', 'llm-request-title', 'request body'),
    el('span', 'llm-request-meta', `${tools} tools · ${chars.toLocaleString()} chars`)
  );
  details.append(summary, el('pre', 'llm-request-body', prettyArguments(raw)));
  entry.appendChild(details);
  scrollTimeline();
}

function addAssistant(data, timestamp) {
  const content = data.content || '';
  const toolCalls = Array.isArray(data.toolCalls) ? data.toolCalls : [];
  if (!content.trim() && toolCalls.length === 0) return;
  const { entry, top } = traceEntry('assistant', timestamp);
  if (toolCalls.length > 0) {
    top.appendChild(el('span', 'trace-tag', `${toolCalls.length} call${toolCalls.length === 1 ? '' : 's'}`));
  }
  const text = el('div', 'assistant-text');
  if (content.trim()) {
    renderMarkdown(text, content);
  } else {
    text.textContent = `Requesting ${toolCalls.join(', ')}…`;
  }
  entry.appendChild(text);
  scrollTimeline();
}

function prettyArguments(raw) {
  if (typeof raw !== 'string') {
    try {
      return JSON.stringify(raw, null, 2);
    } catch (error) {
      return String(raw);
    }
  }
  try {
    return JSON.stringify(JSON.parse(raw), null, 2);
  } catch (error) {
    return raw;
  }
}

function compactArguments(raw) {
  if (typeof raw !== 'string') {
    try {
      return JSON.stringify(raw);
    } catch (error) {
      return String(raw);
    }
  }
  try {
    return JSON.stringify(JSON.parse(raw));
  } catch (error) {
    return raw;
  }
}

function addToolStart(data, timestamp) {
  const id = data.id || `tool-${state.timelineCount}`;
  const { entry } = traceEntry('tool', timestamp);
  const details = el('details', 'tool-call');
  details.open = true;
  const summary = el('summary');
  const name = el('span', 'tool-name', data.name || 'tool');
  const argsPreview = el('span', 'tool-args-preview', truncate(compactArguments(data.arguments || ''), 95));
  const status = el('span', 'tool-status running', 'running');
  const duration = el('span', 'tool-duration', '');
  summary.append(name, argsPreview, status, duration);

  const detail = el('div', 'tool-detail');
  const argsLabel = el('span', 'tool-label', 'arguments');
  const args = el('pre', 'tool-code', prettyArguments(data.arguments || '{}'));
  const outputLabel = el('span', 'tool-label', 'output');
  const output = el('pre', 'tool-output', 'Waiting for output…');
  detail.append(argsLabel, args, outputLabel, output);
  details.append(summary, detail);
  entry.appendChild(details);
  state.toolNodes.set(id, { entry, status, duration, output, name: data.name || 'tool' });
  scrollTimeline();
}

function addToolEnd(data, timestamp) {
  const id = data.id || '';
  let node = state.toolNodes.get(id);
  if (!node) {
    const { entry } = traceEntry('tool', timestamp);
    const details = el('details', 'tool-call');
    const summary = el('summary');
    const name = el('span', 'tool-name', data.name || 'tool');
    const argsPreview = el('span', 'tool-args-preview', '');
    const status = el('span', 'tool-status', '');
    const duration = el('span', 'tool-duration', '');
    summary.append(name, argsPreview, status, duration);
    const detail = el('div', 'tool-detail');
    const output = el('pre', 'tool-output', '');
    detail.append(output);
    details.append(summary, detail);
    entry.appendChild(details);
    node = { entry, status, duration, output, name: data.name || 'tool' };
    state.toolNodes.set(id, node);
  }
  const ok = Boolean(data.ok);
  node.status.className = `tool-status ${ok ? 'ok' : 'error'}`;
  node.status.textContent = ok ? 'ok' : 'error';
  node.duration.textContent = formatDuration(data.durationMs);
  node.output.textContent = data.output || '(no output)';
  node.output.classList.toggle('error', !ok);
  if (data.data && Object.keys(data.data).length > 0) {
    const metaLabel = el('span', 'tool-label', 'metadata');
    const meta = el('pre', 'tool-code', JSON.stringify(data.data, null, 2));
    node.output.parentElement.appendChild(metaLabel);
    node.output.parentElement.appendChild(meta);
  }
  scrollTimeline();
}

function addVerification(data, timestamp) {
  const { entry } = traceEntry('verify', timestamp);
  const ok = Boolean(data.ok);
  const skipped = Boolean(data.skipped);
  entry.classList.add(ok ? 'ok' : 'fail');
  const box = el('div', `verify-box ${ok ? '' : 'fail'}${skipped ? ' skip' : ''}`.trim());
  const title = el('div', 'verify-title');
  title.append(el('span', null, skipped ? '—' : ok ? '✓' : '×'), el('span', null,
    skipped ? 'verification skipped' : ok ? 'build passed' : 'build failed'));
  box.appendChild(title);
  box.appendChild(el('p', 'verify-summary', data.summary || 'No verification summary.'));
  if (data.command) box.appendChild(el('p', 'verify-command', data.command));
  if (data.output) box.appendChild(el('pre', 'verify-output', data.output));
  entry.appendChild(box);
  scrollTimeline();
}

function addCompaction(data, timestamp) {
  const { entry } = traceEntry('system', timestamp);
  const note = el('div', 'system-note');
  note.append(
    el('span', 'system-key', 'context compacted · '),
    document.createTextNode(`${data.removed ?? 0} messages removed, ${Number(data.beforeChars || 0).toLocaleString()} → ${Number(data.afterChars || 0).toLocaleString()} chars`)
  );
  entry.appendChild(note);
  scrollTimeline();
}

function addFinish(snapshot) {
  const status = normalizeStatus(snapshot.status);
  const { entry } = traceEntry('finish', snapshot.finishedAt || Date.now());
  entry.classList.toggle('fail', status === 'FAILED');
  const box = el('div', `finish-box${status === 'FAILED' ? ' fail' : ''}`);
  const head = el('div', 'finish-head');
  const title = status === 'SUCCEEDED' ? 'task complete · verified' : status === 'FAILED' ? 'run failed' : status === 'CANCELLED' ? 'run cancelled' : 'turn limit reached';
  head.append(el('span', null, title), el('span', null, snapshot.id || ''));
  box.appendChild(head);

  const answer = snapshot.finalAnswer || snapshot.error || '';
  if (answer) {
    const body = el('div', 'finish-answer');
    renderMarkdown(body, answer);
    box.appendChild(body);
  }
  const meta = el('div', 'finish-meta');
  const tokens = snapshot.usage ? snapshot.usage.totalTokens || 0 : 0;
  meta.textContent = `${snapshot.step || 0} turns · ${tokens.toLocaleString()} tokens` + (snapshot.verification ? ` · ${snapshot.verification}` : '');
  box.appendChild(meta);
  entry.appendChild(box);
  scrollTimeline(true);
}

/* ------------------------------------------------------------------ timers */

function startTimer() {
  stopTimer();
  updateElapsed();
  state.elapsedTimer = setInterval(updateElapsed, 1000);
}

function stopTimer() {
  if (state.elapsedTimer) {
    clearInterval(state.elapsedTimer);
    state.elapsedTimer = null;
  }
}

function updateElapsed() {
  if (!state.startedAt) {
    $('runElapsed').textContent = '00:00';
    return;
  }
  const end = state.terminal && state.finishedAt ? state.finishedAt : Date.now();
  $('runElapsed').textContent = formatElapsed(end - state.startedAt);
}

/* ------------------------------------------------------------------ composer */

async function startRun() {
  const task = $('taskInput').value.trim();
  if (!task) {
    toast('Write a task first.', true);
    $('taskInput').focus();
    return;
  }
  if (!state.config || !state.config.apiKeyConfigured) {
    toast('DEEPSEEK_API_KEY is missing. Set it in .env and restart the server.', true);
    return;
  }
  if (state.starting) return;
  state.starting = true;
  syncStartButton();
  const label = $('startBtn').querySelector('span');
  label.textContent = 'Starting…';
  const continuing = Boolean(state.selectedId && state.terminal);
  try {
    const result = continuing
      ? await api(`/api/runs/${encodeURIComponent(state.selectedId)}/messages`, {
          method: 'POST',
          body: JSON.stringify({ message: task })
        })
      : await api('/api/runs', {
          method: 'POST',
          body: JSON.stringify({ task })
        });
    const snapshot = result.run;
    state.runs = [snapshot, ...state.runs.filter((run) => run.id !== snapshot.id)];
    renderRunList();
    $('taskInput').value = '';
    updateCharCount();
    if (continuing) {
      state.terminal = false;
      state.finishRendered = false;
      state.visited.clear();
      state.currentStep = 0;
      state.verification = null;
      applySnapshot(snapshot, false);
      renderVerificationPending();
      if (!state.es) connect(snapshot.id, state.lastSeq);
      toast('Follow-up sent. The session keeps its workspace and transcript.');
    } else {
      await selectRun(snapshot.id);
      toast('Run started. The agent is working in an isolated workspace.');
    }
  } catch (error) {
    toast(error.message, true);
  } finally {
    state.starting = false;
    syncStartButton();
  }
}

function newRun() {
  stopStream();
  stopTimer();
  state.selectedId = null;
  state.selectionToken += 1;
  showEmpty();
  renderRunList();
  $('taskInput').focus();
}

async function cancelRun() {
  const id = state.selectedId;
  if (!id) return;
  $('cancelBtn').disabled = true;
  try {
    const snapshot = await api(`/api/runs/${encodeURIComponent(id)}/cancel`, { method: 'POST' });
    applySnapshot(snapshot, false);
    toast('Cancellation requested.');
  } catch (error) {
    toast(error.message, true);
  } finally {
    $('cancelBtn').disabled = false;
  }
}

/* ------------------------------------------------------------------ modals */

function openModal(title) {
  state.lastFocused = document.activeElement;
  $('modalTitle').textContent = title;
  $('modalBody').replaceChildren();
  $('modalBackdrop').classList.remove('hidden');
  document.body.classList.add('modal-open');
  $('modalClose').focus();
}

function closeModal() {
  $('modalBackdrop').classList.add('hidden');
  document.body.classList.remove('modal-open');
  if (state.lastFocused && state.lastFocused.focus) {
    state.lastFocused.focus();
  }
}

function modalLoading(message) {
  $('modalBody').appendChild(el('p', 'modal-lede', message));
}

function showHowItWorks() {
  openModal('How the agent works');
  const body = $('modalBody');
  body.appendChild(el('p', 'modal-lede',
    'Jade runs a full ten-phase pipeline on every model turn. A session keeps its workspace and transcript across follow-ups; each turn is capped by the configured limit so the agent can inspect, edit, compile, fix, and rerun without looping forever.'));

  const loop = el('div', 'how-grid');
  PHASES.forEach((phase, index) => {
    const step = el('div', 'how-step');
    step.append(
      el('div', 'how-num', String(index + 1).padStart(2, '0')),
      (() => {
        const copy = el('div');
        copy.append(el('strong', null, phase.label), el('p', null, phase.detail));
        return copy;
      })()
    );
    loop.appendChild(step);
  });
  const loopSection = el('section', 'modal-section');
  loopSection.append(el('h4', null, 'The ten-phase loop'), loop);
  body.appendChild(loopSection);

  const runtime = el('div', 'how-grid');
  const facts = [
    ['Languages', 'The workspace is detected as Java, Python, JavaScript, or Go from its source files. One run has one primary language; compile_code and run_code adapt to it.'],
    ['Tool registration', 'Twelve built-ins are registered in a ToolRegistry. Additional ToolProvider implementations are discovered at startup with Java ServiceLoader from META-INF/services.'],
    ['Skill discovery', 'At startup Jade walks skills/**/SKILL.md, parses simple frontmatter, scores the catalog against the task, injects the top two playbooks, and exposes load_skill for on-demand reads.'],
    ['Verification gate', 'After source edits, automatic verification runs Maven/javac, python3 -m py_compile, node --check, or go build through the real workspace. A failure is fed back to DeepSeek; success is the only path to a completed run.'],
    ['Sessions', 'A session owns one run id, one workspace, and one transcript. Sending a follow-up appends a user turn to the same transcript and reuses the open SSE stream; New run creates a fresh isolated workspace.'],
    ['Isolation limits', 'Each session gets its own agent-workspaces/run-* directory and all paths go through a workspace jail. Shell commands run with a sanitized environment and, on macOS, a sandbox-exec write jail with network denied by default. Reads and kernel isolation are still shared with the host; this is not a VM.'],
    ['Transport', 'The local HTTP server exposes a JSON API and an SSE event stream. The browser never receives the API key.']
  ];
  facts.forEach(([title, text]) => {
    const step = el('div', 'how-step');
    step.append(el('div', 'how-num', '·'), (() => {
      const copy = el('div');
      copy.append(el('strong', null, title), el('p', null, text));
      return copy;
    })());
    runtime.appendChild(step);
  });
  const runtimeSection = el('section', 'modal-section');
  runtimeSection.append(el('h4', null, 'Mechanisms'), runtime);
  body.appendChild(runtimeSection);
}

async function showSkills() {
  openModal('Skills');
  modalLoading('Discovering SKILL.md files…');
  try {
    state.skills = await api('/api/skills');
  } catch (error) {
    $('modalBody').replaceChildren(el('div', 'modal-error', error.message));
    return;
  }
  const body = $('modalBody');
  body.replaceChildren();
  body.appendChild(el('p', 'modal-lede',
    `${state.skills.length} skill playbook${state.skills.length === 1 ? '' : 's'} discovered from ${state.config ? state.config.skillsDir : 'skills/'}. The catalog is always in the system prompt; the top matches are loaded in full.`));
  const list = el('div', 'resource-list');
  if (state.skills.length === 0) {
    body.appendChild(el('p', 'empty-note', 'No skills found. Add a skills/<name>/SKILL.md file and restart.'));
    return;
  }
  for (const skill of state.skills) {
    const card = el('div', 'resource-card');
    const head = el('div', 'resource-head');
    head.append(el('span', 'resource-name', skill.name || 'unnamed'), el('span', 'resource-kind', 'SKILL.md'));
    card.appendChild(head);
    card.appendChild(el('p', 'resource-desc', skill.description || 'No description.'));
    if (Array.isArray(skill.tags) && skill.tags.length) {
      const tags = el('div', 'tag-row');
      skill.tags.forEach((tag) => tags.appendChild(el('span', 'tag', tag)));
      card.appendChild(tags);
    }
    card.appendChild(el('div', 'resource-path', skill.path || ''));
    list.appendChild(card);
  }
  body.appendChild(list);
}

async function showTools() {
  openModal('Tools');
  modalLoading('Reading the tool registry…');
  try {
    state.tools = await api('/api/tools');
  } catch (error) {
    $('modalBody').replaceChildren(el('div', 'modal-error', error.message));
    return;
  }
  const body = $('modalBody');
  body.replaceChildren();
  body.appendChild(el('p', 'modal-lede',
    `${state.tools.length} tools registered. Built-ins live in the runtime; external ToolProvider implementations are discovered through Java ServiceLoader at startup.`));
  const list = el('div', 'tool-modal-list');
  for (const tool of state.tools) {
    const details = el('details', 'tool-modal-item');
    const summary = el('summary');
    const name = el('span', 'tool-name', tool.name || 'tool');
    const badge = el('span', tool.mutating ? 'mutating-badge' : 'readonly-badge', tool.mutating ? 'writes' : 'read');
    if (!tool.mutating) badge.className = 'mutating-badge readonly-badge';
    summary.append(name, badge);
    details.appendChild(summary);
    details.appendChild(el('p', 'tool-desc', tool.description || ''));
    const schema = el('pre', 'tool-schema', JSON.stringify(tool.parameters || {}, null, 2));
    details.appendChild(schema);
    list.appendChild(details);
  }
  body.appendChild(list);
}

async function openFile(path) {
  const id = state.selectedId;
  if (!id) return;
  openModal('File preview');
  const body = $('modalBody');
  body.replaceChildren(el('p', 'modal-lede', `Reading ${path}…`));
  try {
    const data = await api(`/api/runs/${encodeURIComponent(id)}/file?path=${encodeURIComponent(path)}`);
    if (id !== state.selectedId) return;
    body.replaceChildren();
    const head = el('div', 'file-preview-head');
    const info = el('div');
    info.append(el('div', 'file-preview-path', data.path || path));
    head.appendChild(info);
    const copy = el('button', 'copy-button', 'Copy');
    copy.type = 'button';
    copy.addEventListener('click', async () => {
      try {
        await navigator.clipboard.writeText(data.content || '');
        toast('File copied to clipboard.');
      } catch (error) {
        toast('Clipboard is unavailable in this browser context.', true);
      }
    });
    head.appendChild(copy);
    body.appendChild(head);
    const pre = el('pre', 'preview-code', data.content || '(empty file)');
    body.appendChild(pre);
  } catch (error) {
    body.replaceChildren(el('div', 'modal-error', error.message));
  }
}

/* ------------------------------------------------------------------ wiring */

function wireEvents() {
  $('taskInput').addEventListener('input', updateCharCount);
  $('taskInput').addEventListener('keydown', (event) => {
    if ((event.metaKey || event.ctrlKey) && event.key === 'Enter') {
      event.preventDefault();
      startRun();
    }
  });
  $('startBtn').addEventListener('click', startRun);
  $('cancelBtn').addEventListener('click', cancelRun);
  $('newRunBtn').addEventListener('click', newRun);
  $('howBtn').addEventListener('click', showHowItWorks);
  $('skillsBtn').addEventListener('click', showSkills);
  $('toolsBtn').addEventListener('click', showTools);
  $('modalClose').addEventListener('click', closeModal);
  $('modalBackdrop').addEventListener('click', (event) => {
    if (event.target === $('modalBackdrop')) closeModal();
  });
  document.addEventListener('keydown', (event) => {
    if (event.key === 'Escape' && !$('modalBackdrop').classList.contains('hidden')) {
      closeModal();
    }
  });
  document.querySelectorAll('.example').forEach((button) => {
    button.addEventListener('click', () => {
      $('taskInput').value = button.dataset.task || '';
      updateCharCount();
      $('taskInput').focus();
      $('taskInput').scrollIntoView({ block: 'center', behavior: 'smooth' });
    });
  });
  window.addEventListener('beforeunload', stopStream);
}

async function init() {
  wireEvents();
  updateCharCount();
  renderPhases();
  renderPhaseDetail();
  try {
    await loadInitial();
  } catch (error) {
    toast(`Cannot reach the agent server: ${error.message}`, true);
    showEmpty();
  }
  setInterval(refreshRuns, 8000);
}

document.addEventListener('DOMContentLoaded', init);
