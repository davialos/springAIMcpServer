// Workflow runtime of the generated k6 suite (springAIMcpServerCommon celfaker, ADR-0030).
// Dependency-injected so the same code runs under k6 and under the Node unit tests (http, check and sleep are passed in).
// Synchronous by design: k6's http API is synchronous.

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

export function createRuntime(deps) {
  const { http, check, sleep, workflow, apis, valid, invalid, baseUrl, env = {}, metrics = {}, log = () => {} } = deps;
  const order = topo(workflow.steps);
  const byId = new Map(order.map((s) => [s.id, s]));

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

  function header(headers, name) {
    const wanted = name.toLowerCase();
    for (const k of Object.keys(headers || {})) if (k.toLowerCase() === wanted) return headers[k];
    return undefined;
  }

  function parseBody(res) {
    try { return typeof res.json === 'function' ? res.json() : JSON.parse(res.body); } catch (e) { return undefined; }
  }

  function runStep(step, state, info, opts = {}) {
    const api = apis[step.api];
    const headers = Object.assign({ 'Content-Type': 'application/json', Accept: 'application/json' });
    for (const [k, v] of Object.entries(api.headers || {})) headers[k] = renderValue(v, state.vars, info);
    const pathVals = {};
    const query = [];
    let body = null;
    if (api.hasBody) {
      const pool = valid[api.id] || [];
      body = opts.body !== undefined ? JSON.parse(JSON.stringify(opts.body))
        : pool.length ? JSON.parse(JSON.stringify(pool[info.iter % pool.length])) : {};
    }
    for (const inj of step.inject || []) {
      const dot = inj.target.indexOf('.');
      const kind = inj.target.slice(0, dot);
      const name = inj.target.slice(dot + 1);
      if (kind === 'body' && opts.body !== undefined) continue; // a negative case carries its own, deliberately wrong body
      const value = renderValue(inj.value, state.vars, info);
      if (kind === 'path') pathVals[name] = value;
      else if (kind === 'query') query.push(encodeURIComponent(name) + '=' + encodeURIComponent(value));
      else if (kind === 'header') headers[name] = String(value);
      else if (kind === 'body' && body !== null) setPath(body, name, value);
    }
    const path = api.path.replace(/\{([A-Za-z0-9_]+)\}/g, (m, n) => {
      if (!(n in pathVals)) throw new Error('step ' + step.id + ' has no value for path parameter {' + n + '}');
      return encodeURIComponent(pathVals[n]);
    });
    const url = baseUrl + path + (query.length ? '?' + query.join('&') : '');
    const expected = opts.expectStatus || (step.expectStatus && step.expectStatus.length ? step.expectStatus : api.expectedStatus);
    const params = {
      headers,
      tags: { step: step.id, api: api.id, kind: opts.kind || 'flow' },
      responseCallback: http.expectedStatuses ? http.expectedStatuses(...expected) : undefined,
    };
    const res = http.request(api.method, url, body === null ? null : JSON.stringify(body), params);
    const statusOk = expected.includes(res.status);
    const label = opts.kind === 'negative' ? step.id + ' rejects invalid data' : step.id + ' status ok';
    let ok = check(res, { [label]: (r) => expected.includes(r.status) });
    let parsed;
    if (api.role === 'VALIDATION' && api.expectBody) {
      parsed = parseBody(res);
      ok = check(res, { [step.id + ' body matches']: () => subset(api.expectBody, parsed) }) && ok;
    }
    if (statusOk && opts.kind !== 'negative') {
      for (const ex of step.extract || []) {
        let v;
        if (ex.from === 'status') v = res.status;
        else if (ex.from.startsWith('header.')) v = header(res.headers, ex.from.slice(7));
        else { parsed = parsed === undefined ? parseBody(res) : parsed; v = getPath(parsed, ex.from.slice(5)); }
        if (v === undefined) { log('step ' + step.id + ': ' + ex.from + ' not found in the response'); ok = false; }
        else state.vars[step.id + '.' + ex.name] = v;
      }
    }
    if (step.thinkTime > 0 && opts.kind !== 'negative') sleep(step.thinkTime);
    return statusOk && ok;
  }

  function runFlow(info) {
    const state = { vars: {} };
    for (const step of order) {
      let ok;
      try { ok = runStep(step, state, info); } catch (e) { log(String(e.message)); ok = false; }
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
  function runNegative(index, info) {
    if (!selected.length) return null;
    const { step, c } = selected[index % selected.length];
    const state = { vars: {} };
    try {
      for (const pre of ancestors(step)) {
        if (!runStep(pre, state, info, { kind: 'setup' })) {
          if (metrics.setupFailed) metrics.setupFailed.add(1);
          return null;
        }
      }
      return runStep(step, state, info, { kind: 'negative', body: c.body, expectStatus: c.expectedStatus });
    } catch (e) {
      log(String(e.message));
      return null;
    }
  }

  return { runFlow, runNegative, negativeCount: selected.length, order };
}
