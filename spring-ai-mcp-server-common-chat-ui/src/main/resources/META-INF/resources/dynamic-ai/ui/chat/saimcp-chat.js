// <saimcp-chat> — embeddable AI chat window for springAIMcpServerCommon agents (F-53, LLD-13).
//
//   <script type="module" src="/dynamic-ai/ui/chat/saimcp-chat.js"></script>
//   <saimcp-chat agent="order-helper"></saimcp-chat>
//
// Attributes
//   agent          agent slug (required)
//   base-url       origin/prefix of the backend ('' = same origin)
//   mode           "floating" (default: launcher button + window) | "inline" (fills its container)
//   open           present = window open
//   width, height  initial window size in px (default 420 × 640); the user can resize, the size is remembered
//   position       "bottom-right" (default) | "bottom-left"
//   theme          "auto" (default) | "light" | "dark"
//   title          header title (default: the agent's display name)
//   placeholder    composer placeholder
//   greeting       text of the empty conversation
//   credentials    fetch credentials: "same-origin" (default) | "include" | "omit"
//   persist        "session" (default: conversation kept for this tab) | "local" | "none"
//   mermaid-src    URL of the Mermaid ESM build (default jsDelivr)
//   image-hosts    comma-separated hosts whose images load without a click
// Properties
//   tokenProvider  async () => token or "Bearer …" (added as Authorization on every call)
//   headers        object or async () => object of extra headers (e.g. a CSRF token)
// Methods: open(), close(), toggle(), send(text), newConversation()
// Events (bubbling, composed): saimcp-open, saimcp-close, saimcp-turn-start, saimcp-turn-end, saimcp-error,
//   saimcp-answer, saimcp-feedback, saimcp-conversation
// Static: SaimcpChat.registerComponent(type, (component, ctx) => HTMLElement)

import { STYLES } from './styles.js';
import { ChatApi, ChatApiError } from './client.js';
import { renderMarkdown, escapeHtml } from './markdown.js';
import { applyEvent, newTurn, restoreHistory, stepsSummary } from './turn.js';
import { copyButton, h, registerComponent, renderComponent, renderDisplay } from './components.js';
import { renderMermaidBlocks } from './mermaid.js';

const ICON = {
  chat: '<svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><path d="M21 12a8 8 0 0 1-11.6 7.1L4 20l1-4.6A8 8 0 1 1 21 12z"/></svg>',
  close: '<svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round"><path d="M6 6l12 12M18 6L6 18"/></svg>',
  max: '<svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><rect x="4" y="4" width="16" height="16" rx="2"/></svg>',
  restore: '<svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><rect x="8" y="4" width="12" height="12" rx="2"/><path d="M4 8v10a2 2 0 0 0 2 2h10"/></svg>',
  plus: '<svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round"><path d="M12 5v14M5 12h14"/></svg>',
  send: '<svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2.2" stroke-linecap="round" stroke-linejoin="round"><path d="M5 12h14M13 6l6 6-6 6"/></svg>',
  stop: '<svg viewBox="0 0 24 24" fill="currentColor"><rect x="6" y="6" width="12" height="12" rx="2"/></svg>',
  up: '<svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><path d="M7 10v11H4V10h3zm0 0l4-7a2 2 0 0 1 3 2l-1 5h6a2 2 0 0 1 2 2.3l-1.4 7A2 2 0 0 1 17.6 21H7"/></svg>',
  down: '<svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><path d="M17 14V3h3v11h-3zm0 0l-4 7a2 2 0 0 1-3-2l1-5H5a2 2 0 0 1-2-2.3l1.4-7A2 2 0 0 1 6.4 3H17"/></svg>',
  copy: '<svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><rect x="9" y="9" width="12" height="12" rx="2"/><path d="M5 15V5a2 2 0 0 1 2-2h10"/></svg>',
  check: '<svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="3" stroke-linecap="round" stroke-linejoin="round"><path d="M5 13l4 4L19 7"/></svg>',
  fail: '<svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="3" stroke-linecap="round"><path d="M6 6l12 12M18 6L6 18"/></svg>',
};

const REASONS = [
  ['inaccurate', 'Inaccurate'], ['incomplete', 'Incomplete'], ['off_topic', 'Not what I asked'],
  ['unsafe', 'Unsafe or inappropriate'], ['other', 'Other'],
];

