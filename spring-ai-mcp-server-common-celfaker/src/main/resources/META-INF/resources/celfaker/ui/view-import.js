import { api as server } from './api.js';
import { download, h, pickFile } from './dom.js';
import { apiById, notify, state } from './state.js';

const imp = { mode: 'swagger', curl: '', url: '', spec: '', result: null, picked: new Set(), busy: false, error: '' };
const fake = { apiId: null, count: 5, data: null, tab: 'valid', busy: false, error: '' };
const send = { apiId: null, url: '', body: '', env: '', result: null, busy: false, error: '' };

const DEFAULT_URL = 'http://localhost:8080';
let localServices = null; // services of the local-dev control plane, loaded once

function field(label, control, hint) {
  return h('label', { class: 'field' }, h('span', { class: 'label' }, label), control, hint ? h('small', {}, hint) : null);
}

async function run(fn) {
  imp.busy = true; imp.error = ''; imp.result = null;
  notify();
  try {
    imp.result = await fn();
    imp.picked = new Set(imp.result.apis.map((a, i) => i));
  } catch (e) {
    imp.error = String(e.message);
  }
  imp.busy = false;
  notify();
}

/** Adds the picked imported APIs to the contract (ids made unique) and analyses their payloads. */
async function addPicked(refresh) {
  const added = [];
  for (const [i, spec] of imp.result.apis.entries()) {
    if (!imp.picked.has(i)) continue;
    let id = spec.id;
    for (let n = 2; apiById(id); n++) id = spec.id + n;
    const copy = { ...spec, id };
    state.contract.apis.push(copy);
    added.push(copy);
  }
  if (imp.result.baseUrl && (!state.contract.apis.length || state.contract.baseUrl === DEFAULT_URL || added.length === state.contract.apis.length)) {
    state.contract.baseUrl = imp.result.baseUrl;
  }
  for (const a of added) await refresh(a);
  if (added.length) state.ui.api = added[0].id;
  imp.result = null;
  notify();
}

export function importCard(refresh) {
  if (localServices === null) { localServices = []; server.localServices().then((l) => { if (l.length) { localServices = l; notify(); } }); }
  const modes = [['swagger', 'Swagger / OpenAPI URL'], ['spec', 'Paste or open a spec'], ['curl', 'cURL command']];
  const readBtn = h('button', { class: 'btn primary', disabled: imp.busy || !imp.url.trim(), onclick: () => run(() => server.importOpenApi(imp.url.trim(), null)) }, imp.busy ? 'Reading…' : 'Read documentation');
  const curlBtn = h('button', { class: 'btn primary', disabled: imp.busy || !imp.curl.trim(), onclick: () => run(() => server.importCurl(imp.curl)) }, 'Import cURL');
  const input = imp.mode === 'swagger'
    ? h('div', {}, field('Service or document address', h('input', { value: imp.url, placeholder: 'http://localhost:8080  or  https://host/v3/api-docs',
        oninput: (e) => { imp.url = e.target.value; readBtn.disabled = imp.busy || !imp.url.trim(); },
        onkeydown: (e) => { if (e.key === 'Enter' && imp.url.trim()) readBtn.click(); } }),
        'Give the service root, its Swagger UI or the JSON/YAML document: the usual locations (/v3/api-docs, /swagger.json, /openapi.yaml …) are tried.'),
      localServices.length ? h('div', { class: 'chips', 'aria-label': 'Local services' }, h('span', { class: 'muted' }, 'Running locally:'),
        localServices.map((l) => h('button', { class: 'chip toggle', title: l.url, onclick: () => { imp.url = l.url; notify(); } }, l.name))) : null,
      readBtn)
    : imp.mode === 'spec'
      ? h('div', {}, field('Swagger 2 / OpenAPI 3 (JSON or YAML)', h('textarea', { rows: 6, class: 'mono', spellcheck: 'false', oninput: (e) => { imp.spec = e.target.value; } }, imp.spec)),
        h('div', { class: 'toolbar' },
          h('button', { class: 'btn', onclick: async () => { const t = await pickFile('.json,.yaml,.yml'); if (t) { imp.spec = t; notify(); } } }, 'Open file…'),
          h('button', { class: 'btn primary', disabled: imp.busy, onclick: () => run(() => server.importOpenApi(imp.url.trim() || null, imp.spec)) }, 'Read documentation')))
      : h('div', {}, field('cURL command', h('textarea', { rows: 6, class: 'mono', spellcheck: 'false', placeholder: "curl -X POST https://api.example.com/orders -H 'Content-Type: application/json' -d '{\"qty\": 2}'", oninput: (e) => { imp.curl = e.target.value; curlBtn.disabled = imp.busy || !imp.curl.trim(); } }, imp.curl),
          'Paste from a terminal, browser DevTools (“Copy as cURL”) or Postman (“Code → cURL”). Secrets in Authorization / Cookie / API-key headers are replaced by {{env.NAME}} placeholders.'),
        curlBtn);

  const r = imp.result;
  return h('section', { class: 'card' },
    h('h3', {}, 'Add APIs'),
    h('div', { class: 'chips', role: 'tablist', 'aria-label': 'Import source' }, modes.map(([id, label]) =>
      h('button', { role: 'tab', 'aria-selected': imp.mode === id, class: 'chip toggle' + (imp.mode === id ? ' on' : ''), onclick: () => { imp.mode = id; imp.result = null; imp.error = ''; notify(); } }, label))),
    input,
    imp.error ? h('p', { class: 'error' }, imp.error) : null,
    r ? h('div', {},
      h('h4', {}, r.title, ' ', h('span', { class: 'muted' }, r.baseUrl || 'no base URL found')),
      r.warnings.map((w) => h('p', { class: 'warn' }, w)),
      h('div', { class: 'toolbar' },
        h('button', { class: 'btn sm', onclick: () => { imp.picked = new Set(r.apis.map((_, i) => i)); notify(); } }, 'All'),
        h('button', { class: 'btn sm', onclick: () => { imp.picked = new Set(); notify(); } }, 'None'),
        h('button', { class: 'btn primary', disabled: !imp.picked.size, onclick: () => addPicked(refresh) }, 'Add ' + imp.picked.size + ' selected')),
      h('div', { class: 'scroll tall' }, h('table', {},
        h('thead', {}, h('tr', {}, h('th', {}, ''), h('th', {}, 'Method'), h('th', {}, 'Path'), h('th', {}, 'Name'), h('th', {}, 'Rules'))),
        h('tbody', {}, r.apis.map((a, i) => h('tr', {},
          h('td', {}, h('input', { type: 'checkbox', checked: imp.picked.has(i), 'aria-label': 'import ' + a.id, onchange: (e) => { e.target.checked ? imp.picked.add(i) : imp.picked.delete(i); notify(); } })),
          h('td', {}, h('span', { class: 'badge m-' + a.method }, a.method)), h('td', { class: 'mono wrap' }, a.path),
          h('td', {}, a.name, a.role === 'VALIDATION' ? h('span', { class: 'chip' }, 'validation') : null), h('td', {}, a.rules?.length ?? 0))))))) : null);
}

