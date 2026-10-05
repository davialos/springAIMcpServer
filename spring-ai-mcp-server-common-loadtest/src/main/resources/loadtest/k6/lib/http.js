// Request execution: URL/query/headers/body assembly, auth, status checks, think time and safety rails.
import http from 'k6/http';
import { check, sleep } from 'k6';
import * as data from './data.js';
import { parseMode } from './modes.js';
import * as validate from './validate.js';
import * as authn from './auth.js';

const DEFAULT_EXPECTED = { GET: [200], HEAD: [200], POST: [200, 201, 202], PUT: [200, 201, 204], PATCH: [200, 204], DELETE: [200, 202, 204], OPTIONS: [200, 204] };

function baseUrl(config) {
  return (__ENV.BASE_URL || config.baseUrl || 'http://localhost:8080').replace(/\/+$/, '');
}

/**
 * Resolves the APIs this run uses (enabled in config, READ_ONLY, API filter), with their expected statuses.
 * Throws when BASE_URL looks like production and ALLOW_PROD is not set (safety.blockedHostPattern).
 */
export function prepare(config, modules, seedSteps, responseSchemas) {
  const creates = {};
  for (const step of seedSteps || []) creates[step.api] = step;
  const url = baseUrl(config);
  const safety = config.safety || {};
  if (safety.blockedHostPattern && __ENV.ALLOW_PROD !== 'true') {
    const host = url.replace(/^[a-z]+:\/\//i, '').split(/[/:]/)[0];
    if (new RegExp(safety.blockedHostPattern, 'i').test(host)) {
      throw new Error(`BASE_URL host "${host}" matches safety.blockedHostPattern; refusing to load-test it. Set ALLOW_PROD=true if this is intended.`);
    }
  }
  const readOnly = __ENV.READ_ONLY === 'true' || safety.readOnly === true;
  const only = __ENV.API ? __ENV.API.split(',').map((s) => s.trim()).filter(Boolean) : null;
  const { mixed } = parseMode(__ENV.MODE);
  const dataMode = data.dataMode();
  const accept4xx = ((config.data || {}).acceptClientErrorsIn || []).indexOf(dataMode) >= 0;
  const clientErrors = (config.data || {}).clientErrorStatuses || [400, 404, 409, 422];
  const apis = [];
  const byId = {};
  for (const mod of modules) {
    const meta = mod.API;
    const c = (config.apis || {})[meta.id] || {};
    let expected = c.expectedStatuses || DEFAULT_EXPECTED[meta.method] || [200];
    if (accept4xx) expected = expected.concat(clientErrors);
    const api = {
      id: meta.id,
      method: meta.method,
      path: meta.path,
      name: `${meta.method} ${meta.path}`,
      weight: c.weight !== undefined ? c.weight : 1,
      expected,
      callback: http.expectedStatuses(...expected),
      creates: creates[meta.id], // the table this API inserts into, if it is a create endpoint
      graphql: meta.graphql === true,
      safe: meta.safe === true, // a read although it is a POST (GraphQL query)
      bodyType: meta.bodyType, // undefined = JSON; form | multipart
      authRole: c.auth, // a role, or "none" for a public endpoint (see lib/auth.js)
      responseSchema: (responseSchemas || {})[meta.id], // what a successful response looks like, if known
      mod,
    };
    byId[api.id] = api;
    if (only) {
      if (only.indexOf(api.id) < 0) continue; // an explicit API list overrides "enabled"
    } else if (c.enabled === false) {
      continue;
    }
    if (readOnly && api.method !== 'GET' && api.method !== 'HEAD' && !api.safe) continue;
    if (mixed && api.weight <= 0) continue;
    apis.push(api);
  }
  if (only) {
    for (const id of only) if (!byId[id]) throw new Error(`API=${id}: no such API (see loadtest.config.json → apis)`);
  }
  return { apis, byId, baseUrl: url, config, dataMode, readOnly, validation: validationSettings(config) };
}

/** config.validation, with the VALIDATE_RESPONSES / VALIDATE_SAMPLE overrides: how responses and reads are checked. */
function validationSettings(config) {
  const cfg = config.validation || {};
  const mode = __ENV.VALIDATE_RESPONSES || cfg.responses || 'check';
  const sample = __ENV.VALIDATE_SAMPLE !== undefined ? parseFloat(__ENV.VALIDATE_SAMPLE) : cfg.sample !== undefined ? cfg.sample : 0.25;
  const raw = cfg.readAfterWrite || {};
  return {
    mode, // check | log | off
    sample,
    maxViolations: cfg.maxViolations !== undefined ? cfg.maxViolations : 0,
    readAfterWrite: mode !== 'off' && raw.enabled !== false,
    maxMismatches: raw.maxMismatches !== undefined ? raw.maxMismatches : 0,
  };
}

function at(obj, path) {
  return String(path || '').split('.').filter(Boolean).reduce((o, k) => (o == null ? undefined : o[k]), obj);
}

/** Runs once before the load (k6 setup): obtains credentials. Secrets come from the environment only. */
export function setupAuth(config) {
  return authn.setup(config, baseUrl(config));
}

function queryString(query) {
  const parts = [];
  for (const [k, v] of Object.entries(query || {})) {
    if (v === undefined || v === null) continue;
    const values = Array.isArray(v) ? v : [v];
    for (const x of values) parts.push(`${encodeURIComponent(k)}=${encodeURIComponent(typeof x === 'object' ? JSON.stringify(x) : x)}`);
  }
  return parts.length ? `?${parts.join('&')}` : '';
}

function fillPath(template, params) {
  return template.replace(/\{([^}]+)\}/g, (_, name) => encodeURIComponent(String(params && params[name] !== undefined ? params[name] : '')));
}

