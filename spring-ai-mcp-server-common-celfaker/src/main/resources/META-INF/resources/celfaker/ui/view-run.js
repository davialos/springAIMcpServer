import { api as server } from './api.js';
import { debounce, h } from './dom.js';
import { duplicateScenario, newWorkflow, toWorkflow, uniqueName } from './flow-model.js';
import { runScenario } from './scenario-runner.js';
import { notify, selectScenario, state } from './state.js';

const runs = new Map(); // scenario object -> { running, events, summary, problems, error }  (in memory only)
const opts = { iterations: 1, env: '' };
const openRows = new Set();
const invalidCache = new Map(); // api id + hash -> reasons

function parseEnv(text) {
  const env = {};
  for (const line of text.split('\n')) {
    const m = /^\s*([A-Za-z0-9_]+)\s*=\s*(.*)$/.exec(line);
    if (m) env[m[1]] = m[2];
  }
  return env;
}

export function currentRun() {
  return runs.get(state.workflow);
}

/** 'ok' | 'fail' | 'running' | '' for the node of a step in the scenario on the canvas. */
export function nodeStatus(stepId) {
  const r = runs.get(state.workflow);
  if (!r) return '';
  const last = [...r.events].reverse().find((e) => e.step === stepId);
  return !last ? '' : last.phase === 'start' ? 'running' : last.ok ? 'ok' : 'fail';
}

async function runOne(wf) {
  const r = { running: true, events: [], summary: null, problems: [], error: '' };
  runs.set(wf, r);
  notify();
  try {
    r.summary = await runScenario({
      contract: state.contract, workflow: toWorkflow(wf), prepare: server.prepare, send: server.send,
      env: parseEnv(opts.env), iterations: Math.max(1, Number(opts.iterations) || 1), seed: Number(state.settings.seed),
      onEvent: (e) => { if (e.phase !== 'iteration') { r.events.push(e); notify(); } },
    });
    r.problems = r.summary.problems;
  } catch (e) {
    r.error = String(e.message);
  }
  r.running = false;
  notify();
}

async function runAll() {
  for (const wf of state.scenarios.filter((w) => w.steps.length)) await runOne(wf);
}

const outcome = (wf) => {
  const r = runs.get(wf);
  if (!r) return '';
  return r.running ? 'running' : r.error || r.problems.length || r.summary?.failed ? 'fail' : r.summary ? 'ok' : '';
};

// ---------------------------------------------------------------------------------------------------------------------
export function scenarioBar() {
  const list = state.scenarios;
  return h('div', { class: 'scenario-bar', role: 'tablist', 'aria-label': 'Scenarios' },
    list.map((w, i) => h('button', { role: 'tab', 'aria-selected': w === state.workflow, class: 'scenario-tab' + (w === state.workflow ? ' active' : ''),
      onclick: () => { selectScenario(i); notify(); } }, h('span', { class: 'dot ' + outcome(w) }), ' ', w.name, h('small', { class: 'muted' }, ' · ' + w.steps.length))),
    h('button', { class: 'btn sm', title: 'New empty scenario', onclick: () => { const w = newWorkflow(uniqueName(list, 'Scenario ' + (list.length + 1))); list.push(w); selectScenario(list.length - 1); notify(); } }, '+ Scenario'),
    h('button', { class: 'btn sm', onclick: () => { const c = duplicateScenario(list, state.workflow); list.push(c); selectScenario(list.length - 1); notify(); } }, 'Duplicate'),
    h('button', { class: 'btn sm', onclick: () => { const n = prompt('Scenario name', state.workflow.name); if (n && n.trim()) { state.workflow.name = n.trim(); notify(); } } }, 'Rename'),
    h('button', { class: 'btn sm danger', disabled: list.length < 2, onclick: () => {
      if (!confirm('Delete scenario “' + state.workflow.name + '”?')) return;
      list.splice(list.indexOf(state.workflow), 1); selectScenario(0); notify(); } }, 'Delete'));
}

// ---------------------------------------------------------------------------------------------------------------------
function pretty(v) {
  if (typeof v === 'string') { try { return JSON.stringify(JSON.parse(v), null, 2); } catch (e) { return v; } }
  return v === null || v === undefined ? '' : JSON.stringify(v, null, 2);
}

