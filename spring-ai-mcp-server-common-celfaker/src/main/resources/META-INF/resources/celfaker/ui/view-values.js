import { clear, download, h, pickFile } from './dom.js';
import { api as server } from './api.js';
import { notify, save, selectedCandidates, state } from './state.js';

const view = { name: null, error: '' };

async function generate() {
  state.valueMap = await server.attributeMap(selectedCandidates(), Number(state.settings.seed));
  view.name = null;
  notify();
}

function listArea(label, list, onChange, hint) {
  const err = h('small', { class: 'error' });
  const area = h('textarea', { rows: 6, class: 'mono', spellcheck: 'false', 'aria-label': label });
  area.value = JSON.stringify(list, null, 1);
  area.addEventListener('input', () => {
    try { const v = JSON.parse(area.value); if (!Array.isArray(v)) throw new Error('must be a JSON array'); onChange(v); err.textContent = ''; save(); } catch (e) { err.textContent = e.message; }
  });
  return h('label', { class: 'field' }, h('span', { class: 'label' }, label), hint ? h('small', {}, hint) : null, area, err);
}

export function render(root) {
  clear(root);
  root.append(h('p', { class: 'lead' }, 'The attribute map links each ', h('code', {}, 'sysObject.attribute'), ' to the values it is run with: ',
    h('b', {}, 'valid'), ' (what an API accepts), ', h('b', {}, 'boundary'), ' (edges used for CEL cases) and ', h('b', {}, 'invalid'),
    ' (wrong type or shape, for validation tests). Edit it, add real ids and business values, and the generator uses your version.'));
  root.append(h('div', { class: 'toolbar' },
    h('button', { class: 'btn primary', onclick: generate }, state.valueMap ? 'Regenerate from parameters' : 'Generate from parameters'),
    h('button', { class: 'btn', onclick: async () => { const t = await pickFile(); if (t) { try { state.valueMap = JSON.parse(t); notify(); } catch (e) { alert('Not an attribute map: ' + e.message); } } } }, 'Import attribute-map.json'),
    state.valueMap ? h('button', { class: 'btn', onclick: () => download('attribute-map.json', JSON.stringify(state.valueMap, null, 2)) }, 'Export attribute-map.json') : null));
  const attrs = state.valueMap?.attributes;
  if (!attrs) { root.append(h('p', { class: 'empty' }, 'No attribute map yet.')); return; }
  const names = Object.keys(attrs);
  view.name = names.includes(view.name) ? view.name : names[0];
  const cur = attrs[view.name];
  root.append(h('div', { class: 'split' },
    h('div', { class: 'list' }, names.map((n) => h('button', { class: 'list-item' + (n === view.name ? ' active' : ''), onclick: () => { view.name = n; notify(); } },
      h('span', { class: 'mono' }, n), ' ', h('span', { class: 'chip t-' + attrs[n].type }, attrs[n].type.toLowerCase())))),
    cur ? h('div', { class: 'card' },
      h('h3', {}, view.name, ' ', h('span', { class: 'chip' }, cur.kind)),
      h('p', { class: 'muted' }, 'Sample: ', h('code', {}, JSON.stringify(cur.sample))),
      listArea('Valid values', cur.valid, (v) => { cur.valid = v; }, 'Used for happy-path request data and CEL cases.'),
      listArea('Boundary values', cur.boundary, (v) => { cur.boundary = v; }, 'Edge inputs for CEL cases (empty, zero, limits).'),
      listArea('Invalid values', cur.invalid, (v) => { cur.invalid = v; }, 'Each becomes a negative request that the API must reject.')) : null));
}
