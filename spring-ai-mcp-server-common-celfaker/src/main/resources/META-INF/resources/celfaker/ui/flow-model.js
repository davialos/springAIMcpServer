// Pure workflow-graph logic of the flow designer (no DOM). Workflow shape = Java `Workflow` (ADR-0030):
// { name, steps: [{ id, api, dependsOn, extract:[{name,from}], inject:[{target,value}], expectStatus, thinkTime, x, y }], load }

export function newWorkflow(name = 'workflow') {
  return { name, steps: [], load: { profile: 'smoke', vus: 5, duration: '30s', negatives: 100 } };
}

/** Fills in the fields older saved workflows lack, so the rest of the UI can rely on them. */
export function normalizeStep(s) {
  s.dependsOn ??= []; s.extract ??= []; s.inject ??= []; s.expectStatus ??= []; s.assertions ??= [];
  s.body ??= null; s.invalidCase ??= ''; s.thinkTime ??= 0; s.x ??= 40; s.y ??= 40;
  return s;
}

export function normalizeWorkflow(wf) {
  wf.name ||= 'scenario';
  wf.load ??= { profile: 'smoke', vus: 5, duration: '30s', negatives: 100 };
  wf.steps = (wf.steps ?? []).map(normalizeStep);
  return wf;
}

/** A scenario name not used yet: "name", "name 2", ... */
export function uniqueName(list, base) {
  const names = new Set(list.map((w) => w.name));
  if (!names.has(base)) return base;
  let n = 2;
  while (names.has(base + ' ' + n)) n++;
  return base + ' ' + n;
}

export function duplicateScenario(list, wf) {
  const copy = JSON.parse(JSON.stringify(wf));
  copy.name = uniqueName(list, wf.name + ' copy');
  return copy;
}

/** Operators of a response assertion, with the label shown in the inspector. */
export const ASSERT_OPS = [['==', 'equals'], ['!=', 'differs from'], ['exists', 'exists'], ['absent', 'is absent'], ['contains', 'contains'],
  ['>', 'is greater than'], ['>=', 'is at least'], ['<', 'is less than'], ['<=', 'is at most'], ['matches', 'matches regex']];

export function findStep(wf, id) {
  return wf.steps.find((s) => s.id === id);
}

export function newStepId(wf, apiId) {
  const base = apiId.replace(/[^A-Za-z0-9_]/g, '_').replace(/^([^A-Za-z])/, 's$1');
  if (!findStep(wf, base)) return base;
  let n = 2;
  while (findStep(wf, base + n)) n++;
  return base + n;
}

export function addStep(wf, apiId, x = 40, y = 40) {
  const step = normalizeStep({ id: newStepId(wf, apiId), api: apiId, thinkTime: 0, x, y });
  wf.steps.push(step);
  return step;
}

export function removeStep(wf, id) {
  wf.steps = wf.steps.filter((s) => s.id !== id);
  for (const s of wf.steps) {
    s.dependsOn = s.dependsOn.filter((d) => d !== id);
    s.inject = s.inject.filter((i) => !String(i.value).includes('{{' + id + '.'));
  }
}

/** All steps that must run before `id` (transitive). */
export function ancestors(wf, id) {
  const seen = new Set();
  const todo = [...(findStep(wf, id)?.dependsOn ?? [])];
  while (todo.length) {
    const cur = todo.pop();
    if (seen.has(cur)) continue;
    seen.add(cur);
    todo.push(...(findStep(wf, cur)?.dependsOn ?? []));
  }
  return seen;
}

export function wouldCycle(wf, from, to) {
  return from === to || ancestors(wf, from).has(to);
}

/** Adds the edge from -> to (`to` runs after `from`). */
export function connect(wf, from, to) {
  if (!findStep(wf, from) || !findStep(wf, to)) return { ok: false, reason: 'unknown step' };
  if (wouldCycle(wf, from, to)) return { ok: false, reason: 'that connection would create a cycle' };
  const target = findStep(wf, to);
  if (target.dependsOn.includes(from)) return { ok: false, reason: 'already connected' };
  target.dependsOn.push(from);
  return { ok: true };
}

export function disconnect(wf, from, to) {
  const target = findStep(wf, to);
  if (target) target.dependsOn = target.dependsOn.filter((d) => d !== from);
}

export function renameStep(wf, oldId, newId) {
  if (!/^[A-Za-z][A-Za-z0-9_]*$/.test(newId)) return { ok: false, reason: 'use letters, digits and _, starting with a letter' };
  if (newId !== oldId && findStep(wf, newId)) return { ok: false, reason: 'a step with that id exists' };
  for (const s of wf.steps) {
    if (s.id === oldId) s.id = newId;
    s.dependsOn = s.dependsOn.map((d) => (d === oldId ? newId : d));
    for (const i of s.inject) i.value = String(i.value).split('{{' + oldId + '.').join('{{' + newId + '.');
  }
  return { ok: true };
}

