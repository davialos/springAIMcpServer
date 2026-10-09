import { api as server } from './api.js';
import { clear, download, h } from './dom.js';
import { toWorkflow } from './flow-model.js';
import { notify, save, state } from './state.js';

const view = { busy: false, error: '', result: null, file: null };

function request() {
  const s = state.settings;
  return {
    contract: state.contract,
    workflows: state.scenarios.filter((w) => w.steps.length).map(toWorkflow),
    valueMap: state.valueMap,
    seed: Number(s.seed), validCount: Number(s.valid), casesPerExpression: Number(s.cases),
    options: { categories: [], maxPerParameter: Number(s.maxPerParameter) || 0, combined: Number(s.combined) || 0 },
  };
}

async function run() {
  view.busy = true; view.error = ''; view.result = null;
  notify();
  try {
    view.result = await server.generate(request());
    view.file = 'k6/main.js';
  } catch (e) {
    view.error = String(e.message);
  }
  view.busy = false;
  notify();
}

async function zip() {
  try {
    const blob = await server.zip(request());
    const url = URL.createObjectURL(blob);
    const a = h('a', { href: url, download: state.contract.name + '-celfaker-suite.zip' });
    document.body.append(a);
    a.click();
    a.remove();
    setTimeout(() => URL.revokeObjectURL(url), 1000);
  } catch (e) {
    view.error = String(e.message);
    notify();
  }
}

export function render(root) {
  clear(root);
  const s = state.settings;
  const num = (key, min = 0) => h('input', { type: 'number', min, value: s[key], onchange: (e) => { s[key] = Number(e.target.value); save(); } });
  root.append(
    h('p', { class: 'lead' }, 'One k6 project per scenario that has steps (a single scenario goes to <code>k6/</code>, several to <code>k6/&lt;scenario&gt;/</code>). Generates the parameter library, attribute map, CEL expressions with input/result cases, request data for every API (valid, rule-satisfying and negative) and a k6 project that runs your workflow.'),
    h('div', { class: 'card' },
      h('div', { class: 'toolbar' },
        h('label', { class: 'inline' }, 'Seed ', num('seed', -2147483648)),
        h('label', { class: 'inline' }, 'Valid bodies per API ', num('valid', 1)),
        h('label', { class: 'inline' }, 'Cases per expression (0 = none) ', num('cases'))),
      h('div', { class: 'toolbar' },
        h('button', { class: 'btn primary', disabled: view.busy || !state.contract.apis.length, onclick: run }, view.busy ? 'Generating…' : 'Generate'),
        h('button', { class: 'btn', disabled: !state.contract.apis.length, onclick: zip }, 'Download .zip'),
        h('span', { class: 'muted' }, 'Run: ', h('code', {}, 'k6 run -e BASE_URL=' + state.contract.baseUrl + ' k6/main.js'))),
      view.error ? h('p', { class: 'error' }, view.error) : null));
  const res = view.result;
  if (!res) return;
  root.append(
    h('div', { class: 'card' }, h('h3', {}, 'Summary'),
      h('div', { class: 'stats' }, Object.entries(res.summary).filter(([k, v]) => typeof v === 'number').map(([k, v]) => h('div', { class: 'stat' }, h('b', {}, v), h('span', {}, k)))),
      Object.entries(res.summary).filter(([k]) => k.startsWith('data.')).map(([k, v]) => h('p', {}, h('code', {}, k.slice(5)), ': ', v.valid + ' valid, ' + v.invalid + ' invalid bodies')),
      res.warnings.map((w) => h('p', { class: 'warn' }, w))),
    h('div', { class: 'split' },
      h('div', { class: 'list' }, Object.keys(res.files).map((f) => h('button', { class: 'list-item' + (f === view.file ? ' active' : ''), onclick: () => { view.file = f; notify(); } }, f))),
      h('div', { class: 'card' },
        h('div', { class: 'toolbar' }, h('b', {}, view.file), h('button', { class: 'btn sm', onclick: () => download(view.file.split('/').pop(), res.files[view.file], 'text/plain') }, 'Download')),
        h('pre', { class: 'code' }, (res.files[view.file] ?? '').slice(0, 60000)))));
}
