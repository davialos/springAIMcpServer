// Request execution: URL/query/headers/body assembly, auth, status checks, think time and safety rails.
import http from 'k6/http';
import { check, sleep } from 'k6';
import encoding from 'k6/encoding';
import * as data from './data.js';
import { parseMode } from './modes.js';

const DEFAULT_EXPECTED = { GET: [200], HEAD: [200], POST: [200, 201, 202], PUT: [200, 201, 204], PATCH: [200, 204], DELETE: [200, 202, 204], OPTIONS: [200, 204] };

function baseUrl(config) {
  return (__ENV.BASE_URL || config.baseUrl || 'http://localhost:8080').replace(/\/+$/, '');
}

/**
 * Resolves the APIs this run uses (enabled in config, READ_ONLY, API filter), with their expected statuses.
 * Throws when BASE_URL looks like production and ALLOW_PROD is not set (safety.blockedHostPattern).
 */
export function prepare(config, modules) {
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
      mod,
    };
    byId[api.id] = api;
    if (only) {
      if (only.indexOf(api.id) < 0) continue; // an explicit API list overrides "enabled"
    } else if (c.enabled === false) {
      continue;
    }
    if (readOnly && api.method !== 'GET' && api.method !== 'HEAD') continue;
    if (mixed && api.weight <= 0) continue;
    apis.push(api);
  }
  if (only) {
    for (const id of only) if (!byId[id]) throw new Error(`API=${id}: no such API (see loadtest.config.json → apis)`);
  }
  return { apis, byId, baseUrl: url, config, dataMode };
}

function resolveEnv(value) {
  if (typeof value === 'string') return value.replace(/\$\{([A-Z0-9_]+)\}/g, (_, k) => __ENV[k] || '');
  if (Array.isArray(value)) return value.map(resolveEnv);
  if (value && typeof value === 'object') {
    const out = {};
    for (const [k, v] of Object.entries(value)) out[k] = resolveEnv(v);
    return out;
  }
  return value;
}

function at(obj, path) {
  return String(path || '').split('.').filter(Boolean).reduce((o, k) => (o == null ? undefined : o[k]), obj);
}

/** Runs once before the load (k6 setup): obtains credentials. Secrets come from the environment only. */
export function setupAuth(config) {
  const auth = config.auth || { type: 'none' };
  switch (auth.type) {
    case 'bearer':
      if (!__ENV.AUTH_TOKEN) throw new Error('auth.type=bearer needs AUTH_TOKEN');
      return { headers: { [auth.header || 'Authorization']: `Bearer ${__ENV.AUTH_TOKEN}` } };
    case 'basic': {
      const user = __ENV.AUTH_USER || '';
      const pass = __ENV.AUTH_PASSWORD || '';
      return { headers: { Authorization: `Basic ${encoding.b64encode(`${user}:${pass}`)}` } };
    }
    case 'apiKey':
      if (!__ENV.API_KEY) throw new Error('auth.type=apiKey needs API_KEY');
      return { headers: { [auth.apiKeyHeader || 'X-API-Key']: __ENV.API_KEY } };
    case 'login': {
      const login = auth.login || {};
      const url = baseUrl(config) + (login.path || '/login');
      const res = http.request(login.method || 'POST', url, JSON.stringify(resolveEnv(login.body || {})), {
        headers: { 'Content-Type': 'application/json', Accept: 'application/json' },
        tags: { api: 'auth_login', name: `${login.method || 'POST'} ${login.path || '/login'}` },
      });
      if (res.status < 200 || res.status >= 300) throw new Error(`login failed: HTTP ${res.status}`);
      const token = at(res.json(), login.tokenPath || 'token');
      if (!token) throw new Error(`login response has no ${login.tokenPath || 'token'}`);
      return { headers: { [auth.header || 'Authorization']: `${login.scheme === undefined ? 'Bearer ' : login.scheme}${token}` } };
    }
    default:
      return { headers: {} };
  }
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

/** Builds the request for an API without sending it. */
export function buildRequest(api, hooks) {
  const ctx = data.context(api.id);
  let req = api.mod.build(ctx);
  const payload = data.userPayload(ctx);
  if (payload !== undefined) req.body = payload;
  if (hooks && typeof hooks.beforeRequest === 'function') req = hooks.beforeRequest(api.id, req, ctx) || req;
  return { req, ctx };
}

/** Builds, sends and checks one request. */
export function call(api, runtime, auth, hooks) {
  const { req, ctx } = buildRequest(api, hooks);
  const config = runtime.config;
  const url = runtime.baseUrl + fillPath(api.path, req.path) + queryString(req.query);
  const headers = Object.assign({ Accept: 'application/json' }, config.headers || {}, (auth && auth.headers) || {});
  for (const [k, v] of Object.entries(req.headers || {})) if (v !== undefined) headers[k] = String(v);
  let body = null;
  if (req.body !== undefined && api.method !== 'GET' && api.method !== 'HEAD') {
    headers['Content-Type'] = 'application/json';
    body = JSON.stringify(req.body);
  }
  const res = http.request(api.method, url, body, {
    headers,
    tags: { api: api.id, name: api.name },
    responseCallback: api.callback,
    timeout: (config.http && config.http.timeout) || '30s',
  });
  check(res, { 'status is expected': (r) => api.expected.indexOf(r.status) >= 0 }, { api: api.id });
  if (hooks && typeof hooks.afterResponse === 'function') hooks.afterResponse(api.id, res, req, ctx);
  const think = config.thinkTime || {};
  if (think.max > 0) sleep(think.min + Math.random() * (think.max - think.min));
  return res;
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