/**
 * Builds the request for an API without sending it. `seeded`: pools of ids created in setup; `complete`: include
 * every optional field (seeding).
 */
export function buildRequest(api, hooks, seeded, complete) {
  const ctx = data.context(api.id, seeded, complete, api.method !== 'GET' && api.method !== 'HEAD');
  let req = api.mod.build(ctx);
  const payload = data.userPayload(ctx);
  if (payload !== undefined) req.body = payload;
  if (hooks && typeof hooks.beforeRequest === 'function') req = hooks.beforeRequest(api.id, req, ctx) || req;
  return { req, ctx };
}

/** Builds, sends and checks one request, then pauses for the configured think time. */
export function call(api, runtime, auth, hooks) {
  const { req, ctx } = buildRequest(api, hooks, auth && auth.seeded);
  const res = send(api, req, ctx, runtime, auth, hooks);
  if (api.creates && res.status >= 200 && res.status < 300) {
    // later requests of this VU can use the new row, by id and by its other keys (slug, username …)
    data.remember(api.creates.pool, createdId(api.creates, res, req));
    const keys = capturedKeys(api.creates, res, req);
    for (const pool of Object.keys(keys)) data.remember(pool, keys[pool]);
  }
  const think = runtime.config.thinkTime || {};
  if (think.max > 0) sleep(think.min + Math.random() * (think.max - think.min));
  return res;
}

function hasFile(value) {
  return value && typeof value === 'object' && Object.values(value).some((v) => v && typeof v === 'object' && (v.$file || (Array.isArray(v) && v.some((x) => x && x.$file))));
}

/**
 * The request body: JSON text, or for APIs that take forms an object k6 encodes itself — multipart/form-data when a
 * field is a file (`{$file}` marker from lib/dummy.js) or the API is a multipart one, else urlencoded.
 */