// ---------------------------------------------------------------------------------------------------------------------
export function fakePanel(spec, refresh) {
  if (fake.apiId !== spec.id) { Object.assign(fake, { apiId: spec.id, data: null, error: '', tab: 'valid' }); }
  const generate = async () => {
    fake.busy = true; fake.error = '';
    notify();
    try { fake.data = await server.fake(spec, Number(state.settings.seed), Number(fake.count)); } catch (e) { fake.error = String(e.message); }
    fake.busy = false;
    notify();
  };
  const d = fake.data;
  return h('section', { class: 'card' },
    h('h3', {}, 'Fake input'),
    h('p', { class: 'muted' }, 'Valid bodies satisfy the API’s rules; invalid ones break a type, a required field or a rule. Values come from the attribute map generator, seed ', String(state.settings.seed), '.'),
    h('div', { class: 'toolbar' },
      h('label', { class: 'inline' }, 'Valid bodies ', h('input', { type: 'number', min: 1, max: 500, value: fake.count, onchange: (e) => { fake.count = Number(e.target.value); } })),
      h('button', { class: 'btn primary', disabled: fake.busy || !spec.requestExample, onclick: generate }, fake.busy ? 'Generating…' : 'Generate fake input')),
    fake.error ? h('p', { class: 'error' }, fake.error) : null,
    d ? h('div', {},
      d.warnings.map((w) => h('p', { class: 'warn' }, w)),
      h('div', { class: 'chips' }, [['valid', 'Valid (' + d.valid.length + ')'], ['invalid', 'Invalid (' + d.invalid.length + ')']].map(([id, label]) =>
        h('button', { class: 'chip toggle' + (fake.tab === id ? ' on' : ''), onclick: () => { fake.tab = id; notify(); } }, label))),
      fake.tab === 'valid'
        ? h('div', {},
          h('div', { class: 'toolbar' },
            h('button', { class: 'btn sm', onclick: () => download(spec.id + '.valid.json', JSON.stringify(d.valid, null, 2)) }, 'Download JSON'),
            h('button', { class: 'btn sm', onclick: async () => { spec.requestExample = d.valid[0]; await refresh(spec); notify(); } }, 'Use first as request example'),
            h('button', { class: 'btn sm', onclick: () => { send.apiId = spec.id; send.body = JSON.stringify(d.valid[0], null, 2); notify(); } }, 'Use first in Send')),
          h('pre', { class: 'code' }, JSON.stringify(d.valid, null, 2).slice(0, 30000)))
        : h('div', { class: 'scroll tall' }, h('table', {},
          h('thead', {}, h('tr', {}, h('th', {}, 'Why it is invalid'), h('th', {}, 'Expected'), h('th', {}, 'Body'), h('th', {}, ''))),
          h('tbody', {}, d.invalid.map((c) => h('tr', {}, h('td', { class: 'mono wrap' }, c.reason), h('td', {}, c.expectedStatus.join(' / ')),
            h('td', { class: 'mono wrap' }, JSON.stringify(c.body).slice(0, 160)),
            h('td', {}, h('button', { class: 'btn sm', onclick: () => { send.apiId = spec.id; send.body = JSON.stringify(c.body, null, 2); notify(); } }, 'Send')))))))) : null);
}