/** Variables a step can read: those extracted by its ancestors, as `{{step.var}}` references. */
export function availableVariables(wf, id) {
  const out = [];
  for (const a of ancestors(wf, id)) {
    for (const e of findStep(wf, a)?.extract ?? []) out.push({ ref: '{{' + a + '.' + e.name + '}}', step: a, name: e.name });
  }
  return out;
}

export function pathParams(api) {
  return [...String(api?.path ?? '').matchAll(/\{([A-Za-z0-9_]+)\}/g)].map((m) => m[1]);
}

/** Leaf paths of a response example that make sensible variables. */
export function suggestExtracts(api) {
  const out = [];
  const walk = (node, prefix, depth) => {
    if (node === null || typeof node !== 'object' || depth > 3) return;
    if (Array.isArray(node)) { if (node.length && typeof node[0] === 'object') walk(node[0], prefix + '[0]', depth + 1); return; }
    for (const [k, v] of Object.entries(node)) {
      const path = prefix ? prefix + '.' + k : k;
      if (v !== null && typeof v === 'object') walk(v, path, depth + 1);
      else out.push({ name: k.replace(/[^A-Za-z0-9_]/g, '_'), from: 'body.' + path });
    }
  };
  walk(api?.responseExample, '', 0);
  return out;
}

/**
 * Wires path parameters of a step from its ancestors' variables: a variable with the same name first, else `id`, else
 * the nearest ancestor's first variable. When no ancestor extracts anything usable, the obvious response field
 * (same name as the parameter, or `id`) of an ancestor's response example is added as an extract. Returns how many
 * injections were added.
 */
export function autoWire(wf, apis, id) {
  const step = findStep(wf, id);
  const api = apis.find((a) => a.id === step.api);
  let added = 0;
  for (const p of pathParams(api)) {
    if (step.inject.some((i) => i.target === 'path.' + p)) continue;
    let vars = availableVariables(wf, id);
    let pick = vars.find((v) => v.name === p) ?? vars.find((v) => v.name === 'id');
    if (!pick) {
      // nearest ancestors first (the ones that run last)
      const order = [...ancestors(wf, id)].map((a) => findStep(wf, a)).reverse();
      for (const anc of order) {
        const suggestion = suggestExtracts(apis.find((a) => a.id === anc.api)).find((s) => s.name === p || s.name === 'id');
        if (suggestion) {
          if (!anc.extract.some((e) => e.name === suggestion.name)) anc.extract.push({ ...suggestion });
          pick = { ref: '{{' + anc.id + '.' + suggestion.name + '}}' };
          break;
        }
      }
    }
    pick = pick ?? vars[0];
    if (pick) { step.inject.push({ target: 'path.' + p, value: pick.ref }); added++; }
  }
  return added;
}

/** Layered auto layout: x by dependency depth, y by order inside a layer. */
export function layout(wf, width = 230, height = 120) {
  const depth = new Map();
  const depthOf = (s) => {
    if (depth.has(s.id)) return depth.get(s.id);
    depth.set(s.id, 0);
    const d = s.dependsOn.length ? 1 + Math.max(...s.dependsOn.map((id) => depthOf(findStep(wf, id)))) : 0;
    depth.set(s.id, d);
    return d;
  };
  const rows = new Map();
  for (const s of wf.steps) {
    const d = depthOf(s);
    const row = rows.get(d) ?? 0;
    rows.set(d, row + 1);
    s.x = 40 + d * width;
    s.y = 40 + row * height;
  }
}

/** Cleans a workflow for sending to the generator (drops nothing the server needs, rounds positions). */
export function toWorkflow(wf) {
  return {
    name: wf.name,
    load: wf.load,
    steps: wf.steps.map((s) => ({
      id: s.id, api: s.api, dependsOn: [...s.dependsOn],
      extract: s.extract.filter((e) => e.name && e.from),
      inject: s.inject.filter((i) => i.target),
      expectStatus: s.expectStatus.map(Number).filter(Number.isFinite),
      assertions: (s.assertions ?? []).filter((a) => a.from && a.op).map((a) => ({ from: a.from, op: a.op, value: a.value ?? '' })),
      body: s.body && typeof s.body === 'object' ? s.body : null,
      invalidCase: s.invalidCase || '',
      thinkTime: Number(s.thinkTime) || 0, x: Math.round(s.x), y: Math.round(s.y),
    })),
  };
}

/** Drops steps whose API no longer exists and edges to missing steps. */
export function prune(wf, apiIds) {
  wf.steps = wf.steps.filter((s) => apiIds.includes(s.api));
  const ids = new Set(wf.steps.map((s) => s.id));
  for (const s of wf.steps) s.dependsOn = s.dependsOn.filter((d) => ids.has(d));
}
