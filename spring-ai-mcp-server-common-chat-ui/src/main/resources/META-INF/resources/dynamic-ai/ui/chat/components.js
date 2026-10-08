// Component registry of <saimcp-chat>: renders `ui.component` events (LLD-13 §3) inside the conversation.
//
// Built in: `choice` (questions with options, answered once), `structured-response` (the backend-controlled display
// tree, LLD-06 §8.3), `proposal.*` (write proposals awaiting review, LLD-11) and a collapsible JSON fallback.
// Hosts add their own:
//   - SaimcpChat.registerComponent('order-card', (component, ctx) => element)    a render function, or
//   - define a custom element whose tag equals the componentType ('order-card'); it receives `.payload` and
//     `.chatContext` properties.

const renderers = new Map();

/**
 * Registers a renderer for a component type.
 *
 * @param {string} type componentType sent by the backend
 * @param {(component: {type: string, componentId: string|null, payload: any, answer: any, copyable: boolean},
 *          ctx: object) => HTMLElement} render
 */
export function registerComponent(type, render) {
  if (typeof render !== 'function') throw new TypeError('render must be a function');
  renderers.set(type, render);
}

/** Whether a renderer exists for a type (built-in, registered or custom element). */
export function hasRenderer(type) {
  return renderers.has(type) || (type.includes('-') && Boolean(customElements.get(type)));
}

/**
 * Renders one component. Never throws: a failing renderer falls back to the JSON view.
 *
 * @param {object} component {type, componentId, payload, answer, copyable}
 * @param {object} ctx       {answer, sendMessage, copy, emit, persistent, interactive}
 * @returns {HTMLElement}
 */
export function renderComponent(component, ctx) {
  let el;
  try {
    const render = renderers.get(component.type);
    if (render) {
      el = render(component, ctx);
    } else if (component.type.includes('-') && customElements.get(component.type)) {
      el = document.createElement(component.type);
      el.payload = component.payload;
      el.chatContext = ctx;
    } else if (component.type.startsWith('proposal.')) {
      el = renderProposal(component);
    } else {
      el = renderJson(component);
    }
  } catch (e) {
    console.warn('saimcp-chat: component renderer failed', component.type, e);
    el = renderJson(component);
  }
  const wrap = h('div', { class: 'component', 'data-type': component.type });
  wrap.append(el);
  if (component.copyable && ctx.copyEnabled) {
    const text = typeof component.payload === 'string' ? component.payload : JSON.stringify(component.payload, null, 2);
    wrap.append(copyButton(text, ctx, 'Copy'));
  }
  return wrap;
}

// ─── DOM helpers ────────────────────────────────────────────────────────────────────────────────────

/** Creates an element; attributes go through setAttribute, children are appended (strings as text nodes). */
export function h(tag, attrs = {}, ...children) {
  const el = document.createElement(tag);
  for (const [k, v] of Object.entries(attrs)) {
    if (v === false || v === null || v === undefined) continue;
    if (k.startsWith('on') && typeof v === 'function') el.addEventListener(k.slice(2), v);
    else el.setAttribute(k, v === true ? '' : String(v));
  }
  for (const c of children.flat()) {
    if (c === null || c === undefined || c === false) continue;
    el.append(c instanceof Node ? c : document.createTextNode(String(c)));
  }
  return el;
}

/** A copy button that flips to "Copied" for a moment. */
export function copyButton(text, ctx, label = 'Copy') {
  const btn = h('button', { type: 'button', class: 'copy', 'aria-label': label }, label);
  btn.addEventListener('click', async () => {
    const ok = await ctx.copy(typeof text === 'function' ? text() : text);
    btn.textContent = ok ? 'Copied' : 'Copy failed';
    setTimeout(() => { btn.textContent = label; }, 1500);
  });
  return btn;
}

// ─── choice ─────────────────────────────────────────────────────────────────────────────────────────