function encodeBody(api, body) {
  if (api.bodyType !== 'multipart' && api.bodyType !== 'form' && !hasFile(body)) return JSON.stringify(body);
  const multipart = api.bodyType === 'multipart' || hasFile(body);
  const form = {};
  for (const [name, v] of Object.entries(body || {})) {
    if (v === undefined || v === null) continue;
    if (v.$file) form[name] = http.file(v.$file.data, v.$file.name, v.$file.contentType);
    else if (Array.isArray(v) && v.some((x) => x && x.$file)) form[name] = http.file(v[0].$file.data, v[0].$file.name, v[0].$file.contentType);
    else if (typeof v === 'object') form[name] = multipart ? http.file(JSON.stringify(v), `${name}.json`, 'application/json') : JSON.stringify(v);
    else form[name] = multipart ? String(v) : v;
  }
  return form;
}

/** Sends a built request: URL, auth and JSON body, tags, expected-status check, afterResponse hook. */
export function send(api, req, ctx, runtime, auth, hooks, phase) {
  const config = runtime.config;
  const url = runtime.baseUrl + fillPath(api.path, req.path) + queryString(req.query);
  let body = null;
  if (req.body !== undefined && api.method !== 'GET' && api.method !== 'HEAD') body = encodeBody(api, req.body);
  const params = {
    tags: phase ? { api: `${phase}_${api.id}`, name: api.name, phase } : { api: api.id, name: api.name },
    responseCallback: api.callback,
    timeout: (config.http && config.http.timeout) || '30s',
  };
  let res;
  for (let attempt = 0; attempt < 2; attempt++) {
    const session = authn.sessionFor(runtime, api, auth); // the identity of the API's role; logs in when due
    const headers = Object.assign({ Accept: 'application/json' }, config.headers || {}, session.headers || {},
      authn.csrfHeaders(session, runtime.baseUrl, api.method));
    for (const [k, v] of Object.entries(req.headers || {})) if (v !== undefined) headers[k] = String(v);
    if (typeof body === 'string') headers['Content-Type'] = 'application/json'; // forms and uploads: k6 sets theirs
    params.headers = headers;
    if (session.jar) params.jar = session.jar;
    // the first 401 is answered by a new login, not a failure; only a 401 after that is
    params.responseCallback = attempt === 0 && authn.canRenew(runtime, api)
      ? http.expectedStatuses(...api.expected, 401) : api.callback;
    res = http.request(api.method, url, body, params);
    // an expired or revoked token: log in again and send the request once more
    if (res.status === 401 && attempt === 0 && authn.renew(runtime, api)) continue;
    break;
  }
  const tags = phase ? { api: `${phase}_${api.id}`, phase } : { api: api.id };
  check(res, { 'status is expected': (r) => api.expected.indexOf(r.status) >= 0 }, tags);
  checkResponse(api, res, runtime, tags);
  if (api.graphql && res.status === 200) checkGraphQl(api, res, tags);
  if (hooks && typeof hooks.afterResponse === 'function') hooks.afterResponse(api.id, res, req, ctx);
  return res;
}

/** GraphQL answers 200 with an "errors" member when an operation fails: that is a failed request. */
function checkGraphQl(api, res, tags) {
  let errors;
  try {
    errors = res.json().errors;
  } catch (e) {
    errors = [{ message: 'the response is not JSON' }];
  }
  const ok = !(Array.isArray(errors) && errors.length > 0);
  check(res, { 'no GraphQL errors': () => ok }, tags);
  if (!ok) console.warn(`GraphQL ${api.id} returned errors: ${JSON.stringify(errors).slice(0, 200)}`);
}

const loggedViolations = {};

/** Validates a sample of successful JSON responses against the API's schema (see lib/validate.js). */
function checkResponse(api, res, runtime, tags) {
  const v = runtime.validation;
  if (v.mode === 'off' || !api.responseSchema || res.status < 200 || res.status >= 300) return;
  if (v.sample < 1 && Math.random() >= v.sample) return;
  const type = String(res.headers['Content-Type'] || res.headers['content-type'] || '');
  if (type.indexOf('json') < 0 || !res.body) return;
  let errors;
  try {
    errors = validate.validate(api.responseSchema, res.json());
  } catch (e) {
    errors = ['$: the body is not valid JSON'];
  }
  const ok = errors.length === 0;
  if (v.mode === 'check') check(res, { 'response matches schema': () => ok }, tags);
  if (ok) return;
  validate.violations.add(1, tags);
  if ((loggedViolations[api.id] = (loggedViolations[api.id] || 0) + 1) <= 3) {
    console.warn(`response of ${api.method} ${api.path} (HTTP ${res.status}) breaks its schema: ${errors.join('; ')}`);
  }
}

