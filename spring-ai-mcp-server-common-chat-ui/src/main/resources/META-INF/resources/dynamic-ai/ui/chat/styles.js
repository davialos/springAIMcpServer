// Styles of <saimcp-chat>, scoped to its shadow root. Hosts theme it with CSS custom properties on the element:
//   saimcp-chat { --saimcp-accent: #0b5cad; --saimcp-font: "Inter", sans-serif; --saimcp-z-index: 2000; }

export const STYLES = `
:host {
  --saimcp-accent: #2f6fde;
  --saimcp-accent-fg: #ffffff;
  --saimcp-bg: #ffffff;
  --saimcp-surface: #f5f6f8;
  --saimcp-fg: #1d2330;
  --saimcp-muted: #5f6878;
  --saimcp-border: #dfe3ea;
  --saimcp-user-bg: #e8effc;
  --saimcp-code-bg: #f3f4f7;
  --saimcp-danger: #c4302b;
  --saimcp-success: #1f7a4d;
  --saimcp-radius: 12px;
  --saimcp-font: system-ui, -apple-system, "Segoe UI", Roboto, sans-serif;
  --saimcp-mono: ui-monospace, SFMono-Regular, Menlo, Consolas, monospace;
  --saimcp-shadow: 0 12px 40px rgba(20, 30, 50, .22);
  --saimcp-z-index: 2147483000;
  --tok-keyword: #a626a4; --tok-string: #22863a; --tok-number: #b35900; --tok-comment: #8a919e;
  --tok-literal: #0b61a4; --tok-function: #4655c7; --tok-builtin: #6f42c1; --tok-type: #b0510c;
  --tok-property: #005cc5; --tok-operator: #4b5361; --tok-annotation: #8b5a00; --tok-tag: #22863a;
  --tok-variable: #b31d28;
  all: initial;
  display: contents;
  font-family: var(--saimcp-font);
  color: var(--saimcp-fg);
}
:host([theme="dark"]) { color-scheme: dark; }
@media (prefers-color-scheme: dark) {
  :host(:not([theme="light"])) {
    --saimcp-bg: #171b22; --saimcp-surface: #20252e; --saimcp-fg: #e6e9ef; --saimcp-muted: #9aa3b2;
    --saimcp-border: #2f3642; --saimcp-user-bg: #23324d; --saimcp-code-bg: #11151b; --saimcp-accent: #5b8ff0;
    --tok-keyword: #d38bd8; --tok-string: #8bcf7f; --tok-number: #f0a35e; --tok-comment: #7b8494;
    --tok-literal: #6fb3f2; --tok-function: #8fa1ff; --tok-builtin: #b99cf0; --tok-type: #f0b36e;
    --tok-property: #79b8ff; --tok-operator: #b7bdc8; --tok-annotation: #e2c26b; --tok-tag: #8bcf7f;
    --tok-variable: #f28b8b;
  }
}
:host([theme="dark"]) {
  --saimcp-bg: #171b22; --saimcp-surface: #20252e; --saimcp-fg: #e6e9ef; --saimcp-muted: #9aa3b2;
  --saimcp-border: #2f3642; --saimcp-user-bg: #23324d; --saimcp-code-bg: #11151b; --saimcp-accent: #5b8ff0;
  --tok-keyword: #d38bd8; --tok-string: #8bcf7f; --tok-number: #f0a35e; --tok-comment: #7b8494;
  --tok-literal: #6fb3f2; --tok-function: #8fa1ff; --tok-builtin: #b99cf0; --tok-type: #f0b36e;
  --tok-property: #79b8ff; --tok-operator: #b7bdc8; --tok-annotation: #e2c26b; --tok-tag: #8bcf7f;
  --tok-variable: #f28b8b;
}

* { box-sizing: border-box; }
button { font: inherit; color: inherit; }
.sr-only { position: absolute; width: 1px; height: 1px; overflow: hidden; clip: rect(0 0 0 0); white-space: nowrap; }

/* ── launcher and window ─────────────────────────────────────────────────────────────────── */
.launcher {
  position: fixed; bottom: 24px; right: 24px; z-index: var(--saimcp-z-index);
  width: 56px; height: 56px; border-radius: 50%; border: none; cursor: pointer;
  background: var(--saimcp-accent); color: var(--saimcp-accent-fg); box-shadow: var(--saimcp-shadow);
  display: grid; place-items: center;
}
.launcher svg { width: 26px; height: 26px; }
:host([position="bottom-left"]) .launcher { right: auto; left: 24px; }
.launcher:focus-visible, button:focus-visible, textarea:focus-visible, input:focus-visible {
  outline: 2px solid var(--saimcp-accent); outline-offset: 2px;
}

.window {
  position: fixed; bottom: 92px; right: 24px; z-index: var(--saimcp-z-index);
  display: flex; flex-direction: column; overflow: hidden;
  background: var(--saimcp-bg); color: var(--saimcp-fg); font-family: var(--saimcp-font); font-size: 14px;
  line-height: 1.5; border: 1px solid var(--saimcp-border); border-radius: var(--saimcp-radius);
  box-shadow: var(--saimcp-shadow); min-width: 300px; min-height: 360px;
}
:host([position="bottom-left"]) .window { right: auto; left: 24px; }
.window[hidden] { display: none; }
.window.maximized { inset: 16px !important; width: auto !important; height: auto !important; }
:host([mode="inline"]) .window {
  position: relative; inset: auto; width: 100%; height: 100%; min-width: 0; box-shadow: none; z-index: auto;
}
@media (max-width: 520px) {
  :host(:not([mode="inline"])) .window { inset: 0 !important; width: auto !important; height: auto !important;
    border-radius: 0; }
}

.resize { position: absolute; z-index: 2; }
.resize.n { top: -3px; left: 12px; right: 12px; height: 8px; cursor: ns-resize; }
.resize.w { left: -3px; top: 12px; bottom: 12px; width: 8px; cursor: ew-resize; }
.resize.e { right: -3px; top: 12px; bottom: 12px; width: 8px; cursor: ew-resize; display: none; }
.resize.nw { top: -3px; left: -3px; width: 16px; height: 16px; cursor: nwse-resize; }
.resize.ne { top: -3px; right: -3px; width: 16px; height: 16px; cursor: nesw-resize; display: none; }
:host([position="bottom-left"]) .resize.w, :host([position="bottom-left"]) .resize.nw { display: none; }
:host([position="bottom-left"]) .resize.e, :host([position="bottom-left"]) .resize.ne { display: block; }
:host([mode="inline"]) .resize, .window.maximized .resize { display: none; }

header.bar {
  display: flex; align-items: center; gap: 8px; padding: 10px 12px; border-bottom: 1px solid var(--saimcp-border);
  background: var(--saimcp-surface);
}
header.bar .title { flex: 1; font-weight: 600; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.icon-btn {
  border: none; background: transparent; cursor: pointer; width: 30px; height: 30px; border-radius: 8px;
  display: grid; place-items: center; color: var(--saimcp-muted);
}
.icon-btn:hover { background: var(--saimcp-border); color: var(--saimcp-fg); }
.icon-btn svg { width: 18px; height: 18px; }
.icon-btn[aria-pressed="true"] { color: var(--saimcp-accent); }

/* ── messages ─────────────────────────────────────────────────────────────────────────────── */
.messages { flex: 1; overflow-y: auto; padding: 16px 14px; display: flex; flex-direction: column; gap: 14px;
  overscroll-behavior: contain; scroll-behavior: smooth; }
.empty { margin: auto; text-align: center; color: var(--saimcp-muted); max-width: 80%; }
.msg { display: flex; flex-direction: column; gap: 6px; max-width: 100%; }
.msg.user { align-items: flex-end; }
.msg.user .bubble {
  background: var(--saimcp-user-bg); padding: 8px 12px; border-radius: 14px 14px 4px 14px; max-width: 85%;
  white-space: pre-wrap; overflow-wrap: anywhere;
}
.msg.assistant .body { overflow-wrap: anywhere; min-height: 1em; }
.msg.assistant .body.streaming::after {
  content: ""; display: inline-block; width: 7px; height: 1em; margin-left: 2px; vertical-align: text-bottom;
  background: var(--saimcp-accent); animation: blink 1s steps(2) infinite;
}
@keyframes blink { 50% { opacity: 0; } }
.jump {
  position: absolute; left: 50%; transform: translateX(-50%); bottom: 84px; z-index: 3;
  border: 1px solid var(--saimcp-border); background: var(--saimcp-bg); border-radius: 16px; padding: 4px 12px;
  cursor: pointer; box-shadow: 0 2px 8px rgba(0,0,0,.12); font-size: 12px;
}
.jump[hidden] { display: none; }

/* markdown */
.body > :first-child { margin-top: 0; } .body > :last-child { margin-bottom: 0; }
.body p, .body ul, .body ol, .body blockquote, .body .table-wrap, .body .code-block, .body .mermaid-block { margin: 0 0 10px; }
.body h1, .body h2, .body h3, .body h4, .body h5, .body h6 { margin: 14px 0 8px; line-height: 1.3; }
.body h1 { font-size: 1.35em; } .body h2 { font-size: 1.2em; } .body h3 { font-size: 1.08em; } .body h4, .body h5, .body h6 { font-size: 1em; }
.body ul, .body ol { padding-left: 1.4em; }
.body li { margin: 2px 0; }
.body li.task { list-style: none; margin-left: -1.2em; }
.body blockquote { border-left: 3px solid var(--saimcp-border); padding: 2px 10px; color: var(--saimcp-muted); }
.body hr { border: none; border-top: 1px solid var(--saimcp-border); margin: 12px 0; }
.body a { color: var(--saimcp-accent); }
.body code { font-family: var(--saimcp-mono); font-size: .9em; background: var(--saimcp-code-bg); padding: 1px 4px;
  border-radius: 4px; }
.table-wrap { overflow-x: auto; max-width: 100%; }
table { border-collapse: collapse; font-size: 13px; min-width: 50%; }
th, td { border: 1px solid var(--saimcp-border); padding: 5px 8px; text-align: left; vertical-align: top; }
th { background: var(--saimcp-surface); font-weight: 600; }
td[data-format="number"], th[data-format="number"] { text-align: right; font-variant-numeric: tabular-nums; }
.code-block { border: 1px solid var(--saimcp-border); border-radius: 8px; overflow: hidden; background: var(--saimcp-code-bg); }
.code-bar { display: flex; align-items: center; justify-content: space-between; padding: 3px 8px;
  border-bottom: 1px solid var(--saimcp-border); font-size: 12px; color: var(--saimcp-muted); }
.code-block pre { margin: 0; padding: 10px 12px; overflow-x: auto; }
.code-block pre code { background: none; padding: 0; font-size: 12.5px; line-height: 1.55; white-space: pre; }
.tok-keyword { color: var(--tok-keyword); } .tok-string { color: var(--tok-string); } .tok-number { color: var(--tok-number); }
.tok-comment { color: var(--tok-comment); font-style: italic; } .tok-literal { color: var(--tok-literal); }
.tok-function { color: var(--tok-function); } .tok-builtin { color: var(--tok-builtin); } .tok-type { color: var(--tok-type); }
.tok-property { color: var(--tok-property); } .tok-operator { color: var(--tok-operator); }
.tok-annotation { color: var(--tok-annotation); } .tok-tag { color: var(--tok-tag); } .tok-variable { color: var(--tok-variable); }
.mermaid-block { border: 1px solid var(--saimcp-border); border-radius: 8px; padding: 8px; background: var(--saimcp-bg); }
.mermaid-block[data-rendered="true"] .mermaid-source { display: none; }
.mermaid-block.show-source .mermaid-source { display: block !important; }
.mermaid-block .mermaid-source { margin: 0; overflow-x: auto; background: var(--saimcp-code-bg); padding: 8px; border-radius: 6px; }
.mermaid-view { overflow-x: auto; text-align: center; }
.mermaid-view svg { max-width: 100%; height: auto; }
.mermaid-note { font-size: 12px; color: var(--saimcp-muted); margin-bottom: 6px; }
.mermaid-tools { display: flex; gap: 6px; justify-content: flex-end; margin-top: 4px; }
.image-placeholder { border: 1px dashed var(--saimcp-border); background: var(--saimcp-surface); border-radius: 6px;
  padding: 6px 10px; cursor: pointer; font-size: 12px; color: var(--saimcp-muted); }
.body img { max-width: 100%; border-radius: 6px; }

/* steps */
details.steps { border: 1px solid var(--saimcp-border); border-radius: 10px; background: var(--saimcp-surface);
  font-size: 13px; }
details.steps > summary { cursor: pointer; padding: 6px 10px; color: var(--saimcp-muted); list-style: none;
  display: flex; align-items: center; gap: 8px; }
details.steps > summary::-webkit-details-marker { display: none; }
details.steps > summary::after { content: "›"; margin-left: auto; transition: transform .15s; }
details.steps[open] > summary::after { transform: rotate(90deg); }
.steps ol { list-style: none; margin: 0; padding: 0 10px 8px; display: flex; flex-direction: column; gap: 6px; }
.step { display: grid; grid-template-columns: 18px 1fr; gap: 6px; align-items: start; }
.step .icon { width: 16px; height: 16px; margin-top: 2px; display: grid; place-items: center; }
.step .title { font-weight: 500; }
.step .detail { color: var(--saimcp-muted); }
.step pre { margin: 4px 0 0; padding: 6px 8px; background: var(--saimcp-code-bg); border-radius: 6px; overflow-x: auto;
  font-family: var(--saimcp-mono); font-size: 12px; white-space: pre-wrap; word-break: break-word; }
.step .step-copy { margin-top: 2px; }
.spinner { width: 12px; height: 12px; border: 2px solid var(--saimcp-border); border-top-color: var(--saimcp-accent);
  border-radius: 50%; animation: spin .8s linear infinite; }
@keyframes spin { to { transform: rotate(360deg); } }
.ok { color: var(--saimcp-success); } .fail { color: var(--saimcp-danger); }

/* actions, feedback, copy */
.actions { display: flex; align-items: center; gap: 2px; color: var(--saimcp-muted); }
.actions .icon-btn { width: 28px; height: 28px; }
.copy { border: 1px solid var(--saimcp-border); background: var(--saimcp-bg); border-radius: 6px; padding: 1px 8px;
  font-size: 12px; cursor: pointer; color: var(--saimcp-muted); }
.copy:hover { color: var(--saimcp-fg); }
.code-bar .copy-code { border: none; background: transparent; cursor: pointer; font-size: 12px; color: var(--saimcp-muted); }
.feedback-form { border: 1px solid var(--saimcp-border); border-radius: 10px; padding: 10px; display: flex;
  flex-direction: column; gap: 8px; background: var(--saimcp-surface); font-size: 13px; }
.chips { display: flex; flex-wrap: wrap; gap: 6px; }
.chip { border: 1px solid var(--saimcp-border); background: var(--saimcp-bg); border-radius: 14px; padding: 2px 10px;
  cursor: pointer; font-size: 12px; }
.chip[aria-pressed="true"] { border-color: var(--saimcp-accent); color: var(--saimcp-accent); }
.feedback-form textarea { resize: vertical; min-height: 54px; }
.feedback-form .row { display: flex; gap: 8px; justify-content: flex-end; }
.thanks { font-size: 12px; color: var(--saimcp-muted); }

/* components */
.component { display: flex; flex-direction: column; gap: 6px; align-items: flex-start; }
.component > :first-child { width: 100%; }
.choice { border: 1px solid var(--saimcp-border); border-radius: 12px; padding: 10px; display: flex;
  flex-direction: column; gap: 8px; background: var(--saimcp-surface); }
.choice-question { margin: 0; font-weight: 600; }
.choice-options { display: flex; flex-direction: column; gap: 6px; }
.choice-option { display: grid; grid-template-columns: 18px 1fr; column-gap: 8px; text-align: left; cursor: pointer;
  border: 1px solid var(--saimcp-border); border-radius: 10px; padding: 7px 10px; background: var(--saimcp-bg); }
.choice-option:hover:not(:disabled) { border-color: var(--saimcp-accent); }
.choice-option .choice-mark { grid-row: span 2; width: 16px; height: 16px; margin-top: 2px; border-radius: 50%;
  border: 2px solid var(--saimcp-border); }
.choice-option[role="checkbox"] .choice-mark { border-radius: 4px; }
.choice-option.selected { border-color: var(--saimcp-accent); background: var(--saimcp-user-bg); }
.choice-option.selected .choice-mark { border-color: var(--saimcp-accent); background: var(--saimcp-accent);
  box-shadow: inset 0 0 0 3px var(--saimcp-bg); }
.choice-option:disabled { cursor: default; opacity: .75; }
.choice-option.selected:disabled { opacity: 1; }
.choice-desc { grid-column: 2; font-size: 12px; color: var(--saimcp-muted); }
.choice-other, textarea, input[type="text"] { font: inherit; color: inherit; background: var(--saimcp-bg);
  border: 1px solid var(--saimcp-border); border-radius: 8px; padding: 7px 9px; width: 100%; }
.choice-actions { display: flex; justify-content: flex-end; }
.choice-status { margin: 0; font-size: 12px; color: var(--saimcp-muted); min-height: 0; }
.choice-status:empty { display: none; }
.choice-status.answered { color: var(--saimcp-success); }
.choice-status.error { color: var(--saimcp-danger); }
button.primary { background: var(--saimcp-accent); color: var(--saimcp-accent-fg); border: none; border-radius: 8px;
  padding: 6px 14px; cursor: pointer; font-weight: 500; }
button.primary:disabled { opacity: .5; cursor: default; }
button.secondary { background: transparent; border: 1px solid var(--saimcp-border); border-radius: 8px; padding: 6px 12px;
  cursor: pointer; }
.display { display: flex; flex-direction: column; gap: 10px; width: 100%; }
.display h4 { margin: 0 0 6px; font-size: 13px; }
.display-fields { display: grid; grid-template-columns: max-content 1fr; gap: 4px 12px; margin: 0; font-size: 13px; }
.display-fields dt { color: var(--saimcp-muted); } .display-fields dd { margin: 0; }
.display-note { font-size: 12px; color: var(--saimcp-muted); margin: 4px 0 0; }
.display-section { border-left: 3px solid var(--saimcp-border); padding-left: 10px; }
.proposal { border: 1px solid var(--saimcp-accent); border-radius: 10px; padding: 10px; }
.proposal p { margin: 4px 0; }
.json-view summary { cursor: pointer; color: var(--saimcp-muted); font-size: 12px; }
.json-view pre { background: var(--saimcp-code-bg); padding: 8px; border-radius: 6px; overflow-x: auto; font-size: 12px; }

/* errors */
.error-box { border: 1px solid var(--saimcp-danger); color: var(--saimcp-danger); border-radius: 10px; padding: 8px 10px;
  display: flex; align-items: center; justify-content: space-between; gap: 8px; font-size: 13px; }
.banner { margin: 8px 12px 0; }

/* composer */
footer.composer { border-top: 1px solid var(--saimcp-border); padding: 10px; display: flex; flex-direction: column; gap: 4px; }
.composer-row { display: flex; gap: 8px; align-items: flex-end; }
.composer textarea { flex: 1; resize: none; max-height: 160px; min-height: 40px; line-height: 1.4; }
.composer .send { width: 40px; height: 40px; border-radius: 10px; border: none; background: var(--saimcp-accent);
  color: var(--saimcp-accent-fg); cursor: pointer; display: grid; place-items: center; flex: none; }
.composer .send:disabled { opacity: .45; cursor: default; }
.composer .send svg { width: 18px; height: 18px; }
.counter { font-size: 11px; color: var(--saimcp-muted); text-align: right; }
.counter.over { color: var(--saimcp-danger); }
@media (prefers-reduced-motion: reduce) { * { animation: none !important; scroll-behavior: auto !important; } }
`;