const DEFAULT_SIZE = { width: 420, height: 640 };
const MIN_SIZE = { width: 300, height: 360 };

function icon(name) {
  const span = document.createElement('span');
  span.innerHTML = ICON[name]; // static, trusted markup
  return span.firstElementChild;
}

function iconButton(name, label, onClick) {
  const b = h('button', { type: 'button', class: 'icon-btn', 'aria-label': label, title: label }, icon(name));
  if (onClick) b.addEventListener('click', onClick);
  return b;
}

function storage(kind) {
  try {
    return kind === 'local' ? globalThis.localStorage : kind === 'none' ? null : globalThis.sessionStorage;
  } catch {
    return null;
  }
}

export class SaimcpChat extends HTMLElement {
  static get observedAttributes() {
    return ['open', 'title', 'width', 'height'];
  }

  /** Registers a renderer for a `ui.component` type, for every chat on the page. */
  static registerComponent(type, render) {
    registerComponent(type, render);
  }

  constructor() {
    super();
    this.attachShadow({ mode: 'open' });
    this.tokenProvider = null;
    this.headers = null;
    this.entries = [];
    this.config = null;
    this.conversationId = null;
    this.busy = false;
    this.abort = null;
    this.ready = null;
    this.built = false;
    this.stick = true;
    this.pendingRender = new Set();
    this.frame = 0;
  }

  // ─── lifecycle ──────────────────────────────────────────────────────────────────────────────────

  connectedCallback() {
    if (!this.built) {
      this.build();
      this.built = true;
    }
    if (this.inline || this.hasAttribute('open')) {
      this.showWindow(false);
    }
  }

  disconnectedCallback() {
    this.abort?.abort();
  }

  attributeChangedCallback(name, oldValue, value) {
    if (!this.built || oldValue === value) return;
    if (name === 'open') {
      if (value !== null) this.showWindow(true);
      else this.hideWindow(true);
    } else if (name === 'title') {
      this.titleEl.textContent = value || this.config?.displayName || 'Assistant';
    } else if (name === 'width' || name === 'height') {
      this.applySize(this.initialSize());
    }
  }

  get inline() {
    return this.getAttribute('mode') === 'inline';
  }

  get agent() {
    return this.getAttribute('agent') || '';
  }

  get api() {
    if (!this._api || this._apiAgent !== this.agent) {
      this._apiAgent = this.agent;
      this._api = new ChatApi({
        baseUrl: this.getAttribute('base-url') || '',
        agent: this.agent,
        credentials: this.getAttribute('credentials') || 'same-origin',
        headers: async () => {
          const out = {};
          const extra = typeof this.headers === 'function' ? await this.headers() : this.headers;
          if (extra) Object.assign(out, extra);
          if (typeof this.tokenProvider === 'function') {
            const t = await this.tokenProvider();
            if (t) out.Authorization = /\s/.test(t) ? t : `Bearer ${t}`;
          }
          return out;
        },
      });
    }
    return this._api;
  }

  // ─── public API ─────────────────────────────────────────────────────────────────────────────────

  /** Opens the window. */
  open() {
    this.setAttribute('open', '');
  }

  /** Closes the window (floating mode). */
  close() {
    this.removeAttribute('open');
  }

  /** Toggles the window. */
  toggle() {
    if (this.hasAttribute('open')) this.close();
    else this.open();
  }

  /** Starts a new conversation (the previous one stays on the server). */
  newConversation() {
    this.abort?.abort();
    this.entries = [];
    this.conversationId = null;
    this.store()?.removeItem(this.storeKey('conversation'));
    this.messagesEl.replaceChildren(this.emptyState());
    this.emit('saimcp-conversation', { conversationId: null });
    this.input.focus();
  }