/** After a read that follows a write of the same row: did the read return what the write sent? */
function checkReadAfterWrite(api, res, written, runtime) {
  if (!runtime.validation.readAfterWrite || !written || res.status < 200 || res.status >= 300) return;
  let body;
  try {
    body = res.json();
  } catch (e) {
    return;
  }
  const diffs = validate.mismatches(written, body);
  const tags = { api: api.id };
  check(res, { 'read returns what was written': () => diffs.length === 0 }, tags);
  if (diffs.length) {
    validate.readMismatches.add(1, tags);
    console.warn(`read ${api.method} ${api.path} differs from the write before it: ${diffs.join('; ')}`);
  }
}

// ── Journey: replay of a recorded browser flow (data/journey.json) ──────────────────────────────────────
// Each step: { api, path, query, headers, body, fill: [paths], pauseMs, recordedStatus }. A value
// { "$from": i, "at": "content.0.id", "alt": [{ "$from": j, "at": "id" }], "recorded": 41 } is taken from step
// i's response this run, else from the alternatives, else the recorded value. In auto/user data mode the recorded values are sent; sensitive fields (listed in
// fill) are generated. In dummy/random/real/mixed mode only the order, pauses and correlations are replayed.

function getPath(obj, path) {
  return String(path || '').split('.').filter((k) => k !== '').reduce((o, k) => (o == null ? undefined : o[k]), obj);
}

function setPath(obj, path, value) {
  const keys = String(path).split('.');
  let o = obj;
  for (let i = 0; i < keys.length - 1; i++) {
    if (o[keys[i]] == null || typeof o[keys[i]] !== 'object') o[keys[i]] = /^\d+$/.test(keys[i + 1]) ? [] : {};
    o = o[keys[i]];
  }
  if (value === undefined) delete o[keys[keys.length - 1]];
  else o[keys[keys.length - 1]] = value;
}

function resolveRefs(value, results) {
  if (Array.isArray(value)) return value.map((v) => resolveRefs(v, results));
  if (value && typeof value === 'object') {
    if (value.$from !== undefined) {
      for (const source of [value].concat(value.alt || [])) {
        // "$location": the last segment of the step's Location header (a create answering 201 without a body)
        let v = source.at === '$location' ? (results.locations || [])[source.$from]
          : getPath(results[source.$from], source.at);
        if (v === undefined && (value.deep === true || source.deep === true) && source.at !== '$location') {
          v = findKey(results[source.$from], source.at, 0); // wrapped responses
        }
        if (v !== undefined && v !== null && typeof v !== 'object') return v;
      }
      return value.recorded;
    }
    const out = {};
    for (const [k, v] of Object.entries(value)) out[k] = resolveRefs(v, results);
    return out;
  }
  return value;
}

function onlyRefs(values, results) {
  const out = {};
  for (const [k, v] of Object.entries(values || {})) {
    if (v && typeof v === 'object' && v.$from !== undefined) out[k] = resolveRefs(v, results);
  }
  return out;
}

