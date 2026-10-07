// Authentication: static credentials (bearer, basic, apiKey, JSON login) and the Spring Security styles that need a
// session per caller — form login with CSRF, OAuth2 (client credentials / password grant, e.g. Keycloak) — with one
// identity per role, per-VU identities (${VU} in a user name), token refresh and a retry after a 401.
//
// config.auth: { type, header, apiKeyHeader, login{…}, form{…}, oauth2{…}, users{ROLE: {username, password, token,
// clientId, clientSecret}}, defaultRole, perVu, refresh{afterSeconds}, retryOn401 }. Secrets only as ${ENV} values.
import http from 'k6/http';
import encoding from 'k6/encoding';

const sessions = {}; // per VU (every VU has its own JS runtime): identity name → session

export function resolveEnv(value, vars) {
  if (typeof value === 'string') {
    return value.replace(/\$\{([A-Z0-9_]+)\}/g, (_, k) => {
      if (vars && vars[k] !== undefined) return vars[k];
      if (k === 'VU') return String(__VU);
      return __ENV[k] || '';
    });
  }
  if (Array.isArray(value)) return value.map((v) => resolveEnv(v, vars));
  if (value && typeof value === 'object') {
    const out = {};
    for (const [k, v] of Object.entries(value)) out[k] = resolveEnv(v, vars);
    return out;
  }
  return value;
}

function at(obj, path) {
  return String(path || '').split('.').filter(Boolean).reduce((o, k) => (o == null ? undefined : o[k]), obj);
}

/** Whether callers get their own session at run time (not one token from setup shared by every VU). */
export function isDynamic(cfg) {
  const users = cfg.users && Object.keys(cfg.users).length > 0;
  const refresh = cfg.refresh && cfg.refresh.afterSeconds > 0;
  return cfg.type === 'form' || cfg.type === 'oauth2' || cfg.perVu === true || !!users || !!refresh;
}

function absolute(base, path) {
  return /^https?:\/\//i.test(path) ? path : base + path;
}

/** The credentials of an identity: users[role] (with ${ENV}/${VU} resolved), else the AUTH_* variables. */
function identity(cfg, role) {
  const name = role || cfg.defaultRole;
  const user = name && cfg.users && cfg.users[name] ? resolveEnv(cfg.users[name]) : {};
  return {
    name: name || 'default',
    username: user.username || resolveEnv(__ENV.AUTH_USER || ''),
    password: user.password || resolveEnv(__ENV.AUTH_PASSWORD || ''),
    token: user.token || __ENV.AUTH_TOKEN || '',
    clientId: user.clientId,
    clientSecret: user.clientSecret,
  };
}

function jwtExpiry(token) {
  try {
    const part = String(token).split('.')[1];
    if (!part) return 0;
    const claims = JSON.parse(encoding.b64decode(part, 'rawurl', 's'));
    return claims.exp ? claims.exp * 1000 - 10000 : 0; // renew 10 s early
  } catch (e) {
    return 0;
  }
}

function expiry(cfg, token, expiresInSeconds) {
  const after = cfg.refresh && cfg.refresh.afterSeconds;
  if (after > 0) return Date.now() + after * 1000;
  if (expiresInSeconds > 0) return Date.now() + expiresInSeconds * 900; // renew at 90% of the lifetime
  return jwtExpiry(token);
}

function fail(what, res) {
  throw new Error(`${what} failed: HTTP ${res.status}${res.body ? ` ${String(res.body).slice(0, 120)}` : ''}`);
}

function bearer(cfg, token, expiresIn, type) {
  return { headers: { [cfg.header || 'Authorization']: `${type || 'Bearer'} ${token}` }, expiresAt: expiry(cfg, token, expiresIn) };
}

function jsonLogin(cfg, base, id) {
  const login = cfg.login || {};
  const vars = { AUTH_USER: id.username, AUTH_PASSWORD: id.password };
  const method = login.method || 'POST';
  const res = http.request(method, absolute(base, login.path || '/login'), JSON.stringify(resolveEnv(login.body || {}, vars)), {
    headers: { 'Content-Type': 'application/json', Accept: 'application/json' },
    tags: { api: 'auth_login', name: `${method} ${login.path || '/login'}` },
  });
  if (res.status < 200 || res.status >= 300) fail('login', res);
  const token = at(res.json(), login.tokenPath || 'token');
  if (!token) throw new Error(`login response has no ${login.tokenPath || 'token'}`);
  const scheme = login.scheme === undefined ? 'Bearer ' : login.scheme;
  return { headers: { [cfg.header || 'Authorization']: `${scheme}${token}` }, expiresAt: expiry(cfg, token, at(res.json(), login.expiresInPath || 'expires_in')) };
}

function oauth2(cfg, base, id) {
  const o = resolveEnv(cfg.oauth2 || {});
  const grant = o.grant || 'client_credentials';
  const clientId = id.clientId || o.clientId || '';
  const clientSecret = id.clientSecret || o.clientSecret || '';
  const body = { grant_type: grant };
  if (o.scope) body.scope = o.scope;
  if (grant === 'password') {
    body.username = id.username;
    body.password = id.password;
  }
  const headers = { 'Content-Type': 'application/x-www-form-urlencoded', Accept: 'application/json' };
  if ((o.clientAuth || 'basic') === 'basic') headers.Authorization = `Basic ${encoding.b64encode(`${clientId}:${clientSecret}`)}`;
  else {
    body.client_id = clientId;
    body.client_secret = clientSecret;
  }
  const res = http.post(absolute(base, o.tokenUrl || '/oauth/token'), body, {
    headers,
    tags: { api: 'auth_token', name: 'POST oauth2 token' },
  });
  if (res.status < 200 || res.status >= 300) fail('OAuth2 token request', res);
  const json = res.json();
  if (!json.access_token) throw new Error('OAuth2 token response has no access_token');
  return bearer(cfg, json.access_token, json.expires_in, json.token_type === 'Bearer' || !json.token_type ? 'Bearer' : json.token_type);
}