  /**
   * Sends a message as the user.
   *
   * @param {string} text the message
   */
  async send(text) {
    const message = String(text ?? '').trim();
    if (!message || this.busy) return;
    await this.ensureReady();
    const max = this.config?.maxMessageChars || 32000;
    if (message.length > max) {
      this.showBanner(`Your message is too long (${message.length} / ${max} characters).`);
      return;
    }
    this.clearBanner();
    this.addEntry({ role: 'user', text: message });
    const entry = this.addEntry({ role: 'assistant', turn: newTurn(), feedback: null });
    this.setBusy(true);
    this.abort = new AbortController();
    const body = { message, clientRequestId: crypto.randomUUID?.() ?? String(Date.now()) };
    if (this.conversationId) body.conversationId = this.conversationId;
    this.emit('saimcp-turn-start', { message });
    try {
      await this.api.stream(body, {
        signal: this.abort.signal,
        onEvent: (ev) => {
          applyEvent(entry.turn, ev);
          if (ev.type === 'turn.start') this.setConversation(ev.conversationId);
          this.scheduleRender(entry);
        },
      });
      if (entry.turn.status === 'streaming') {
        entry.turn.status = 'error';
        entry.turn.error = { code: 'interrupted', title: 'The answer was interrupted.', retryable: true };
      }
    } catch (e) {
      if (e?.name === 'AbortError') {
        entry.turn.status = 'stopped';
      } else {
        entry.turn.status = 'error';
        entry.turn.error = errorOf(e);
        this.emit('saimcp-error', entry.turn.error);
      }
    } finally {
      this.abort = null;
      this.setBusy(false);
      entry.retry = message;
      this.pendingRender.delete(entry); // a frame queued by the last events must not re-render the final answer
      this.renderAssistant(entry, true);
      this.emit('saimcp-turn-end', { turnId: entry.turn.turnId, status: entry.turn.status, text: entry.turn.text });
      this.input.focus();
    }
  }

  // ─── building ───────────────────────────────────────────────────────────────────────────────────

  build() {
    const style = document.createElement('style');
    style.textContent = STYLES;
    this.shadowRoot.append(style);

    if (!this.inline) {
      this.launcher = h('button', { type: 'button', class: 'launcher', 'aria-label': 'Open assistant',
        'aria-expanded': 'false' }, icon('chat'));
      this.launcher.addEventListener('click', () => this.toggle());
      this.shadowRoot.append(this.launcher);
    }

    this.titleEl = h('span', { class: 'title' }, this.getAttribute('title') || 'Assistant');
    const header = h('header', { class: 'bar' }, this.titleEl,
      iconButton('plus', 'New conversation', () => this.newConversation()));
    if (!this.inline) {
      this.maxBtn = iconButton('max', 'Maximize', () => this.toggleMaximize());
      header.append(this.maxBtn, iconButton('close', 'Close', () => this.close()));
    }

    this.bannerEl = h('div', { class: 'banner', hidden: true });
    this.messagesEl = h('div', { class: 'messages', role: 'log', 'aria-live': 'polite', 'aria-relevant': 'additions' });
    this.messagesEl.append(this.emptyState());
    this.messagesEl.addEventListener('scroll', () => {
      const el = this.messagesEl;
      this.stick = el.scrollHeight - el.scrollTop - el.clientHeight < 48;
      this.jumpBtn.hidden = this.stick;
    });
    this.messagesEl.addEventListener('click', (e) => this.onMessagesClick(e));
    this.jumpBtn = h('button', { type: 'button', class: 'jump', hidden: true }, 'Jump to latest ↓');
    this.jumpBtn.addEventListener('click', () => this.scrollToEnd(true));

    this.input = h('textarea', { rows: 1, 'aria-label': 'Message',
      placeholder: this.getAttribute('placeholder') || 'Ask a question…' });
    this.input.addEventListener('input', () => this.onInput());
    this.input.addEventListener('keydown', (e) => {
      if (e.key === 'Enter' && !e.shiftKey && !e.isComposing) {
        e.preventDefault();
        this.submitComposer();
      }
    });
    this.sendBtn = h('button', { type: 'button', class: 'send', 'aria-label': 'Send' }, icon('send'));
    this.sendBtn.addEventListener('click', () => (this.busy ? this.abort?.abort() : this.submitComposer()));
    this.counter = h('div', { class: 'counter', hidden: true });
    const composer = h('footer', { class: 'composer' }, h('div', { class: 'composer-row' }, this.input, this.sendBtn),
      this.counter);

    this.windowEl = h('section', { class: 'window', role: this.inline ? 'region' : 'dialog',
      'aria-label': this.getAttribute('title') || 'Assistant', hidden: true });
    if (!this.inline) {
      for (const dir of ['n', 'w', 'e', 'nw', 'ne']) {
        const handle = h('div', { class: `resize ${dir}`, 'aria-hidden': 'true' });
        handle.addEventListener('pointerdown', (e) => this.startResize(e, dir));
        this.windowEl.append(handle);
      }
      this.windowEl.addEventListener('keydown', (e) => {
        if (e.key === 'Escape') this.close();
      });
      this.applySize(this.initialSize());
    }
    this.windowEl.append(header, this.bannerEl, this.messagesEl, this.jumpBtn, composer);
    this.shadowRoot.append(this.windowEl);
    this.onInput();
  }