function stepRequest(step, api, runtime, results, seeded) {
  const ctx = data.context(api.id, seeded, step.complete === true, api.method !== 'GET' && api.method !== 'HEAD');
  const req = api.mod.build(ctx);
  if (runtime.dataMode === 'auto' || runtime.dataMode === 'user') {
    req.path = Object.assign({}, req.path, resolveRefs(step.path, results));
    req.query = resolveRefs(step.query || {}, results);
    req.headers = Object.assign({}, req.headers, step.headers || {});
    if (step.body !== undefined) {
      const generated = req.body;
      const body = resolveRefs(step.body, results);
      for (const p of step.fill || []) setPath(body, p, getPath(generated, p));
      req.body = body;
    }
    for (const k of Object.keys(step.path || {})) ctx.sources[`path.${k}`] = 'recorded';
  } else {
    Object.assign(req.path, onlyRefs(step.path, results)); // created ids still flow from step to step
  }
  // lifecycle steps pin chosen fields (a status transition) whatever the data mode generated
  for (const [p, v] of Object.entries(step.set || {})) {
    if (req.body === undefined || req.body === null || typeof req.body !== 'object') req.body = {};
    setPath(req.body, p, resolveRefs(v, results));
  }
  return { req, ctx };
}

/** Replays the whole recorded flow once (one iteration). Steps of disabled APIs (e.g. DELETE) are skipped. */
export function replay(runtime, steps, auth, hooks, options) {
  const allowed = {};
  for (const a of runtime.apis) allowed[a.id] = true;
  const cfg = runtime.config.journey || {};
  const maxPause = cfg.maxPauseMs !== undefined ? cfg.maxPauseMs : 5000;
  const scale = cfg.pauseScale !== undefined ? cfg.pauseScale : 1;
  const results = [];
  results.locations = [];
  let lastWrite = null; // body of the last successful write of this flow (lifecycle: read-after-write)
  for (let i = 0; i < steps.length; i++) {
    const step = steps[i];
    const api = runtime.byId[step.api];
    // a lifecycle flow may delete the row it created itself even though DELETE is off for shared data
    const ownRow = step.ownRow === true && runtime.readOnly !== true
      && (runtime.config.lifecycle || {}).deleteOwnRows !== false;
    if (!api || !(allowed[step.api] || ownRow)) {
      results.push(undefined);
      continue;
    }
    const pause = Math.min(step.pauseMs || 0, maxPause) * scale;
    if (i > 0 && pause > 0) sleep(pause / 1000);
    let { req, ctx } = stepRequest(step, api, runtime, results, auth && auth.seeded);
    if (hooks && typeof hooks.beforeRequest === 'function') req = hooks.beforeRequest(api.id, req, ctx) || req;
    const res = send(api, req, ctx, runtime, auth, hooks);
    let json;
    try {
      json = res.json();
    } catch (e) {
      json = undefined;
    }
    const location = res.headers.Location || res.headers.location;
    if (location) {
      let id = decodeURIComponent(location.replace(/\/+$/, '').split('/').pop());
      if (/^\d+$/.test(id) && id.length < 16) id = parseInt(id, 10);
      results.locations[i] = id;
    }
    if (options && options.readAfterWrite) {
      if (api.method === 'GET') {
        checkReadAfterWrite(api, res, lastWrite, runtime);
      } else {
        const wrote = res.status >= 200 && res.status < 300 && req.body && typeof req.body === 'object';
        lastWrite = wrote && api.method !== 'DELETE' ? req.body : null; // a body-less action may change anything
      }
    }
    // a step that failed leaves nothing for later steps to correlate with: they fall back to recorded values
    results.push(res.status >= 200 && res.status < 300 ? json : undefined);
    if (step.stopOnFailure === true && !(res.status >= 200 && res.status < 300)) break;
  }
}

/**
 * MODE=lifecycle-<profile>: each iteration walks one resource through its life cycle (data/lifecycle.json, generated
 * from the code): create → read → update → status transitions → delete. Flows are taken in turn, so every resource
 * gets the same share of iterations; LIFECYCLE=<name,…> narrows them.
 */
export function lifecycle(runtime, flows, auth, hooks) {
  const wanted = __ENV.LIFECYCLE ? __ENV.LIFECYCLE.split(',').map((x) => x.trim()) : null;
  const usable = flows.filter((f) => !wanted || wanted.indexOf(f.name) >= 0);
  if (!usable.length) return;
  const flow = usable[(typeof __ITER === 'number' ? __ITER : 0) % usable.length];
  replay(runtime, flow.steps, auth, hooks, { readAfterWrite: true });
}

