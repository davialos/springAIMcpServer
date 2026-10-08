import { api as server } from './api.js';
import { clear, h } from './dom.js';
import { notify, save, selectedCandidates, state } from './state.js';

const CATEGORIES = ['COMPARISON', 'ARITHMETIC', 'LOGICAL', 'MEMBERSHIP', 'STRING_FUNCTION', 'REGEX', 'TEMPORAL', 'MACRO', 'ACCESS',
  'CONVERSION', 'CONDITIONAL', 'TYPE', 'CROSS_PARAMETER'];
const view = { picked: new Set(), filter: '', category: '', busy: false, error: '' };

export async function ensureValueMap() {
  const cands = selectedCandidates();
  if (!state.valueMap) state.valueMap = await server.attributeMap(cands, Number(state.settings.seed));
  return state.valueMap;
}

async function generate() {
  view.busy = true; view.error = '';
  notify();
  try {
    const cands = selectedCandidates();
    const res = await server.expressions(cands, state.valueMap, Number(state.settings.seed), {
      categories: [...view.picked], maxPerParameter: Number(state.settings.maxPerParameter) || 0, combined: Number(state.settings.combined) || 0,
    });
    state.expressions = res.expressions;
    state.rejected = res.rejected;
    state.valueMap = res.attributeMap;
  } catch (e) {
    view.error = String(e.message);
  }
  view.busy = false;
  notify();
}

async function showCases(expr) {
  const dlg = h('dialog', {}, h('p', {}, 'Evaluating with the CEL runtime…'));
  document.body.append(dlg);
  dlg.showModal();
  try {
    const res = await server.cases(selectedCandidates(), state.valueMap, Number(state.settings.seed), expr.expression, 40);
    const names = [...new Set(res.cases.flatMap((c) => Object.keys(c.inputs)))];
    dlg.replaceChildren(
      h('h3', {}, 'Input combinations'), h('code', { class: 'block' }, expr.expression),
      h('div', { class: 'scroll' }, h('table', {},
        h('thead', {}, h('tr', {}, names.map((n) => h('th', {}, n)), h('th', {}, 'Result'))),
        h('tbody', {}, res.cases.map((c) => h('tr', {}, names.map((n) => h('td', { class: 'mono' }, JSON.stringify(c.inputs[n]) ?? '')),
          h('td', {}, h('span', { class: 'chip r-' + c.expected, title: c.detail ?? '' }, c.expected))))))),
      h('div', { class: 'toolbar' }, h('button', { class: 'btn', onclick: () => dlg.close() }, 'Close')));
  } catch (e) {
    dlg.replaceChildren(h('p', { class: 'error' }, String(e.message)), h('button', { class: 'btn', onclick: () => dlg.close() }, 'Close'));
  }
  dlg.addEventListener('close', () => dlg.remove());
}

function addRule(expr, apiId) {
  const spec = state.contract.apis.find((a) => a.id === apiId);
  if (!spec) return;
  spec.rules = [...new Set([...(spec.rules ?? []), expr.expression])];
  save();
  notify();
}