  emptyState() {
    const name = this.config?.displayName || this.getAttribute('title') || 'the assistant';
    return h('div', { class: 'empty' }, this.getAttribute('greeting') || `Ask ${name} a question to get started.`);
  }

  // ─── window, size, resize ───────────────────────────────────────────────────────────────────────

  showWindow(notify) {
    this.windowEl.hidden = false;
    if (this.launcher) {
      this.launcher.setAttribute('aria-expanded', 'true');
      this.launcher.replaceChildren(icon('close'));
      this.launcher.setAttribute('aria-label', 'Close assistant');
    }
    this.ensureReady();
    requestAnimationFrame(() => this.input.focus());
    if (notify) this.emit('saimcp-open', {});
  }

  hideWindow(notify) {
    if (this.inline) return;
    this.windowEl.hidden = true;
    if (this.launcher) {
      this.launcher.setAttribute('aria-expanded', 'false');
      this.launcher.replaceChildren(icon('chat'));
      this.launcher.setAttribute('aria-label', 'Open assistant');
      this.launcher.focus();
    }
    if (notify) this.emit('saimcp-close', {});
  }

  initialSize() {
    let saved = null;
    try {
      saved = JSON.parse(globalThis.localStorage?.getItem(this.storeKey('size')) || 'null');
    } catch {
      saved = null;
    }
    return {
      width: saved?.width || parseInt(this.getAttribute('width'), 10) || DEFAULT_SIZE.width,
      height: saved?.height || parseInt(this.getAttribute('height'), 10) || DEFAULT_SIZE.height,
    };
  }

  clampSize({ width, height }) {
    const maxW = Math.max(MIN_SIZE.width, (globalThis.innerWidth || 1280) - 48);
    const maxH = Math.max(MIN_SIZE.height, (globalThis.innerHeight || 800) - 116);
    return {
      width: Math.round(Math.min(maxW, Math.max(MIN_SIZE.width, width))),
      height: Math.round(Math.min(maxH, Math.max(MIN_SIZE.height, height))),
    };
  }

  applySize(size) {
    const s = this.clampSize(size);
    this.size = s;
    this.windowEl.style.width = `${s.width}px`;
    this.windowEl.style.height = `${s.height}px`;
  }

  startResize(e, dir) {
    if (this.windowEl.classList.contains('maximized')) return;
    e.preventDefault();
    const handle = e.currentTarget;
    handle.setPointerCapture(e.pointerId);
    const start = { x: e.clientX, y: e.clientY, ...this.size };
    const move = (ev) => {
      let { width, height } = start;
      if (dir.includes('n')) height = start.height + (start.y - ev.clientY);
      if (dir.includes('w')) width = start.width + (start.x - ev.clientX);
      if (dir.includes('e')) width = start.width + (ev.clientX - start.x);
      this.applySize({ width, height });
    };
    const up = () => {
      handle.removeEventListener('pointermove', move);
      handle.removeEventListener('pointerup', up);
      handle.removeEventListener('pointercancel', up);
      try {
        globalThis.localStorage?.setItem(this.storeKey('size'), JSON.stringify(this.size));
      } catch {
        // storage unavailable: the size is not remembered
      }
    };
    handle.addEventListener('pointermove', move);
    handle.addEventListener('pointerup', up);
    handle.addEventListener('pointercancel', up);
  }

  toggleMaximize() {
    const on = this.windowEl.classList.toggle('maximized');
    this.maxBtn.replaceChildren(icon(on ? 'restore' : 'max'));
    this.maxBtn.setAttribute('aria-label', on ? 'Restore size' : 'Maximize');
    this.maxBtn.title = on ? 'Restore size' : 'Maximize';
  }

  // ─── config and history ─────────────────────────────────────────────────────────────────────────

  ensureReady() {
    if (!this.ready) {
      this.ready = this.init().catch((e) => {
        this.ready = null; // retry on the next open/send
        const err = errorOf(e);
        this.showBanner(`The assistant is not available: ${err.title}`, () => this.ensureReady());
      });
    }
    return this.ready;
  }

