import { clear, h } from './dom.js';
import { notify, state } from './state.js';
import { refreshCandidates } from './view-apis.js';

const TYPE_HINT = {
  STRING: 'string', INT: 'int', DOUBLE: 'double', BOOL: 'bool', TIMESTAMP: 'timestamp', DURATION: 'duration',
  LIST_STRING: 'list(string)', LIST_INT: 'list(int)', LIST_DOUBLE: 'list(double)', MAP: 'map', ANY: 'dyn',
};

function show(v) {
  const t = JSON.stringify(v);
  return t && t.length > 60 ? t.slice(0, 57) + '…' : t;
}

export function render(root) {
  clear(root);
  root.append(h('p', { class: 'lead' }, 'Every value of an API payload can become a parameter-library entry: ',
    h('code', {}, 'sysObject.sysObjectAttribute'), ' with a CEL data type. Tick the values to use in expressions, rules and generated data.'));
  const bodyApis = state.contract.apis.filter((a) => a.role !== 'VALIDATION');
  if (!bodyApis.length) { root.append(h('p', { class: 'empty' }, 'No API with a request body yet.')); return; }
  for (const spec of bodyApis) {
    const cands = state.candidates[spec.id] ?? [];
    const selected = new Set(spec.selectedPaths ?? []);
    const all = selected.size === 0;
    const toggle = (path, on) => {
      const cur = new Set(all ? cands.map((c) => c.path) : spec.selectedPaths);
      if (on) cur.add(path); else cur.delete(path);
      spec.selectedPaths = cur.size === cands.length ? [] : [...cur];
      notify();
    };
    root.append(h('section', { class: 'card' },
      h('h3', {}, spec.name || spec.id, ' ', h('span', { class: 'badge m-' + spec.method }, spec.method), ' ', h('code', {}, spec.path)),
      h('div', { class: 'toolbar' },
        h('button', { class: 'btn', onclick: () => { spec.selectedPaths = []; notify(); } }, 'Select all'),
        h('button', { class: 'btn', onclick: () => { spec.selectedPaths = cands.length ? [cands[0].path] : []; notify(); } }, 'Select none'),
        h('label', { class: 'inline' }, 'MAP paths ',
          h('input', { class: 'mono', placeholder: 'attrs, meta.extra', value: (spec.mapPaths ?? []).join(', '),
            onchange: async (e) => { spec.mapPaths = e.target.value.split(',').map((s) => s.trim()).filter(Boolean); await refreshCandidates(spec); notify(); } }))),
      cands.length ? h('table', {},
        h('thead', {}, h('tr', {}, h('th', {}, 'Use'), h('th', {}, 'Payload path'), h('th', {}, 'Sys object'), h('th', {}, 'Attribute'), h('th', {}, 'CEL type'), h('th', {}, 'Sample'))),
        h('tbody', {}, cands.map((c) => h('tr', {},
          h('td', {}, h('input', { type: 'checkbox', checked: all || selected.has(c.path), 'aria-label': 'use ' + c.name, onchange: (e) => toggle(c.path, e.target.checked) })),
          h('td', {}, h('code', {}, c.path)),
          h('td', {}, c.objectCode), h('td', {}, c.attributeCode),
          h('td', {}, h('span', { class: 'chip t-' + c.type }, TYPE_HINT[c.type] ?? c.type)),
          h('td', { class: 'mono' }, show(c.sample)))))) : h('p', { class: 'empty' }, 'No values found — add a request example on the APIs tab.'),
      (state.skipped[spec.id] ?? []).map((s) => h('p', { class: 'warn' }, s))));
  }
}
