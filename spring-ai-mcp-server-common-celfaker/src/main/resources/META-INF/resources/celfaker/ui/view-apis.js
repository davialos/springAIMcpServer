import { api as server } from './api.js';
import { clear, debounce, download, h, pickFile } from './dom.js';
import { prune } from './flow-model.js';
import { apiById, notify, save, state } from './state.js';

const METHODS = ['GET', 'POST', 'PUT', 'PATCH', 'DELETE'];

export async function refreshCandidates(spec) {
  if (!spec.requestExample || typeof spec.requestExample !== 'object' || spec.role === 'VALIDATION') {
    delete state.candidates[spec.id];
    return;
  }
  try {
    const res = await server.analyze(spec.requestExample, spec.sysObject || spec.id.replace(/[^A-Za-z0-9_]/g, '_'), spec.mapPaths ?? []);
    state.candidates[spec.id] = res.candidates;
    state.skipped[spec.id] = res.skipped;
  } catch (e) {
    state.skipped[spec.id] = [String(e.message)];
  }
}

export async function setContract(contract) {
  state.contract = { name: contract.name ?? 'my-api', baseUrl: contract.baseUrl ?? 'http://localhost:8080', apis: contract.apis ?? [] };
  state.candidates = {};
  for (const a of state.contract.apis) await refreshCandidates(a);
  prune(state.workflow, state.contract.apis.map((a) => a.id));
  state.ui.api = state.contract.apis[0]?.id ?? null;
  notify();
}

function field(label, control, hint) {
  return h('label', { class: 'field' }, h('span', { class: 'label' }, label), control, hint ? h('small', {}, hint) : null);
}

function jsonArea(value, onValue, rows = 8) {
  const err = h('small', { class: 'error' });
  const area = h('textarea', { rows, spellcheck: 'false', class: 'mono' });
  area.value = value === undefined ? '' : JSON.stringify(value, null, 2);
  area.addEventListener('input', () => {
    if (!area.value.trim()) { err.textContent = ''; onValue(undefined); return; }
    try { onValue(JSON.parse(area.value)); err.textContent = ''; } catch (e) { err.textContent = 'Invalid JSON: ' + e.message; }
  });
  return h('div', {}, area, err);
}

export function render(root) {
  clear(root);
  const spec = apiById(state.ui.api);
  const list = h('div', { class: 'list' },
    state.contract.apis.map((a) => h('button', { class: 'list-item' + (a.id === state.ui.api ? ' active' : ''), onclick: () => { state.ui.api = a.id; notify(); } },
      h('span', { class: 'badge m-' + a.method }, a.method), ' ', a.name || a.id, a.role === 'VALIDATION' ? h('span', { class: 'chip' }, 'validation') : null)),
    h('button', { class: 'btn', onclick: addApi }, '+ Add API'));

  const bar = h('div', { class: 'toolbar' },
    field('Project', h('input', { value: state.contract.name, oninput: (e) => { state.contract.name = e.target.value; save(); } })),
    field('Base URL', h('input', { value: state.contract.baseUrl, oninput: (e) => { state.contract.baseUrl = e.target.value; save(); } })),
    h('button', { class: 'btn', onclick: async () => setContract(await server.example()) }, 'Load example'),
    h('button', { class: 'btn', onclick: async () => { const t = await pickFile(); if (t) { try { await setContract(JSON.parse(t)); } catch (e) { alert('Not a contract file: ' + e.message); } } } }, 'Import contract'),
    h('button', { class: 'btn', onclick: () => download(state.contract.name + '-contract.json', JSON.stringify(state.contract, null, 2)) }, 'Export contract'));

  root.append(h('p', { class: 'lead' }, 'Describe each API with its documentation, an example request body (its fields become CEL parameters), the rules it enforces and, for validation endpoints, the action they check.'),
    bar, h('div', { class: 'split' }, list, spec ? editor(spec) : h('p', { class: 'empty' }, 'Add an API or load the example to start.')));
}

function addApi() {
  let n = state.contract.apis.length + 1;
  while (apiById('api' + n)) n++;
  state.contract.apis.push({ id: 'api' + n, name: 'New API', method: 'POST', path: '/api/resource', role: 'ACTION', description: '', requestExample: { name: 'sample' }, responseExample: { id: 1 }, rules: [], expectedStatus: [201], invalidStatus: [400, 422] });
  state.ui.api = 'api' + n;
  refreshCandidates(apiById('api' + n)).then(notify);
}