  async init() {
    if (!this.agent) throw new Error('the agent attribute is required');
    this.config = await this.api.config();
    if (!this.getAttribute('title') && this.config.displayName) {
      this.titleEl.textContent = this.config.displayName;
      this.windowEl.setAttribute('aria-label', this.config.displayName);
    }
    if (!this.entries.length) this.messagesEl.replaceChildren(this.emptyState());
    this.clearBanner();
    this.onInput();
    const stored = this.store()?.getItem(this.storeKey('conversation'));
    if (stored && !this.conversationId && !this.entries.length) {
      await this.restore(stored);
    }
  }

  async restore(conversationId) {
    this.conversationId = conversationId;
    let messages = [];
    try {
      messages = await this.api.messages(conversationId);
    } catch (e) {
      if (!(e instanceof ChatApiError && (e.status === 404 || e.status === 403))) throw e;
      // transcripts are not recorded (or the conversation is gone): only the UI state can be restored
    }
    let state = { components: [], feedback: [] };
    try {
      state = await this.api.uiState(conversationId);
    } catch {
      // no state kept
    }
    const entries = restoreHistory(messages, state);
    if (!entries.length) return;
    this.messagesEl.replaceChildren();
    for (const e of entries) {
      const entry = this.addEntry(e);
      if (entry.role === 'assistant') this.renderAssistant(entry, true);
    }
    this.scrollToEnd(false);
  }

  setConversation(id) {
    if (!id || id === this.conversationId) return;
    this.conversationId = id;
    this.store()?.setItem(this.storeKey('conversation'), id);
    this.emit('saimcp-conversation', { conversationId: id });
  }

  store() {
    return storage(this.getAttribute('persist') || 'session');
  }

  storeKey(what) {
    return `saimcp-chat:${this.getAttribute('base-url') || ''}:${this.agent}:${what}`;
  }

  // ─── composer ───────────────────────────────────────────────────────────────────────────────────

  onInput() {
    const el = this.input;
    el.style.height = 'auto';
    el.style.height = `${Math.min(160, el.scrollHeight)}px`;
    const max = this.config?.maxMessageChars || 32000;
    const len = el.value.length;
    this.counter.hidden = len < max * 0.8;
    this.counter.textContent = `${len} / ${max}`;
    this.counter.classList.toggle('over', len > max);
    if (!this.busy) this.sendBtn.disabled = !el.value.trim() || len > max;
  }

  submitComposer() {
    const text = this.input.value;
    if (!text.trim() || this.busy) return;
    this.input.value = '';
    this.onInput();
    this.send(text);
  }

  setBusy(busy) {
    this.busy = busy;
    this.sendBtn.replaceChildren(icon(busy ? 'stop' : 'send'));
    this.sendBtn.setAttribute('aria-label', busy ? 'Stop' : 'Send');
    this.sendBtn.disabled = busy ? false : !this.input.value.trim();
    this.messagesEl.setAttribute('aria-busy', String(busy));
  }

  // ─── messages ───────────────────────────────────────────────────────────────────────────────────

  addEntry(entry) {
    if (!this.entries.length) this.messagesEl.replaceChildren();
    this.entries.push(entry);
    if (entry.role === 'user') {
      entry.el = h('div', { class: 'msg user' }, h('div', { class: 'bubble' }, entry.text));
    } else {
      entry.el = h('div', { class: 'msg assistant' });
      entry.parts = {
        steps: null, body: h('div', { class: 'body' }), components: h('div', { class: 'components' }),
        display: null, error: null, actions: null, feedbackForm: null,
      };
      entry.rendered = { components: 0, stepsKey: '', text: null };
      entry.el.append(entry.parts.body, entry.parts.components);
    }
    this.messagesEl.append(entry.el);
    this.scrollToEnd(false);
    return entry;
  }

  scheduleRender(entry) {
    this.pendingRender.add(entry);
    if (!this.frame) {
      this.frame = requestAnimationFrame(() => {
        this.frame = 0;
        const entries = [...this.pendingRender];
        this.pendingRender.clear();
        entries.forEach((e) => this.renderAssistant(e, false));
      });
    }
  }

  uiOf(turn) {
    return turn.ui || this.config?.ui || { steps: false, feedback: false, copy: false, choices: false };
  }