// ---------------------------------------------------------------------------------------------------------------------
function parseEnv(text) {
  const env = {};
  for (const line of text.split('\n')) {
    const m = /^\s*([A-Za-z0-9_]+)\s*=\s*(.*)$/.exec(line);
    if (m) env[m[1]] = m[2];
  }
  return env;
}

export function sendPanel(spec) {
  if (send.apiId !== spec.id) Object.assign(send, { apiId: spec.id, url: '', body: '', result: null, error: '' });
  const url = send.url || (state.contract.baseUrl.replace(/\/$/, '') + spec.path);
  const body = send.body || (spec.requestExample && typeof spec.requestExample === 'object' && spec.role !== 'VALIDATION' ? JSON.stringify(spec.requestExample, null, 2) : '');
  const go = async () => {
    send.busy = true; send.error = ''; send.result = null;
    notify();
    try {
      let parsed;
      if (send.body.trim() || body.trim()) {
        try { parsed = JSON.parse(send.body.trim() ? send.body : body); } catch (e) { throw new Error('The body is not valid JSON: ' + e.message); }
      }
      send.result = await server.send({ method: spec.method, url: send.url || url, headers: spec.headers ?? {}, body: parsed, env: parseEnv(send.env) });
    } catch (e) {
      send.error = String(e.message);
    }
    send.busy = false;
    notify();
  };
  const r = send.result;
  return h('section', { class: 'card' },
    h('h3', {}, 'Send'),
    h('p', { class: 'muted' }, 'Calls the real service from this machine, like a REST client. Path parameters such as {id} must be replaced in the URL.'),
    h('div', { class: 'grid2' },
      field('URL', h('input', { value: url, class: 'mono', oninput: (e) => { send.url = e.target.value; } })),
      field('Environment (NAME=value per line, kept in memory only)', h('textarea', { rows: 2, class: 'mono', spellcheck: 'false', placeholder: 'TOKEN=…', oninput: (e) => { send.env = e.target.value; } }, send.env))),
    spec.method === 'GET' || spec.method === 'DELETE' ? null : field('Body (JSON)', h('textarea', { rows: 6, class: 'mono', spellcheck: 'false', oninput: (e) => { send.body = e.target.value; } }, body)),
    h('div', { class: 'toolbar' }, h('button', { class: 'btn primary', disabled: send.busy, onclick: go }, send.busy ? 'Sending…' : 'Send ' + spec.method)),
    send.error ? h('p', { class: 'error' }, send.error) : null,
    r ? h('div', {},
      r.error ? h('p', { class: 'error' }, r.error)
        : h('p', {}, h('span', { class: 'chip ' + (r.status < 400 ? 'r-true' : 'r-error') }, 'HTTP ' + r.status), ' ', h('span', { class: 'muted' }, r.millis + ' ms')),
      r.unresolvedEnv?.length ? h('p', { class: 'warn' }, 'Not set: ' + r.unresolvedEnv.map((n) => '{{env.' + n + '}}').join(', ')) : null,
      r.body !== undefined ? h('pre', { class: 'code' }, pretty(r.body) + (r.truncated ? '\n… truncated' : '')) : null,
      r.body !== undefined && r.status < 300 ? h('button', { class: 'btn sm', onclick: () => { try { spec.responseExample = JSON.parse(r.body); notify(); } catch (e) { alert('The response is not JSON'); } } }, 'Use as response example') : null) : null);
}

function pretty(text) {
  try { return JSON.stringify(JSON.parse(text), null, 2); } catch (e) { return text; }
}
