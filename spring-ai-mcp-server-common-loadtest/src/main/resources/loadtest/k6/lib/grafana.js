// GENERATED runtime. Grafana annotations around a run: a region from setup() to teardown() tagged
// k6 / <mode> / <testid>, shown on the suite's dashboard (grafana/). Active only when GRAFANA_URL is set
// (run.sh sets it with GRAFANA=1); GRAFANA_TOKEN adds a bearer token for a Grafana that needs one.
// A Grafana that cannot be reached never fails the test: annotations are best effort.
import http from 'k6/http';

function enabled() {
  return !!__ENV.GRAFANA_URL;
}

function request(method, path, body) {
  const headers = { 'Content-Type': 'application/json' };
  if (__ENV.GRAFANA_TOKEN) headers.Authorization = `Bearer ${__ENV.GRAFANA_TOKEN}`;
  try {
    // tagged without `api`, so the per-API metrics and thresholds never include these calls
    return http.request(method, `${__ENV.GRAFANA_URL.replace(/\/+$/, '')}${path}`, JSON.stringify(body), {
      headers, timeout: '5s', tags: { name: `grafana ${method} ${path.replace(/\d+$/, '{id}')}`, phase: 'grafana' },
      responseCallback: http.expectedStatuses({ min: 200, max: 599 }),
    });
  } catch (e) {
    return null;
  }
}

/** Starts the run's annotation region; returns what end() needs, or null. */
export function start(mode, dataMode, baseUrl) {
  if (!enabled()) return null;
  const testId = __ENV.TEST_ID || '';
  const tags = ['k6', mode].concat(testId ? [testId] : []);
  const res = request('POST', '/api/annotations', {
    time: Date.now(),
    tags,
    text: `k6 ${mode} (data ${dataMode}) against ${baseUrl}${testId ? ` — ${testId}` : ''}`,
  });
  if (!res || res.status >= 300) {
    console.warn(`grafana: annotation not created (${res ? `HTTP ${res.status}` : 'unreachable'})`);
    return null;
  }
  let id = null;
  try {
    id = res.json().id;
  } catch (e) {
    id = null;
  }
  return id ? { id } : null;
}

/** Closes the region at the end of the run. */
export function end(annotation) {
  if (!enabled() || !annotation || !annotation.id) return;
  request('PATCH', `/api/annotations/${annotation.id}`, { timeEnd: Date.now() });
}