  renderAssistant(entry, final) {
    const { turn, parts } = entry;
    const ui = this.uiOf(turn);
    const streaming = turn.status === 'streaming';

    // steps (Claude-style collapsible details)
    if (ui.steps && (turn.steps.length || streaming)) {
      const key = JSON.stringify(turn.steps) + turn.status;
      if (key !== entry.rendered.stepsKey) {
        entry.rendered.stepsKey = key;
        const wasOpen = parts.steps?.open ?? false;
        const details = this.renderSteps(turn, ui);
        details.open = wasOpen;
        if (parts.steps) parts.steps.replaceWith(details);
        else entry.el.prepend(details);
        parts.steps = details;
      }
    } else if (parts.steps && !turn.steps.length && !streaming) {
      parts.steps.remove();
      parts.steps = null;
    }

    // body: re-rendered only when the text changed, so rendered diagrams and loaded images survive later updates
    if (entry.rendered.text !== turn.text) {
      entry.rendered.text = turn.text;
      const imageHosts = (this.getAttribute('image-hosts') || '').split(',').map((s) => s.trim()).filter(Boolean);
      parts.body.innerHTML = renderMarkdown(turn.text, { imageHosts }); // escaped by construction (markdown.js)
    }
    parts.body.classList.toggle('streaming', streaming);

    // components, appended as they arrive
    while (entry.rendered.components < turn.components.length) {
      const component = turn.components[entry.rendered.components++];
      parts.components.append(renderComponent(component, this.componentContext(entry, ui)));
    }

    if (!final) {
      this.scrollToEnd(false);
      return;
    }

    // structured display: fields and tables the backend chose to show (the prose is already in the body)
    if (turn.display && !parts.display) {
      const display = renderDisplay(turn.display, { skipText: true });
      if (display.childElementCount) {
        parts.display = display;
        parts.body.after(display);
      }
    }

    // diagrams once the answer is complete
    renderMermaidBlocks(parts.body, { src: this.getAttribute('mermaid-src') || undefined, dark: this.isDark() })
      .then(() => this.decorateMermaid(parts.body))
      .catch(() => {});

    parts.error?.remove();
    parts.error = null;
    if (turn.status === 'error' || turn.status === 'stopped') {
      const text = turn.status === 'stopped' ? 'Stopped.' : (turn.error?.title || 'Something went wrong.');
      const box = h('div', { class: 'error-box', role: turn.status === 'error' ? 'alert' : 'status' }, h('span', {}, text));
      if (entry.retry && turn.status === 'error') {
        const retry = h('button', { type: 'button', class: 'secondary' }, 'Retry');
        retry.addEventListener('click', () => {
          box.remove();
          this.send(entry.retry);
        });
        box.append(retry);
      }
      parts.error = box;
      entry.el.append(box);
    }

    parts.actions?.remove();
    parts.actions = this.renderActions(entry, ui);
    if (parts.actions) entry.el.append(parts.actions);
    this.scrollToEnd(false);
  }

  renderSteps(turn, ui) {
    const running = turn.steps.some((s) => s.status === 'running') || turn.status === 'streaming';
    const summary = h('summary', {}, running ? h('span', { class: 'spinner', 'aria-hidden': 'true' }) : null,
      h('span', {}, stepsSummary(turn)));
    const list = h('ol', {});
    for (const s of turn.steps) {
      const statusIcon = s.status === 'running'
        ? h('span', { class: 'spinner', role: 'img', 'aria-label': 'running' })
        : s.status === 'error' ? h('span', { class: 'fail', role: 'img', 'aria-label': 'failed' }, icon('fail'))
          : h('span', { class: 'ok', role: 'img', 'aria-label': 'done' }, icon('check'));
      const body = h('div', {}, h('div', { class: 'title' }, s.kind === 'tool' ? `${s.title}` : s.title));
      if (s.kind === 'tool' && s.args) body.append(h('pre', {}, s.args));
      if (s.detail) body.append(h('div', { class: 'detail' }, s.detail));
      if (ui.copy && (s.args || s.detail)) {
        const text = [s.kind === 'tool' ? `${s.tool}(${s.args || ''})` : s.title, s.detail].filter(Boolean).join('\n');
        body.append(h('div', { class: 'step-copy' }, copyButton(text, this.copyContext(), 'Copy')));
      }
      list.append(h('li', { class: 'step', 'data-status': s.status }, h('span', { class: 'icon' }, statusIcon), body));
    }
    return h('details', { class: 'steps' }, summary, list);
  }

