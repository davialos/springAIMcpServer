// Flow runtime shared by the generated k6 suite (copied to lib/runtime.js) and the dashboard's scenario runner (springAIMcpServerCommon celfaker, ADR-0030).
// Dependency-injected so the same code runs under k6, in the dashboard (scenario runner) and under the Node unit tests:
// http, check and sleep are passed in. Every I/O call is awaited, so a synchronous k6 `http` and an asynchronous browser
// adapter both work; k6 supports async scenario functions.

const PLACEHOLDER = /\{\{\s*([^}\s]+)\s*\}\}/g;
const WHOLE = /^\s*\{\{\s*([^}\s]+)\s*\}\}\s*$/;

export function segments(path) {
  const out = [];
  for (const part of String(path).split('.')) {
    const m = /^([^\[]*)((?:\[\d+\])*)$/.exec(part);
    if (!m) { out.push(part); continue; }
    if (m[1]) out.push(m[1]);
    for (const idx of m[2].matchAll(/\[(\d+)\]/g)) out.push(Number(idx[1]));
  }
  return out;
}

export function getPath(obj, path) {
  let cur = obj;
  for (const seg of segments(path)) {
    if (cur === null || cur === undefined) return undefined;
    cur = cur[seg];
  }
  return cur;
}

export function setPath(obj, path, value) {
  const segs = segments(path);
  let cur = obj;
  for (let i = 0; i < segs.length - 1; i++) {
    if (cur[segs[i]] === null || typeof cur[segs[i]] !== 'object') {
      cur[segs[i]] = typeof segs[i + 1] === 'number' ? [] : {};
    }
    cur = cur[segs[i]];
  }
  cur[segs[segs.length - 1]] = value;
}

/** True when every field of `expected` is present with an equal value in `actual` (objects recursively, arrays by index). */
export function subset(expected, actual) {
  if (expected === null || typeof expected !== 'object') return expected === actual;
  if (actual === null || typeof actual !== 'object') return false;
  return Object.keys(expected).every((k) => subset(expected[k], actual[k]));
}

export function topo(steps) {
  const byId = new Map(steps.map((s) => [s.id, s]));
  const done = new Set();
  const visiting = new Set();
  const out = [];
  const visit = (s) => {
    if (done.has(s.id)) return;
    if (visiting.has(s.id)) throw new Error('cycle through step ' + s.id);
    visiting.add(s.id);
    for (const dep of s.dependsOn || []) {
      if (!byId.has(dep)) throw new Error('step ' + s.id + ' depends on unknown step ' + dep);
      visit(byId.get(dep));
    }
    visiting.delete(s.id);
    done.add(s.id);
    out.push(s);
  };
  steps.forEach(visit);
  return out;
}

function uuid() {
  return 'xxxxxxxx-xxxx-4xxx-yxxx-xxxxxxxxxxxx'.replace(/[xy]/g, (c) => {
    const r = (Math.random() * 16) | 0;
    return (c === 'x' ? r : (r & 0x3) | 0x8).toString(16);
  });
}

/** Compares a response value with an assertion's expected text. */
export function compare(op, actual, expected) {
  const text = (v) => (v !== null && typeof v === 'object' ? JSON.stringify(v) : String(v));
  switch (op) {
    case 'exists': return actual !== undefined && actual !== null;
    case 'absent': return actual === undefined || actual === null;
    case '==': return actual !== undefined && (text(actual) === expected || (typeof actual === 'number' && Number(expected) === actual));
    case '!=': return actual === undefined || (text(actual) !== expected && !(typeof actual === 'number' && Number(expected) === actual));
    case 'contains': return actual !== undefined && actual !== null && text(actual).includes(expected);
    case 'matches': try { return actual !== undefined && new RegExp(expected).test(text(actual)); } catch (e) { return false; }
    case '>': return Number(actual) > Number(expected);
    case '>=': return Number(actual) >= Number(expected);
    case '<': return Number(actual) < Number(expected);
    case '<=': return Number(actual) <= Number(expected);
    default: return false;
  }
}

export function createRuntime(deps) {
  const { http, check, sleep, workflow, apis, valid, invalid, baseUrl, env = {}, metrics = {}, log = () => {}, trace = null } = deps;
  const order = topo(workflow.steps);
  const byId = new Map(order.map((s) => [s.id, s]));
  const clone = (v) => JSON.parse(JSON.stringify(v));

  function ancestors(step) {
    const seen = new Set();
    const todo = [...(step.dependsOn || [])];
    while (todo.length) {
      const id = todo.pop();
      if (!seen.has(id)) { seen.add(id); todo.push(...(byId.get(id).dependsOn || [])); }
    }
    return order.filter((s) => seen.has(s.id));
  }

  function resolve(ref, vars, info) {
    if (ref === 'iter') return info.iter;
    if (ref === 'vu') return info.vu;
    if (ref === 'uuid') return uuid();
    if (ref === 'timestamp') return new Date().toISOString();
    if (ref.startsWith('env.')) return env[ref.slice(4)] === undefined ? '' : env[ref.slice(4)];
    if (!(ref in vars)) throw new Error('unresolved variable {{' + ref + '}}');
    return vars[ref];
  }

  /** Renders a template; a template that is exactly one placeholder keeps the variable's type. */
  function renderValue(tpl, vars, info) {
    const whole = WHOLE.exec(String(tpl));
    if (whole) return resolve(whole[1], vars, info);
    return String(tpl).replace(PLACEHOLDER, (m, ref) => {
      const v = resolve(ref, vars, info);
      return v !== null && typeof v === 'object' ? JSON.stringify(v) : String(v);
    });
  }

  function renderDeep(node, vars, info) {
    if (typeof node === 'string') return node.includes('{{') ? renderValue(node, vars, info) : node;
    if (Array.isArray(node)) return node.map((e) => renderDeep(e, vars, info));
    if (node !== null && typeof node === 'object') return Object.fromEntries(Object.entries(node).map(([k, v]) => [k, renderDeep(v, vars, info)]));
    return node;
  }

  function header(headers, name) {
    const wanted = name.toLowerCase();
    for (const k of Object.keys(headers || {})) if (k.toLowerCase() === wanted) return headers[k];
    return undefined;
  }

  function parseBody(res) {
    try { return typeof res.json === 'function' ? res.json() : JSON.parse(res.body); } catch (e) { return undefined; }
  }

  /** The value a `from` expression (`status`, `header.X`, `body.a.b[0]`) points to in a response. */
  function pick(from, res, getParsed) {
    if (from === 'status') return res.status;
    if (from.startsWith('header.')) return header(res.headers, from.slice(7));
    return getPath(getParsed(), from.slice(5));
  }

  async function runStep(step, state, info, opts = {}) {
    const api = apis[step.api];
    const kind = opts.kind || 'flow';
    const checks = [];
    const chk = (res, name, fn) => { const ok = check(res, { [name]: fn }); checks.push({ name, ok }); return ok; };
    const headers = Object.assign({ 'Content-Type': 'application/json', Accept: 'application/json' });
    for (const [k, v] of Object.entries(api.headers || {})) headers[k] = renderValue(v, state.vars, info);
    const pathVals = {};
    const query = [];
    let body = null;
    if (api.hasBody) {
      if (opts.body !== undefined) {
        body = clone(opts.body);
      } else if (step.body) {
        body = renderDeep(clone(step.body), state.vars, info);
      } else if (step.invalidCase) {
        const found = (invalid[api.id] || []).find((c) => c.reason === step.invalidCase);
        if (!found) throw new Error('step ' + step.id + ': no generated invalid case "' + step.invalidCase + '" for ' + api.id);
        body = clone(found.body);
      } else {
        const pool = valid[api.id] || [];
        body = pool.length ? clone(pool[info.iter % pool.length]) : {};
      }
    }
    for (const inj of step.inject || []) {
      const dot = inj.target.indexOf('.');
      const kindOf = inj.target.slice(0, dot);
      const name = inj.target.slice(dot + 1);
      if (kindOf === 'body' && opts.body !== undefined) continue; // a negative case carries its own, deliberately wrong body
      const value = renderValue(inj.value, state.vars, info);
      if (kindOf === 'path') pathVals[name] = value;
      else if (kindOf === 'query') query.push(encodeURIComponent(name) + '=' + encodeURIComponent(value));
      else if (kindOf === 'header') headers[name] = String(value);
      else if (kindOf === 'body' && body !== null) setPath(body, name, value);
    }
    const path = api.path.replace(/\{([A-Za-z0-9_]+)\}/g, (m, n) => {
      if (!(n in pathVals)) throw new Error('step ' + step.id + ' has no value for path parameter {' + n + '}');
      return encodeURIComponent(pathVals[n]);
    });
    const url = baseUrl + path + (query.length ? (path.includes('?') ? '&' : '?') + query.join('&') : '');
    const expected = opts.expectStatus || (step.expectStatus && step.expectStatus.length ? step.expectStatus : api.expectedStatus);
    const params = {
      headers,
      tags: { step: step.id, api: api.id, kind },
      responseCallback: http.expectedStatuses ? http.expectedStatuses(...expected) : undefined,
    };
    const request = { method: api.method, url, headers, body };
    if (trace) trace({ phase: 'start', step: step.id, api: api.id, kind, request });
    const started = Date.now();
    const res = await http.request(api.method, url, body === null ? null : JSON.stringify(body), params);
    const statusOk = expected.includes(res.status);
    const label = kind === 'negative' ? step.id + ' rejects invalid data' : step.id + ' status ok';
    let ok = chk(res, label, (r) => expected.includes(r.status));
    let parsed;
    let parsedDone = false;
    const getParsed = () => { if (!parsedDone) { parsed = parseBody(res); parsedDone = true; } return parsed; };
    if (api.role === 'VALIDATION' && api.expectBody) {
      ok = chk(res, step.id + ' body matches', () => subset(api.expectBody, getParsed())) && ok;
    }
    for (const a of step.assertions || []) {
      let want = a.value;
      try { want = a.op === 'exists' || a.op === 'absent' ? '' : String(renderValue(a.value, state.vars, info)); } catch (e) { log(String(e.message)); }
      ok = chk(res, step.id + ' assert ' + a.from + ' ' + a.op, () => compare(a.op, pick(a.from, res, getParsed), want)) && ok;
    }
    const extracted = {};
    if (statusOk && kind !== 'negative') {
      for (const ex of step.extract || []) {
        const v = pick(ex.from, res, getParsed);
        if (v === undefined) { log('step ' + step.id + ': ' + ex.from + ' not found in the response'); checks.push({ name: step.id + ' extract ' + ex.name, ok: false }); ok = false; }
        else { state.vars[step.id + '.' + ex.name] = v; extracted[ex.name] = v; }
      }
    }
    const pass = statusOk && ok;
    if (trace) {
      const text = typeof res.body === 'string' ? res.body : '';
      trace({ phase: 'end', step: step.id, api: api.id, kind, request, ok: pass, checks, extracted, millis: res.millis ?? (Date.now() - started),
        response: { status: res.status, headers: res.headers, body: text.slice(0, 4000) } });
    }
    if (step.thinkTime > 0 && kind !== 'negative') await sleep(step.thinkTime);
    return pass;
  }

  async function runFlow(info) {
    const state = { vars: {} };
    for (const step of order) {
      let ok;
      try { ok = await runStep(step, state, info); } catch (e) {
        log(String(e.message));
        if (trace) trace({ phase: 'end', step: step.id, api: step.api, kind: 'flow', ok: false, checks: [{ name: String(e.message), ok: false }], extracted: {}, error: String(e.message) });
        ok = false;
      }
      if (!ok) return false;
    }
    return true;
  }

  // Negative cases: one entry per (step, invalid body), thinned evenly to the workflow's `negatives` limit.
  const negatives = [];
  for (const step of order) {
    const api = apis[step.api];
    for (const c of (api.hasBody && invalid[api.id]) || []) negatives.push({ step, c });
  }
  const cap = workflow.load && workflow.load.negatives !== undefined ? workflow.load.negatives : 100;
  const stride = cap > 0 && negatives.length > cap ? Math.ceil(negatives.length / cap) : 1;
  const selected = cap > 0 ? negatives.filter((_, i) => i % stride === 0).slice(0, cap) : [];

  /** Runs the setup steps with valid data, then sends the invalid case and expects a rejection. */
  async function runNegative(index, info) {
    if (!selected.length) return null;
    const { step, c } = selected[index % selected.length];
    const state = { vars: {} };
    try {
      for (const pre of ancestors(step)) {
        if (!(await runStep(pre, state, info, { kind: 'setup' }))) {
          if (metrics.setupFailed) metrics.setupFailed.add(1);
          return null;
        }
      }
      return await runStep(step, state, info, { kind: 'negative', body: c.body, expectStatus: c.expectedStatus });
    } catch (e) {
      log(String(e.message));
      return null;
    }
  }

  return { runFlow, runNegative, negativeCount: selected.length, order };
}