renderers.set('choice', (component, ctx) => {
  const p = component.payload || {};
  const options = Array.isArray(p.options) ? p.options : [];
  const multiple = Boolean(p.multiple);
  const allowOther = Boolean(p.allowOther);
  const instant = !multiple && !allowOther; // one click answers
  const selected = new Set();
  const qid = `q-${Math.random().toString(36).slice(2)}`;

  const root = h('div', { class: 'choice', role: 'group', 'aria-labelledby': qid });
  root.append(h('p', { class: 'choice-question', id: qid }, p.question || 'Choose an option'));
  const list = h('div', { class: 'choice-options', role: multiple ? 'group' : 'radiogroup' });
  const buttons = options.map((o) => {
    const btn = h('button', {
      type: 'button', class: 'choice-option', role: multiple ? 'checkbox' : 'radio', 'aria-checked': 'false',
      'data-value': o.value,
    }, h('span', { class: 'choice-mark', 'aria-hidden': 'true' }), h('span', { class: 'choice-label' }, o.label),
    o.description ? h('span', { class: 'choice-desc' }, o.description) : null);
    btn.addEventListener('click', () => {
      if (root.dataset.state !== 'open') return;
      if (instant) {
        selected.clear();
        selected.add(o.value);
        sync();
        submit();
        return;
      }
      if (!multiple) selected.clear();
      if (selected.has(o.value)) selected.delete(o.value);
      else selected.add(o.value);
      sync();
    });
    list.append(btn);
    return btn;
  });
  root.append(list);

  let other = null;
  if (allowOther) {
    other = h('input', { type: 'text', class: 'choice-other', maxlength: 1000, placeholder: 'Or type your own answer',
      'aria-label': 'Your own answer' });
    other.addEventListener('input', () => {
      if (!multiple && other.value.trim()) selected.clear();
      sync();
    });
    other.addEventListener('keydown', (e) => {
      if (e.key === 'Enter') {
        e.preventDefault();
        if (!send.disabled) submit();
      }
    });
    root.append(other);
  }
  const send = h('button', { type: 'button', class: 'choice-send primary' }, 'Send');
  const status = h('p', { class: 'choice-status', role: 'status' });
  if (!instant) {
    send.addEventListener('click', submit);
    root.append(h('div', { class: 'choice-actions' }, send));
  }
  root.append(status);

  function sync() {
    for (const b of buttons) {
      const on = selected.has(b.dataset.value);
      b.setAttribute('aria-checked', String(on));
      b.classList.toggle('selected', on);
    }
    send.disabled = selected.size === 0 && !(other && other.value.trim());
  }

  function markAnswered(answer, note) {
    root.dataset.state = 'answered';
    const values = new Set(answer?.values || []);
    for (const b of buttons) {
      const on = values.has(b.dataset.value);
      b.classList.toggle('selected', on);
      b.setAttribute('aria-checked', String(on));
      b.disabled = true;
    }
    if (other) {
      other.disabled = true;
      if (answer?.other) other.value = answer.other;
    }
    send.disabled = true;
    status.textContent = note || 'Answered';
    status.className = 'choice-status answered';
  }

  async function submit() {
    if (root.dataset.state !== 'open') return;
    const body = { values: [...selected], other: other && other.value.trim() ? other.value.trim() : undefined };
    root.dataset.state = 'sending';
    buttons.forEach((b) => { b.disabled = true; });
    send.disabled = true;
    status.textContent = 'Sending…';
    status.className = 'choice-status';
    try {
      const answer = await ctx.answer(component, body);
      markAnswered(answer);
    } catch (e) {
      if (e && e.status === 409) {
        markAnswered(null, 'Already answered');
        return;
      }
      root.dataset.state = 'open';
      buttons.forEach((b) => { b.disabled = false; });
      sync();
      status.textContent = (e && e.problem && (e.problem.detail || e.problem.title)) || 'Your answer could not be sent.';
      status.className = 'choice-status error';
    }
  }

  root.dataset.state = 'open';
  sync();
  if (component.answer) {
    markAnswered(component.answer);
  } else if (!ctx.interactive) {
    markAnswered(null, 'Not answered');
  }
  return root;
});