  renderActions(entry, ui) {
    const { turn } = entry;
    if (turn.status === 'streaming') return null;
    const bar = h('div', { class: 'actions' });
    if (ui.copy && turn.text) {
      const b = iconButton('copy', 'Copy answer', async () => {
        const ok = await this.copyText(turn.text);
        b.title = ok ? 'Copied' : 'Copy failed';
        b.setAttribute('aria-label', b.title);
        setTimeout(() => { b.title = 'Copy answer'; b.setAttribute('aria-label', 'Copy answer'); }, 1500);
      });
      bar.append(b);
    }
    if (ui.feedback && turn.turnId && this.conversationId && turn.status === 'done') {
      const up = iconButton('up', 'Good answer');
      const down = iconButton('down', 'Bad answer');
      const sync = () => {
        up.setAttribute('aria-pressed', String(entry.feedback === 'up'));
        down.setAttribute('aria-pressed', String(entry.feedback === 'down'));
      };
      up.addEventListener('click', () => this.rate(entry, entry.feedback === 'up' ? null : 'up', null, sync));
      down.addEventListener('click', () => {
        if (entry.feedback === 'down') this.rate(entry, null, null, sync);
        else this.openFeedbackForm(entry, sync);
      });
      sync();
      bar.append(up, down);
    }
    return bar.childElementCount ? bar : null;
  }

  openFeedbackForm(entry, sync) {
    entry.parts.feedbackForm?.remove();
    let reason = null;
    const chips = h('div', { class: 'chips', role: 'group', 'aria-label': 'What went wrong?' });
    for (const [code, label] of REASONS) {
      const chip = h('button', { type: 'button', class: 'chip', 'aria-pressed': 'false' }, label);
      chip.addEventListener('click', () => {
        reason = reason === code ? null : code;
        chips.querySelectorAll('.chip').forEach((c) => c.setAttribute('aria-pressed', String(c === chip && reason)));
      });
      chips.append(chip);
    }
    const comment = h('textarea', { rows: 2, maxlength: 2000, placeholder: 'Tell us more (optional)',
      'aria-label': 'Feedback comment' });
    const submit = h('button', { type: 'button', class: 'primary' }, 'Send feedback');
    const cancel = h('button', { type: 'button', class: 'secondary' }, 'Cancel');
    const form = h('div', { class: 'feedback-form' }, h('strong', {}, 'What could be better?'), chips, comment,
      h('div', { class: 'row' }, cancel, submit));
    cancel.addEventListener('click', () => form.remove());
    submit.addEventListener('click', async () => {
      submit.disabled = true;
      const ok = await this.rate(entry, 'down', { reason, comment: comment.value.trim() || undefined }, sync);
      if (ok) form.replaceWith(h('p', { class: 'thanks' }, 'Thanks for the feedback.'));
      else submit.disabled = false;
    });
    entry.parts.feedbackForm = form;
    entry.el.append(form);
    comment.focus();
    this.scrollToEnd(false);
  }

  async rate(entry, rating, extra, sync) {
    const previous = entry.feedback;
    entry.feedback = rating;
    sync();
    try {
      await this.api.feedback(this.conversationId, entry.turn.turnId,
        rating ? { rating, reason: extra?.reason || undefined, comment: extra?.comment } : null);
      this.emit('saimcp-feedback', { turnId: entry.turn.turnId, rating, reason: extra?.reason || null });
      return true;
    } catch (e) {
      entry.feedback = previous;
      sync();
      this.showBanner(`Feedback could not be saved: ${errorOf(e).title}`);
      return false;
    }
  }

  componentContext(entry, ui) {
    return {
      turnId: entry.turn.turnId,
      conversationId: this.conversationId,
      interactive: true,
      copyEnabled: Boolean(ui.copy),
      persistent: Boolean(this.config?.persistentState),
      copy: (text) => this.copyText(text),
      sendMessage: (text) => this.send(text),
      emit: (name, detail) => this.emit(name, detail),
      answer: (component, body) => this.answer(entry, component, body),
    };
  }

  copyContext() {
    return { copy: (text) => this.copyText(text) };
  }