export function render(root) {
  clear(root);
  const cands = selectedCandidates();
  const s = state.settings;
  const bodyApis = state.contract.apis.filter((a) => a.role !== 'VALIDATION');
  root.append(
    h('p', { class: 'lead' }, 'Fake valid CEL for the selected parameters: every operator and macro that fits each data type, plus cross-parameter combinations. Each expression is compiled by the real CEL checker before it is listed.'),
    h('div', { class: 'card' },
      h('div', { class: 'toolbar' },
        h('label', { class: 'inline' }, 'Seed ', h('input', { type: 'number', value: s.seed, onchange: (e) => { s.seed = Number(e.target.value); state.valueMap = null; save(); } })),
        h('label', { class: 'inline' }, 'Max per parameter (0 = all) ', h('input', { type: 'number', min: 0, value: s.maxPerParameter, onchange: (e) => { s.maxPerParameter = Number(e.target.value); save(); } })),
        h('label', { class: 'inline' }, 'Cross-parameter ', h('input', { type: 'number', min: 0, value: s.combined, onchange: (e) => { s.combined = Number(e.target.value); save(); } }))),
      h('div', { class: 'chips', role: 'group', 'aria-label': 'Categories' }, CATEGORIES.map((c) => h('button', {
        class: 'chip toggle' + (view.picked.has(c) ? ' on' : ''), 'aria-pressed': view.picked.has(c),
        onclick: () => { view.picked.has(c) ? view.picked.delete(c) : view.picked.add(c); notify(); } }, c.toLowerCase().replace('_', ' ')))),
      h('div', { class: 'toolbar' },
        h('button', { class: 'btn primary', disabled: view.busy || !cands.length, onclick: generate }, view.busy ? 'Generating…' : 'Generate expressions'),
        h('span', { class: 'muted' }, cands.length + ' parameters selected' + (view.picked.size ? '' : ' · all categories'))),
      view.error ? h('p', { class: 'error' }, view.error) : null));

  if (!state.expressions.length) return;
  const q = view.filter.toLowerCase();
  const rows = state.expressions.filter((e) => (!view.category || e.category === view.category) && (!q || e.expression.toLowerCase().includes(q) || e.description.toLowerCase().includes(q)));
  const counts = {};
  state.expressions.forEach((e) => { counts[e.category] = (counts[e.category] ?? 0) + 1; });
  root.append(h('div', { class: 'card' },
    h('div', { class: 'toolbar' },
      h('input', { type: 'search', placeholder: 'Filter expressions', value: view.filter, 'aria-label': 'Filter expressions', oninput: (e) => { view.filter = e.target.value; renderRows(); } }),
      h('select', { 'aria-label': 'Category', onchange: (e) => { view.category = e.target.value; renderRows(); } },
        h('option', { value: '' }, 'All categories (' + state.expressions.length + ')'),
        Object.entries(counts).map(([c, n]) => h('option', { value: c, selected: c === view.category }, c.toLowerCase() + ' (' + n + ')'))),
      h('span', { class: 'muted' }, state.rejected.length + ' candidates rejected by the CEL checker')),
    h('div', { class: 'scroll tall', id: 'expr-rows' })));
  const target = root.querySelector('#expr-rows');
  function renderRows() {
    const q2 = view.filter.toLowerCase();
    const shown = state.expressions.filter((e) => (!view.category || e.category === view.category) && (!q2 || e.expression.toLowerCase().includes(q2) || e.description.toLowerCase().includes(q2))).slice(0, 400);
    let pick = bodyApis[0]?.id;
    target.replaceChildren(h('table', {},
      h('thead', {}, h('tr', {}, h('th', {}, 'Category'), h('th', {}, 'CEL expression'), h('th', {}, 'Meaning'), h('th', {}, ''))),
      h('tbody', {}, shown.map((e) => h('tr', {},
        h('td', {}, h('span', { class: 'chip' }, e.category.toLowerCase().replace('_', ' '))),
        h('td', { class: 'mono wrap' }, e.expression), h('td', {}, e.description),
        h('td', { class: 'nowrap' },
          h('button', { class: 'btn sm', onclick: () => showCases(e) }, 'Inputs → results'), ' ',
          bodyApis.length ? h('select', { class: 'sm', 'aria-label': 'Rule target', onchange: (ev) => { pick = ev.target.value; } }, bodyApis.map((a) => h('option', { value: a.id }, a.id))) : null, ' ',
          bodyApis.length ? h('button', { class: 'btn sm', title: 'Add as rule to the chosen API', onclick: () => addRule(e, pick ?? bodyApis[0].id) }, '+ rule') : null))))),
      rows.length > 400 ? h('p', { class: 'muted' }, 'Showing 400 of ' + rows.length + ' — filter to narrow.') : null);
  }
  renderRows();
}