function scrapeCsrf(html) {
  const meta = /<meta[^>]+name=["']_csrf["'][^>]+content=["']([^"']+)["']/i.exec(html);
  if (meta) {
    const header = /<meta[^>]+name=["']_csrf_header["'][^>]+content=["']([^"']+)["']/i.exec(html);
    return { token: meta[1], header: header ? header[1] : 'X-CSRF-TOKEN' };
  }
  const input = /<input[^>]+name=["']_csrf["'][^>]+value=["']([^"']+)["']/i.exec(html)
    || /<input[^>]+value=["']([^"']+)["'][^>]+name=["']_csrf["']/i.exec(html);
  return input ? { token: input[1], header: 'X-CSRF-TOKEN' } : undefined;
}

/** Spring form login: GET the page for the CSRF token, POST the credentials, keep the session cookie in a jar. */
function formLogin(cfg, base, id) {
  const f = cfg.form || {};
  const jar = new http.CookieJar();
  const path = f.loginPath || '/login';
  const page = http.get(absolute(base, path), { jar, tags: { api: 'auth_login', name: `GET ${path}` } });
  const form = {};
  form[f.usernameField || 'username'] = id.username;
  form[f.passwordField || 'password'] = id.password;
  const csrf = scrapeCsrf(String(page.body || ''));
  if (csrf && f.csrf !== false) form[f.csrfField || '_csrf'] = csrf.token;
  const res = http.post(absolute(base, f.processingPath || path), form, { jar, tags: { api: 'auth_login', name: `POST ${path}` } });
  if (res.status >= 400 || String(res.url).indexOf('error') >= 0) fail('form login', res);
  return { headers: {}, jar, form: { csrfPage: f.csrfPage || '/', csrf: f.csrf !== false }, expiresAt: expiry(cfg, '', 0) };
}

/** The CSRF header a write needs on a cookie session: the XSRF-TOKEN cookie, else a token scraped from a page. */
export function csrfHeaders(session, base, method) {
  if (!session.jar || !session.form || !session.form.csrf || method === 'GET' || method === 'HEAD' || method === 'OPTIONS') return {};
  const cookie = session.jar.cookiesForURL(base + '/')['XSRF-TOKEN'];
  if (cookie && cookie.length) return { 'X-XSRF-TOKEN': cookie[0] };
  if (!session.scraped) {
    const page = http.get(absolute(base, session.form.csrfPage), { jar: session.jar, tags: { api: 'auth_login', name: 'GET csrf page' } });
    session.scraped = scrapeCsrf(String(page.body || '')) || { none: true };
  }
  return session.scraped.none ? {} : { [session.scraped.header]: session.scraped.token };
}

function establish(cfg, base, role) {
  const id = identity(cfg, role);
  switch (cfg.type) {
    case 'bearer':
      if (!id.token) throw new Error(`auth.type=bearer needs AUTH_TOKEN (or auth.users.${id.name}.token)`);
      return bearer(cfg, id.token, 0);
    case 'basic':
      return { headers: { Authorization: `Basic ${encoding.b64encode(`${id.username}:${id.password}`)}` }, expiresAt: 0 };
    case 'apiKey': {
      const key = id.token || __ENV.API_KEY;
      if (!key) throw new Error('auth.type=apiKey needs API_KEY');
      return { headers: { [cfg.apiKeyHeader || 'X-API-Key']: key }, expiresAt: 0 };
    }
    case 'login':
      return jsonLogin(cfg, base, id);
    case 'oauth2':
      return oauth2(cfg, base, id);
    case 'form':
      return formLogin(cfg, base, id);
    default:
      return { headers: {}, expiresAt: 0 };
  }
}

/** Runs in k6 setup(): static credentials for everyone, or nothing yet when callers log in themselves. */
export function setup(config, base) {
  const cfg = config.auth || { type: 'none' };
  if (isDynamic(cfg)) return { headers: {} };
  return { headers: establish(cfg, base, undefined).headers };
}

/**
 * The session an API call uses: the identity of the API's role (`apis.<id>.auth`: a role, or "none" for public
 * endpoints), else the default one. Logs in on first use and again when the token is due.
 * @returns {{headers: object, jar?: object}}
 */
export function sessionFor(runtime, api, setupAuth) {
  const cfg = runtime.config.auth || { type: 'none' };
  const role = api.authRole;
  if (role === 'none') return { headers: {} };
  if (!isDynamic(cfg)) return { headers: (setupAuth && setupAuth.headers) || {} };
  const name = role || cfg.defaultRole || 'default';
  let s = sessions[name];
  if (!s || (s.expiresAt > 0 && Date.now() >= s.expiresAt)) {
    s = establish(cfg, runtime.baseUrl, role);
    sessions[name] = s;
  }
  return s;
}

/** Whether a 401 for this API is answered by logging in again (so the first 401 is not counted as a failure). */
export function canRenew(runtime, api) {
  const cfg = runtime.config.auth || { type: 'none' };
  return isDynamic(cfg) && cfg.retryOn401 !== false && api.authRole !== 'none';
}

/** Forgets the session of an API's identity (after a 401), so the next call logs in again. */
export function renew(runtime, api) {
  if (!canRenew(runtime, api)) return false;
  delete sessions[api.authRole || (runtime.config.auth || {}).defaultRole || 'default'];
  return true;
}