  async answer(entry, component, body) {
    if (this.busy) {
      throw new ChatApiError(0, { title: 'Wait until the current answer is complete.' });
    }
    const p = component.payload || {};
    let answer;
    let message;
    if (this.config?.persistentState && this.conversationId && entry.turn.turnId && component.componentId) {
      const res = await this.api.answer(this.conversationId, entry.turn.turnId, component.componentId, body);
      answer = res.answer;
      message = res.message;
    } else {
      // nothing is kept server-side: answer through the conversation only
      const labels = (p.options || []).filter((o) => body.values.includes(o.value)).map((o) => o.label);
      answer = { values: body.values, labels, other: body.other };
      message = `My answer to "${p.question}": ${[...labels, body.other].filter(Boolean).join(', ')}`;
    }
    component.answer = answer;
    this.emit('saimcp-answer', { turnId: entry.turn.turnId, componentId: component.componentId, answer });
    queueMicrotask(() => this.send(message));
    return answer;
  }

  decorateMermaid(root) {
    for (const block of root.querySelectorAll('.mermaid-block[data-rendered="true"]:not([data-tools])')) {
      block.setAttribute('data-tools', '');
      const toggle = h('button', { type: 'button', class: 'copy' }, 'Source');
      toggle.addEventListener('click', () => {
        const on = block.classList.toggle('show-source');
        toggle.textContent = on ? 'Diagram' : 'Source';
      });
      const src = () => block.querySelector('.mermaid-source code')?.textContent || '';
      block.append(h('div', { class: 'mermaid-tools' }, toggle, copyButton(src, this.copyContext(), 'Copy')));
    }
  }

  onMessagesClick(e) {
    const copyCode = e.target.closest?.('.copy-code');
    if (copyCode) {
      const code = copyCode.closest('.code-block')?.querySelector('pre code')?.textContent || '';
      this.copyText(code).then((ok) => {
        copyCode.textContent = ok ? 'Copied' : 'Copy failed';
        setTimeout(() => { copyCode.textContent = 'Copy'; }, 1500);
      });
      return;
    }
    const img = e.target.closest?.('.image-placeholder');
    if (img) {
      const el = document.createElement('img');
      el.src = img.dataset.src; // http(s) only, checked by markdown.js
      el.alt = img.dataset.alt || '';
      el.referrerPolicy = 'no-referrer';
      el.loading = 'lazy';
      img.replaceWith(el);
    }
  }

  scrollToEnd(force) {
    if (!force && !this.stick) {
      this.jumpBtn.hidden = false;
      return;
    }
    const el = this.messagesEl;
    el.scrollTop = el.scrollHeight;
    this.stick = true;
    this.jumpBtn.hidden = true;
  }

  // ─── helpers ────────────────────────────────────────────────────────────────────────────────────

  async copyText(text) {
    try {
      await navigator.clipboard.writeText(text);
      return true;
    } catch {
      const ta = h('textarea', { class: 'sr-only', 'aria-hidden': 'true' });
      ta.value = text;
      this.shadowRoot.append(ta);
      ta.select();
      let ok = false;
      try {
        ok = document.execCommand('copy');
      } catch {
        ok = false;
      }
      ta.remove();
      return ok;
    }
  }

  isDark() {
    const t = this.getAttribute('theme');
    if (t === 'dark') return true;
    if (t === 'light') return false;
    return Boolean(globalThis.matchMedia?.('(prefers-color-scheme: dark)').matches);
  }

  showBanner(text, retry) {
    const box = h('div', { class: 'error-box', role: 'alert' }, h('span', {}, text));
    if (retry) {
      const b = h('button', { type: 'button', class: 'secondary' }, 'Retry');
      b.addEventListener('click', () => {
        this.clearBanner();
        retry();
      });
      box.append(b);
    }
    this.bannerEl.replaceChildren(box);
    this.bannerEl.hidden = false;
  }

  clearBanner() {
    this.bannerEl.replaceChildren();
    this.bannerEl.hidden = true;
  }

  emit(name, detail) {
    this.dispatchEvent(new CustomEvent(name, { detail, bubbles: true, composed: true }));
  }
}

/** A caller-safe {code, title, retryable} from any failure. */
function errorOf(e) {
  if (e instanceof ChatApiError) {
    const p = e.problem || {};
    const title = e.status === 401 ? 'Please sign in again.'
      : e.status === 403 ? 'You do not have access to this assistant.'
        : e.status === 429 ? (p.title || 'Too many requests. Please wait a moment.')
          : (p.title || `Request failed (${e.status}).`);
    return { code: p.code || `http-${e.status}`, title, retryable: e.status >= 500 || e.status === 429 };
  }
  return { code: 'network', title: 'The connection was lost.', retryable: true };
}

export { escapeHtml };

if (!customElements.get('saimcp-chat')) {
  customElements.define('saimcp-chat', SaimcpChat);
}