/** MODE=lifecycle-preview: prints the steps of every flow (LIFECYCLE=<name> narrows). Sends nothing. */
export function previewLifecycle(runtime, flows, hooks) {
  const wanted = __ENV.LIFECYCLE ? __ENV.LIFECYCLE.split(',').map((x) => x.trim()) : null;
  for (const flow of flows) {
    if (wanted && wanted.indexOf(flow.name) < 0) continue;
    console.log(JSON.stringify({ flow: flow.name, resource: flow.resource, steps: flow.steps.length }));
    previewJourney(runtime, flow.steps, hooks);
  }
}

/** MODE=journey-preview: prints every step as it would be sent (correlations show their recorded value). */
export function previewJourney(runtime, steps, hooks) {
  for (let i = 0; i < steps.length; i++) {
    const step = steps[i];
    const api = runtime.byId[step.api];
    if (!api) continue;
    const { req, ctx } = stepRequest(step, api, runtime, []);
    console.log(JSON.stringify({
      step: i,
      api: api.id,
      request: `${api.method} ${fillPath(api.path, req.path)}${queryString(req.query)}`,
      headers: req.headers,
      body: req.body,
      pauseMs: step.pauseMs,
      dataMode: runtime.dataMode,
      skipped: runtime.apis.every((a) => a.id !== api.id) || undefined,
    }));
  }
}

// ── Seeding: test data created through the application's own create endpoints (data/seed.json) ──────────
// Steps are ordered by the entity relationships (parents first); each created id goes into a pool that the
// next steps' payloads (foreign keys) and the load test itself (path ids, references) draw from.

function findKey(value, key, depth) {
  if (!value || typeof value !== 'object' || depth > 3) return undefined;
  if (!Array.isArray(value) && value[key] !== undefined && typeof value[key] !== 'object') return value[key];
  for (const v of Array.isArray(value) ? value.slice(0, 1) : Object.values(value)) {
    const found = findKey(v, key, depth + 1);
    if (found !== undefined) return found;
  }
  return undefined;
}

/** The id of a created row: from the response body, else the Location header, else (natural keys) the request. */
export function createdId(step, res, req) {
  if (step.idFromRequest) {
    const fromRequest = findKey(req.body, step.idField, 0);
    if (fromRequest !== undefined) return fromRequest;
  }
  let body;
  try {
    body = res.json();
  } catch (e) {
    body = undefined;
  }
  let id = findKey(body, step.idField, 0);
  if (id === undefined && step.idField !== 'id') id = findKey(body, 'id', 0);
  if (id === undefined) {
    const location = res.headers.Location || res.headers.location;
    if (location) id = decodeURIComponent(location.replace(/\/+$/, '').split('/').pop());
  }
  if (typeof id === 'string' && /^\d+$/.test(id) && id.length < 16) id = parseInt(id, 10);
  return id;
}

function responseBody(res) {
  try {
    return res.json();
  } catch (e) {
    return undefined;
  }
}

/** The other keys of a created row (step.captures: pool → property), from the response, else the request. */
export function capturedKeys(step, res, req) {
  const out = {};
  const body = step.captures ? responseBody(res) : undefined;
  for (const pool of Object.keys(step.captures || {})) {
    const property = step.captures[pool];
    let v = findKey(body, property, 0);
    if (v === undefined) v = findKey(req.body, property, 0);
    if (v !== undefined && v !== null) out[pool] = v;
  }
  return out;
}

function seedingEnabled(runtime) {
  const cfg = runtime.config.seed || {};
  if (__ENV.SEED !== undefined) return __ENV.SEED === 'true';
  return cfg.enabled !== false && __ENV.READ_ONLY !== 'true' && !(runtime.config.safety || {}).readOnly;
}