// ─── structured-response (display tree) ─────────────────────────────────────────────────────────────

renderers.set('structured-response', (component) => renderDisplay(component.payload, { skipText: true }));

/**
 * Renders a display tree ({version, blocks}) as fields and tables. Text blocks are skipped when the answer's Markdown
 * already shows the prose.
 */
export function renderDisplay(tree, { skipText = false } = {}) {
  const root = h('div', { class: 'display' });
  const blocks = Array.isArray(tree?.blocks) ? tree.blocks : [];
  for (const b of blocks) {
    const el = renderBlock(b, skipText);
    if (el) root.append(el);
  }
  return root;
}

function renderBlock(b, skipText) {
  switch (b?.type) {
    case 'text':
      return skipText ? null : h('div', { class: 'display-text' }, b.title ? h('h4', {}, b.title) : null,
        h('p', {}, b.text || ''));
    case 'fields': {
      const dl = h('dl', { class: 'display-fields' });
      for (const item of b.items || []) {
        dl.append(h('dt', {}, item.label), h('dd', {}, formatValue(item.value, item.format)));
      }
      return h('div', { class: 'display-block' }, b.title ? h('h4', {}, b.title) : null, dl);
    }
    case 'table': {
      const cols = b.columns || [];
      const table = h('table', {},
        h('thead', {}, h('tr', {}, cols.map((c) => h('th', { 'data-format': c.format }, c.label)))),
        h('tbody', {}, (b.rows || []).map((r) => h('tr', {}, cols.map((c, i) => h('td', { 'data-format': c.format },
          formatValue(r[i], c.format)))))));
      const note = b.truncated ? h('p', { class: 'display-note' }, `Showing ${(b.rows || []).length} of ${b.totalRows}`)
        : null;
      return h('div', { class: 'display-block' }, b.title ? h('h4', {}, b.title) : null,
        h('div', { class: 'table-wrap' }, table), note);
    }
    case 'section': {
      const s = h('section', { class: 'display-section' }, h('h4', {}, b.title || ''));
      for (const child of b.blocks || []) {
        const el = renderBlock(child, skipText);
        if (el) s.append(el);
      }
      return s;
    }
    default:
      return null;
  }
}

/** Formats a display value by its format hint. */
export function formatValue(value, format) {
  if (value === null || value === undefined) return '—';
  try {
    if (format === 'number' && typeof value === 'number') return value.toLocaleString();
    if (format === 'boolean') return value === true ? 'Yes' : value === false ? 'No' : String(value);
    if (format === 'date' && /^\d{4}-\d{2}-\d{2}$/.test(value)) {
      return new Date(`${value}T00:00:00`).toLocaleDateString();
    }
    if (format === 'datetime' && !Number.isNaN(Date.parse(value))) return new Date(value).toLocaleString();
  } catch {
    // fall through
  }
  return String(value);
}

// ─── proposals and fallback ─────────────────────────────────────────────────────────────────────────

function renderProposal(component) {
  const p = component.payload || {};
  const title = component.type === 'proposal.created' ? 'Change proposed – review required'
    : component.type === 'proposal.applied' ? 'Change applied' : `Proposal ${p.state || 'updated'}`;
  const card = h('div', { class: 'proposal' }, h('strong', {}, title), p.summary ? h('p', {}, p.summary) : null);
  if (p.reviewUrl && /^(https?:)?\//.test(p.reviewUrl)) {
    card.append(h('a', { href: p.reviewUrl, target: '_blank', rel: 'noopener noreferrer' }, 'Review the change'));
  }
  return card;
}

function renderJson(component) {
  return h('details', { class: 'json-view' }, h('summary', {}, component.type),
    h('pre', {}, h('code', {}, JSON.stringify(component.payload, null, 2))));
}