function row(e, key) {
  const failed = e.checks?.filter((c) => !c.ok) ?? [];
  const status = e.response?.status;
  return h('details', { class: 'run-row ' + (e.ok ? 'ok' : 'fail'), open: openRows.has(key), ontoggle: (ev) => { ev.target.open ? openRows.add(key) : openRows.delete(key); } },
    h('summary', {},
      h('span', { class: 'dot ' + (e.ok ? 'ok' : 'fail') }), ' ', h('span', { class: 'muted' }, '#' + (e.iter + 1)), ' ', h('b', {}, e.step), ' ',
      status ? h('span', { class: 'chip ' + (status < 400 ? 'r-true' : 'r-false') }, 'HTTP ' + status) : null, ' ',
      e.millis !== undefined ? h('span', { class: 'muted' }, Math.round(e.millis) + ' ms') : null, ' ',
      e.checks ? h('span', { class: 'muted' }, (e.checks.length - failed.length) + '/' + e.checks.length + ' checks') : null,
      failed.map((c) => h('span', { class: 'chip r-error', title: 'failed check' }, c.name)),
      e.error ? h('span', { class: 'error' }, ' ' + e.error) : null),
    e.request ? h('div', { class: 'run-detail' },
      h('b', {}, e.request.method + ' ' + e.request.url),
      e.request.body ? h('pre', { class: 'code' }, pretty(e.request.body)) : null,
      e.response ? h('pre', { class: 'code' }, 'HTTP ' + e.response.status + '\n' + pretty(e.response.body)) : null,
      Object.keys(e.extracted ?? {}).length ? h('p', { class: 'muted' }, 'extracted: ' + JSON.stringify(e.extracted)) : null,
      h('ul', {}, (e.checks ?? []).map((c) => h('li', { class: c.ok ? 'ok' : 'error' }, (c.ok ? '✓ ' : '✗ ') + c.name)))) : null);
}

export function runPanel() {
  const wf = state.workflow;
  const r = runs.get(wf);
  const ended = r ? r.events.filter((e) => e.phase === 'end') : [];
  const summary = r?.summary;
  return h('section', { class: 'card run-panel' },
    h('div', { class: 'toolbar' },
      h('b', {}, 'Run scenario'),
      h('label', { class: 'inline' }, 'Iterations ', h('input', { type: 'number', min: 1, max: 50, value: opts.iterations, onchange: (e) => { opts.iterations = Number(e.target.value); } })),
      h('button', { class: 'btn primary', disabled: !wf.steps.length || r?.running, onclick: () => runOne(wf) }, r?.running ? 'Running…' : '▶ Run “' + wf.name + '”'),
      h('button', { class: 'btn', disabled: r?.running || !state.scenarios.some((w) => w.steps.length), onclick: runAll }, '▶▶ Run all scenarios'),
      h('button', { class: 'btn sm', onclick: () => { runs.clear(); notify(); } }, 'Clear results')),
    h('label', { class: 'field' }, h('span', { class: 'label' }, 'Environment for {{env.NAME}} (NAME=value per line, kept in memory only)'),
      h('textarea', { rows: 2, class: 'mono', spellcheck: 'false', placeholder: 'TOKEN=…', oninput: (e) => { opts.env = e.target.value; } }, opts.env)),
    h('p', { class: 'muted' }, 'Sends real requests to ', h('code', {}, state.contract.baseUrl), ' from this machine, with generated data, extracting and injecting values exactly like the k6 suite.'),
    r?.error ? h('p', { class: 'error' }, r.error) : null,
    r?.problems.map((p) => h('p', { class: 'warn' }, p)),
    summary && !summary.problems.length ? h('p', { class: summary.failed ? 'error' : 'ok' },
      (summary.failed ? '✗ ' : '✓ ') + summary.passed + ' of ' + summary.iterations + ' iterations passed') : null,
    h('div', { class: 'run-rows' }, ended.map((e, i) => row(e, wf.name + ':' + i))),
    state.scenarios.filter((w) => runs.get(w) && w !== wf).length
      ? h('div', {}, h('h4', {}, 'Other scenarios'), state.scenarios.filter((w) => runs.get(w) && w !== wf).map((w) => {
        const o = runs.get(w);
        return h('p', {}, h('span', { class: 'dot ' + outcome(w) }), ' ', h('b', {}, w.name), ' ', o.summary ? o.summary.passed + '/' + o.summary.iterations + ' passed' : (o.error || 'running…'));
      })) : null);
}

// ---------------------------------------------------------------------------------------------------------------------
const hashOf = (spec) => spec.id + ':' + JSON.stringify([spec.requestExample, spec.rules, spec.selectedPaths]);

/** Reasons of the generated invalid cases of an API (loaded on demand, cached until the API changes). */
export function invalidReasons(spec) {
  return invalidCache.get(hashOf(spec)) ?? [];
}

export const loadInvalidReasons = debounce(async (spec) => {
  try {
    const d = await server.fake(spec, Number(state.settings.seed), 1);
    invalidCache.set(hashOf(spec), d.invalid.map((c) => c.reason));
    notify();
  } catch (e) { /* the inspector keeps working with a free-text field */ }
}, 50);