function editor(spec) {
  const reanalyze = debounce(async () => { await refreshCandidates(spec); save(); }, 400);
  const set = (key, parse = (v) => v) => (e) => { spec[key] = parse(e.target.value); save(); };
  const ints = (v) => v.split(',').map((s) => parseInt(s.trim(), 10)).filter(Number.isFinite);
  const actions = state.contract.apis.filter((a) => a.role !== 'VALIDATION');
  return h('div', { class: 'card' },
    h('div', { class: 'grid2' },
      field('Id', h('input', { value: spec.id, onchange: (e) => renameApi(spec, e.target.value) }), 'letters, digits, _ and -'),
      field('Name', h('input', { value: spec.name, oninput: set('name') })),
      field('Method', h('select', { onchange: set('method') }, METHODS.map((m) => h('option', { value: m, selected: m === spec.method }, m)))),
      field('Path', h('input', { value: spec.path, oninput: set('path') }), 'use {id} for path parameters'),
      field('Role', h('select', { onchange: (e) => { spec.role = e.target.value; refreshCandidates(spec).then(notify); } },
        ['ACTION', 'VALIDATION'].map((r) => h('option', { value: r, selected: r === spec.role }, r)))),
      spec.role === 'VALIDATION'
        ? field('Validates', h('select', { onchange: set('validates') }, h('option', { value: '' }, '—'), actions.map((a) => h('option', { value: a.id, selected: a.id === spec.validates }, a.id))))
        : field('Sys object', h('input', { value: spec.sysObject ?? '', placeholder: spec.id, oninput: (e) => { spec.sysObject = e.target.value || undefined; reanalyze(); } }), 'CEL name = sysObject.attribute'),
      field('Expected status (valid)', h('input', { value: (spec.expectedStatus ?? []).join(', '), onchange: set('expectedStatus', ints) })),
      field('Expected status (invalid)', h('input', { value: (spec.invalidStatus ?? [400, 422]).join(', '), onchange: set('invalidStatus', ints) }))),
    field('Documentation', h('textarea', { rows: 3, oninput: set('description') }, spec.description ?? '')),
    field('Headers (JSON, values may use {{env.TOKEN}})', jsonArea(spec.headers, (v) => { spec.headers = v; save(); }, 3)),
    spec.role === 'ACTION' ? h('div', {},
      field('Request body example', jsonArea(spec.requestExample, (v) => { spec.requestExample = v; reanalyze(); save(); }, 10)),
      field('Rules — CEL, one per line (valid data satisfies them, negative data violates them)',
        h('textarea', { rows: 3, class: 'mono', spellcheck: 'false', oninput: (e) => { spec.rules = e.target.value.split('\n').map((s) => s.trim()).filter(Boolean); save(); } }, (spec.rules ?? []).join('\n')))) : null,
    field('Response example (used to propose variables for the next step)', jsonArea(spec.responseExample, (v) => { spec.responseExample = v; save(); }, 5)),
    spec.role === 'VALIDATION' ? field('Expected response (subset)', jsonArea(spec.expectBody, (v) => { spec.expectBody = v; save(); }, 4)) : null,
    h('div', { class: 'toolbar' }, h('button', { class: 'btn danger', onclick: () => removeApi(spec) }, 'Delete API')));
}

function renameApi(spec, id) {
  if (!/^[A-Za-z0-9_-]+$/.test(id) || (id !== spec.id && apiById(id))) { alert('Id must be unique and use letters, digits, _ or -'); notify(); return; }
  const old = spec.id;
  spec.id = id;
  state.candidates[id] = state.candidates[old];
  delete state.candidates[old];
  for (const s of state.workflow.steps) if (s.api === old) s.api = id;
  for (const a of state.contract.apis) if (a.validates === old) a.validates = id;
  state.ui.api = id;
  notify();
}

function removeApi(spec) {
  state.contract.apis = state.contract.apis.filter((a) => a !== spec);
  delete state.candidates[spec.id];
  prune(state.workflow, state.contract.apis.map((a) => a.id));
  state.ui.api = state.contract.apis[0]?.id ?? null;
  notify();
}
