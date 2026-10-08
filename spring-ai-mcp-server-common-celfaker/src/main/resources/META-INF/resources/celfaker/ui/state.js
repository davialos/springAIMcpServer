import { newWorkflow } from './flow-model.js';

const KEY = 'saimcp-celfaker-v1';
const listeners = new Set();

export const state = {
  contract: { name: 'my-api', baseUrl: 'http://localhost:8080', apis: [] },
  workflow: newWorkflow('workflow'),
  candidates: {}, // api id -> analyzed candidates
  skipped: {},
  valueMap: null, // attribute map (editable JSON)
  expressions: [],
  rejected: [],
  settings: { seed: 42, valid: 30, cases: 12, combined: 30, maxPerParameter: 0 },
  ui: { tab: 'apis', api: null, step: null },
};

export function subscribe(fn) {
  listeners.add(fn);
  return () => listeners.delete(fn);
}

export function notify() {
  save();
  listeners.forEach((fn) => fn(state));
}

export function save() {
  try {
    localStorage.setItem(KEY, JSON.stringify({ contract: state.contract, workflow: state.workflow, valueMap: state.valueMap, settings: state.settings }));
  } catch (e) { /* storage unavailable: the page works without it */ }
}

export function restore() {
  try {
    const saved = JSON.parse(localStorage.getItem(KEY) ?? 'null');
    if (saved) Object.assign(state, saved);
  } catch (e) { /* ignore a corrupt or blocked store */ }
}

export function apiById(id) {
  return state.contract.apis.find((a) => a.id === id);
}

/** Selected candidates of every body API, de-duplicated by CEL name. */
export function selectedCandidates() {
  const byName = new Map();
  for (const api of state.contract.apis) {
    const selected = new Set(api.selectedPaths ?? []);
    for (const c of state.candidates[api.id] ?? []) {
      if ((selected.size === 0 || selected.has(c.path)) && !byName.has(c.name)) byName.set(c.name, c);
    }
  }
  return [...byName.values()].map((c) => ({ path: c.path, objectCode: c.objectCode, attributeCode: c.attributeCode, type: c.type, sample: c.sample }));
}