/**
 * Runs in k6 setup(): creates config.seed.perTable rows per table (SEED_PER_TABLE overrides) and returns the
 * created ids by pool, for every VU. Tables whose create fails are logged and simply have no seeded rows.
 */
export function seed(runtime, steps, auth, hooks) {
  const seeded = {};
  if (!seedingEnabled(runtime) || !steps || !steps.length) return seeded;
  const cfg = runtime.config.seed || {};
  const perTable = parseInt(__ENV.SEED_PER_TABLE || cfg.perTable || 5, 10);
  const report = [];
  for (const step of steps) {
    const api = runtime.byId[step.api];
    if (!api || ((runtime.config.apis || {})[step.api] || {}).enabled === false) continue;
    const ids = [];
    const keys = {};
    let created = 0;
    for (let i = 0; i < perTable; i++) {
      const { req, ctx } = buildRequest(api, hooks, seeded, true);
      const res = send(api, req, ctx, runtime, auth, hooks, 'seed');
      if (res.status >= 200 && res.status < 300) {
        created++;
        const id = createdId(step, res, req);
        if (id !== undefined && id !== null) ids.push(id);
        const captured = capturedKeys(step, res, req);
        for (const pool of Object.keys(captured)) (keys[pool] || (keys[pool] = [])).push(captured[pool]);
      }
    }
    if (ids.length) seeded[step.pool] = ids;
    for (const pool of Object.keys(keys)) seeded[pool] = keys[pool];
    const failures = perTable - created;
    report.push(`${step.table} ${created}/${perTable}${failures ? ` (${failures} failed)` : ''}`);
  }
  console.log(`seed: ${report.join(', ')}`);
  return seeded;
}

/**
 * Runs in k6 teardown() when config.seed.cleanup (or SEED_CLEANUP=true): deletes seeded rows, children first.
 * A table whose rows cannot all be deleted (no delete endpoint, or a delete refused) keeps its parents too:
 * deleting them would only violate the foreign keys. Rows created during the load itself are not touched.
 */
export function cleanup(runtime, steps, setupData, hooks) {
  const cfg = runtime.config.seed || {};
  const wanted = __ENV.SEED_CLEANUP !== undefined ? __ENV.SEED_CLEANUP === 'true' : cfg.cleanup === true;
  if (!wanted || !setupData || !setupData.seeded) return;
  const kept = {};
  const report = [];
  for (const step of (steps || []).slice().reverse()) {
    const ids = setupData.seeded[step.deletePool || step.pool] || [];
    if (!ids.length) continue;
    const api = step.deleteApi && runtime.byId[step.deleteApi];
    let deleted = 0;
    if (api && !kept[step.pool]) {
      const param = (api.path.match(/\{([^}]+)\}/) || [])[1];
      for (const id of ids) {
        const res = send(api, { path: { [param]: id }, query: {}, headers: {} }, data.context(api.id), runtime,
          setupData, hooks, 'cleanup');
        if ((res.status >= 200 && res.status < 300) || res.status === 404) deleted++;
      }
    }
    if (deleted < ids.length) {
      for (const parent of step.dependsOn || []) kept[parent] = true; // still referenced: keep the parents
    }
    report.push(`${step.table} ${deleted}/${ids.length}${kept[step.pool] ? ' (kept: still referenced)' : ''}`);
  }
  console.log(`cleanup: ${report.join(', ')}`);
}

/** MODE=preview: prints PREVIEW_COUNT requests per API, with the data source of every field. Sends nothing. */
export function preview(runtime, hooks) {
  const count = parseInt(__ENV.PREVIEW_COUNT || '2', 10);
  for (const api of runtime.apis) {
    for (let i = 0; i < count; i++) {
      const { req, ctx } = buildRequest(api, hooks);
      const line = {
        api: api.id,
        request: `${api.method} ${fillPath(api.path, req.path)}${queryString(req.query)}`,
        headers: req.headers,
        body: req.body,
        dataMode: runtime.dataMode,
        sources: ctx.sources,
      };
      console.log(JSON.stringify(line));
    }
  }
}
