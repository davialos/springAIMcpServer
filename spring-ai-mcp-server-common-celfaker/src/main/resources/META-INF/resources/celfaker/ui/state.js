import { newWorkflow, normalizeWorkflow, prune } from './flow-model.js';

const KEY = 'saimcp-celfaker-v1';
const listeners = new Set();

export const state = {
  contract: { name: 'my-api', baseUrl: 'http://localhost:8080', apis: [] },
  scenarios: [], // named workflows, each one a test scenario; `workflow` is the one on the canvas
  workflow: newWorkflow('Scenario 1'),
  candidates: {}, // api id -> analyzed candidates
  skipped: {},
  valueMap: null, // attribute map (editable JSON)
  expressions: [],
  rejected: [],
  settings: { seed: 42, valid: 30, cases: 12, combined: 30, maxPerParameter: 0 },
  ui: { tab: 'apis', api: null, step: null },
};
state.scenarios = [state.workflow];

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
    localStorage.setItem(KEY, JSON.stringify({ contract: state.contract, scenarios: state.scenarios, active: Math.max(0, state.scenarios.indexOf(state.workflow)), valueMap: state.valueMap, settings: state.settings }));
  } catch (e) { /* storage unavailable: the page works without it */ }
}

export function restore() {
  try {
    const saved = JSON.parse(localStorage.getItem(KEY) ?? 'null');
    if (saved) {
      const { scenarios, active, workflow, ...rest } = saved;
      Object.assign(state, rest);
      state.scenarios = (scenarios?.length ? scenarios : [workflow ?? newWorkflow('Scenario 1')]).map(normalizeWorkflow);
      state.workflow = state.scenarios[Math.min(active ?? 0, state.scenarios.length - 1)];
    }
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

/** Makes scenario `i` the one on the canvas. */
export function selectScenario(i) {
  state.workflow = state.scenarios[i];
  state.ui.step = null;
}

/** Drops steps whose API no longer exists from every scenario. */
export function pruneAll() {
  const ids = state.contract.apis.map((a) => a.id);
  state.scenarios.forEach((w) => prune(w, ids));
}
