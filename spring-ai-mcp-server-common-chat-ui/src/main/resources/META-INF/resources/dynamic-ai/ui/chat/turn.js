// Pure state of one assistant turn, built from dai-stream/1 events (LLD-13 §3). No DOM: the component renders it.

/** Tool results that count as failed steps. */
const FAILED = new Set(['error', 'not_permitted', 'unavailable']);

/** A new, empty assistant turn. */
export function newTurn() {
  return {
    turnId: null,
    conversationId: null,
    ui: null,
    text: '',
    steps: [],
    components: [],
    display: null,
    usage: null,
    status: 'streaming',
    finishReason: null,
    error: null,
  };
}

/** "find_orders" → "Find orders". */
export function humanizeTool(name) {
  const s = String(name || 'tool').replace(/[_-]+/g, ' ').replace(/([a-z])([A-Z])/g, '$1 $2').trim().toLowerCase();
  return s.charAt(0).toUpperCase() + s.slice(1);
}

function parsePayload(raw) {
  if (typeof raw !== 'string') return raw ?? null;
  try {
    return JSON.parse(raw);
  } catch {
    return raw;
  }
}

/**
 * Applies one event to a turn (mutates and returns it).
 *
 * @param {ReturnType<typeof newTurn>} turn
 * @param {{type: string}} ev parsed event data
 */
export function applyEvent(turn, ev) {
  switch (ev.type) {
    case 'turn.start':
      turn.turnId = ev.turnId;
      turn.conversationId = ev.conversationId;
      turn.ui = ev.ui || null;
      break;
    case 'text.delta':
      turn.text += ev.text || '';
      break;
    case 'step': {
      const existing = turn.steps.find((s) => s.id === ev.stepId);
      const step = existing || { id: ev.stepId, kind: 'step' };
      Object.assign(step, { title: ev.title, status: ev.status, detail: ev.detail ?? step.detail ?? null });
      if (!existing) turn.steps.push(step);
      break;
    }
    case 'tool.call':
      turn.steps.push({
        id: ev.callId, kind: 'tool', tool: ev.tool, title: humanizeTool(ev.tool), args: ev.argsPreview || '',
        status: 'running', detail: null,
      });
      break;
    case 'tool.result': {
      const step = turn.steps.find((s) => s.id === ev.callId);
      if (step) {
        step.status = FAILED.has(ev.status) ? 'error' : 'done';
        step.result = ev.status;
        step.detail = ev.summary || null;
      }
      break;
    }
    case 'ui.component': {
      const payload = parsePayload(ev.payload);
      if (ev.componentType === 'structured-response') {
        turn.display = payload;
      } else {
        turn.components.push({
          type: ev.componentType, componentId: ev.componentId || null, copyable: Boolean(ev.copyable),
          payload, answer: null,
        });
      }
      break;
    }
    case 'proposal.created':
    case 'proposal.updated':
    case 'proposal.applied':
      turn.components.push({ type: ev.type, componentId: ev.proposalId || null, copyable: false, payload: ev,
        answer: null });
      break;
    case 'usage':
      turn.usage = { inputTokens: ev.inputTokens, outputTokens: ev.outputTokens, model: ev.model };
      break;
    case 'turn.end':
      turn.status = 'done';
      turn.finishReason = ev.finishReason || 'stop';
      settleSteps(turn, 'done');
      break;
    case 'error':
      turn.status = 'error';
      turn.error = { code: ev.code, title: ev.title, retryable: Boolean(ev.retryable) };
      settleSteps(turn, 'error');
      break;
    default:
      break; // unknown events are ignored (forward compatible)
  }
  return turn;
}

/** Steps still running when the turn ends take the turn's outcome. */
function settleSteps(turn, status) {
  for (const s of turn.steps) {
    if (s.status === 'running') s.status = status;
  }
}

/** Summary line of a turn's steps: "Working…", "Used 2 tools", "3 steps". */
export function stepsSummary(turn) {
  const running = turn.steps.some((s) => s.status === 'running');
  if (running || (turn.status === 'streaming' && turn.steps.length === 0)) return 'Working…';
  const tools = turn.steps.filter((s) => s.kind === 'tool').length;
  const failed = turn.steps.filter((s) => s.status === 'error').length;
  let text = tools > 0
    ? `Used ${tools} tool${tools === 1 ? '' : 's'}`
    : `${turn.steps.length} step${turn.steps.length === 1 ? '' : 's'}`;
  if (failed) text += ` · ${failed} failed`;
  return text;
}

/**
 * Builds the chat's history from a stored transcript and the UI state (reload): one entry per message, with the
 * components and feedback of each assistant turn attached by turn id.
 *
 * @param {{role: string, content: string, turnId?: string}[]} messages transcript, oldest first
 * @param {{components?: object[], feedback?: object[]}} uiState
 * @returns {{role: string, text?: string, turn?: object, feedback?: string|null}[]}
 */
export function restoreHistory(messages, uiState) {
  const components = uiState?.components || [];
  const feedback = new Map((uiState?.feedback || []).map((f) => [f.turnId, f.rating]));
  const used = new Set();
  const entries = [];
  for (const m of messages || []) {
    if (m.role === 'USER') {
      entries.push({ role: 'user', text: m.content });
    } else if (m.role === 'ASSISTANT') {
      const turn = newTurn();
      turn.turnId = m.turnId || null;
      turn.text = m.content;
      turn.status = 'done';
      turn.components = components.filter((c) => c.turnId && c.turnId === m.turnId).map((c) => {
        used.add(c);
        return { type: c.componentType, componentId: c.componentId, copyable: false, payload: c.payload,
          answer: c.answer || null };
      });
      entries.push({ role: 'assistant', turn, feedback: m.turnId ? feedback.get(m.turnId) || null : null });
    }
  }
  // components whose message is not in the transcript (recording off): show them on their own, in order
  for (const c of components) {
    if (!used.has(c)) {
      const turn = newTurn();
      turn.turnId = c.turnId;
      turn.status = 'done';
      turn.components = [{ type: c.componentType, componentId: c.componentId, copyable: false, payload: c.payload,
        answer: c.answer || null }];
      entries.push({ role: 'assistant', turn, feedback: feedback.get(c.turnId) || null });
    }
  }
  return entries;
}
