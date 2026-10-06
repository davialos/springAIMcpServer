// Resilience experiments: network faults injected through Toxiproxy while the load runs.
//
// config.resilience: { enabled, toxiproxy: "http://localhost:8474", proxies: [{name, listen, upstream}],
//   experiments: [{ name, proxy, startAfter: "30s", duration: "60s", recovery: "30s",
//                   toxics: [{ type: "latency", stream: "downstream", toxicity: 1, attributes: { latency: 300 } }],
//                   expect: { maxErrorRate, p95Ms, recoveryMaxErrorRate, recoveryP95Ms } }] }
// Off unless RESILIENCE=on (or resilience.enabled). Each experiment is a scenario of the run itself, so it starts at the
// same moment whoever runs k6 (CLI, CI, Docker); requests are tagged fault=<name> during the fault, fault=recover_<name>
// for the `recovery` window after it, fault=none otherwise — the verdict is per phase (see modes.js thresholds).
import http from 'k6/http';
import { check, sleep } from 'k6';
import { seconds } from './modes.js';

/** The settings of this run, or null when experiments are off. `shift`: seconds before measuring starts (warm-up). */
export function prepare(config, shift) {
  const cfg = config.resilience || {};
  const on = __ENV.RESILIENCE === 'on' || (cfg.enabled === true && __ENV.RESILIENCE !== 'off');
  const only = __ENV.EXPERIMENT ? __ENV.EXPERIMENT.split(',').map((s) => s.trim()) : null;
  const experiments = (cfg.experiments || []).filter((e) => !only || only.indexOf(e.name) >= 0);
  if (!on || !experiments.length) return null;
  const windows = experiments.map((e) => {
    const start = (shift || 0) + seconds(e.startAfter || '30s');
    const end = start + seconds(e.duration || '60s');
    return { name: e.name, start, end, recoverEnd: end + seconds(e.recovery || '30s') };
  });
  return { url: (__ENV.TOXIPROXY_URL || cfg.toxiproxy || 'http://localhost:8474').replace(/\/+$/, ''), proxies: cfg.proxies || [],
    experiments, windows };
}

const JSON_HEADERS = { headers: { 'Content-Type': 'application/json' }, tags: { api: 'chaos' } };

/** Creates the proxies in the Toxiproxy server (k6 setup); one that exists already is fine. */
export function ensureProxies(r) {
  for (const p of r.proxies) {
    const res = http.post(`${r.url}/proxies`, JSON.stringify({ name: p.name, listen: p.listen, upstream: p.upstream, enabled: true }), JSON_HEADERS);
    if (res.status >= 300 && res.status !== 409) throw new Error(`Toxiproxy ${r.url}: cannot create proxy ${p.name}: HTTP ${res.status} ${res.body}`);
  }
}

function toxicName(e, t, i) {
  return t.name || `${e.name}-${t.type}-${i}`;
}

function setEnabled(r, proxy, enabled) {
  return http.post(`${r.url}/proxies/${proxy}`, JSON.stringify({ enabled }), JSON_HEADERS);
}

/** The body of a scenario `fault`: inject, hold for `duration`, remove. */
export function runFault(r, name) {
  const e = r.experiments.find((x) => x.name === name);
  if (!e) throw new Error(`no resilience experiment ${name}`);
  let ok = true;
  (e.toxics || []).forEach((t, i) => {
    const res = t.type === 'down' ? setEnabled(r, e.proxy, false)
      : http.post(`${r.url}/proxies/${e.proxy}/toxics`, JSON.stringify({ name: toxicName(e, t, i), type: t.type,
        stream: t.stream || 'downstream', toxicity: t.toxicity === undefined ? 1 : t.toxicity, attributes: t.attributes || {} }), JSON_HEADERS);
    ok = ok && res.status < 300;
    check(res, { [`toxiproxy accepted ${name} (${t.type})`]: (x) => x.status < 300 }, { api: 'chaos', fault: 'none' });
  });
  console.log(`resilience: ${name} ON for ${e.duration || '60s'} (${(e.toxics || []).map((t) => t.type).join(', ')} on ${e.proxy})`);
  sleep(seconds(e.duration || '60s'));
  removeFault(r, e);
  console.log(`resilience: ${name} OFF`);
  return ok;
}

function removeFault(r, e) {
  (e.toxics || []).forEach((t, i) => {
    if (t.type === 'down') setEnabled(r, e.proxy, true);
    else http.del(`${r.url}/proxies/${e.proxy}/toxics/${toxicName(e, t, i)}`, null, JSON_HEADERS);
  });
}

/** k6 teardown: whatever happened, no fault stays behind. */
export function cleanup(r) {
  if (r) r.experiments.forEach((e) => removeFault(r, e));
}

/**
 * The fault tag of a request sent now: the experiment running, `recover_<name>` after it, else `none`. Time is counted
 * from `auth.t0`, taken at the end of k6 setup — the moment the scenarios' own start times count from.
 */
export function tagNow(r, auth) {
  if (!auth || !auth.t0) return 'none';
  const t = (Date.now() - auth.t0) / 1000;
  for (const w of r.windows) {
    if (t >= w.start && t < w.end) return w.name;
    if (t >= w.end && t < w.recoverEnd) return `recover_${w.name}`;
  }
  return 'none';
}
