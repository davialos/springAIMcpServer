// End-of-test report: a per-API table on stdout plus JSON and Markdown files under reports/.

function metric(data, name) {
  return data.metrics[name] && data.metrics[name].values;
}

function ms(v) {
  return v === undefined ? '-' : `${v.toFixed(1)}`;
}

function pct(v) {
  return v === undefined ? '-' : `${(v * 100).toFixed(2)}%`;
}

function pad(s, n) {
  s = String(s);
  return s.length >= n ? s : s + ' '.repeat(n - s.length);
}

/** One row per resilience experiment: how the service behaved during the fault and after it. */
function resilienceRows(data, runtime) {
  const rows = [];
  for (const e of runtime.resilience ? runtime.resilience.experiments : []) {
    const phase = (tag) => {
      const d = metric(data, `http_req_duration{fault:${tag}}`) || {};
      const f = metric(data, `http_req_failed{fault:${tag}}`) || {};
      const failedMetric = data.metrics[`http_req_failed{fault:${tag}}`];
      const ok = ['http_req_failed', 'http_req_duration'].every((m) => {
        const t = (data.metrics[`${m}{fault:${tag}}`] || {}).thresholds || {};
        return Object.values(t).every((x) => x.ok);
      });
      return { requests: failedMetric && failedMetric.values ? failedMetric.values.passes + failedMetric.values.fails : 0, failed: f.rate, p95: d['p(95)'], ok };
    };
    rows.push({ experiment: e.name, proxy: e.proxy, toxics: (e.toxics || []).map((t) => t.type).join('+'), during: phase(e.name), after: phase(`recover_${e.name}`) });
  }
  return rows;
}

export function summary(data, runtime, mode) {
  if (mode.endsWith('preview')) return { stdout: '' };
  const rows = [];
  for (const api of runtime.apis) {
    const d = metric(data, `http_req_duration{api:${api.id}}`) || {};
    const f = metric(data, `http_req_failed{api:${api.id}}`) || {};
    const c = metric(data, `http_reqs{api:${api.id}}`) || {};
    rows.push({ api: api.id, name: api.name, requests: c.count || 0, rps: c.rate, failed: f.rate, avg: d.avg, p95: d['p(95)'], p99: d['p(99)'], max: d.max });
  }
  const failedThresholds = [];
  for (const [name, m] of Object.entries(data.metrics)) {
    for (const [rule, t] of Object.entries(m.thresholds || {})) if (!t.ok) failedThresholds.push(`${name}: ${rule}`);
  }
  const g = metric(data, 'http_req_duration') || {};
  const gf = metric(data, 'http_req_failed') || {};
  const gr = metric(data, 'http_reqs') || {};
  const header = `${pad('API', 32)} ${pad('requests', 9)} ${pad('rps', 8)} ${pad('failed', 8)} ${pad('avg ms', 9)} ${pad('p95 ms', 9)} ${pad('p99 ms', 9)} max ms`;
  const lines = [
    '',
    `Load test — mode ${mode}, data ${runtime.dataMode}, target ${runtime.baseUrl}`,
    `Total: ${gr.count || 0} requests, ${(gr.rate || 0).toFixed(1)} req/s, failed ${pct(gf.rate)}, p95 ${ms(g['p(95)'])} ms`,
    '',
    header,
    '-'.repeat(header.length),
    ...rows.map((r) => `${pad(r.api, 32)} ${pad(r.requests, 9)} ${pad(r.rps === undefined ? '-' : r.rps.toFixed(2), 8)} ${pad(pct(r.failed), 8)} ${pad(ms(r.avg), 9)} ${pad(ms(r.p95), 9)} ${pad(ms(r.p99), 9)} ${ms(r.max)}`),
    '',
    failedThresholds.length ? `Thresholds FAILED:\n  ${failedThresholds.join('\n  ')}` : 'All thresholds passed.',
    '',
  ];
  const chaos = resilienceRows(data, runtime);
  if (chaos.length) {
    const h = `${pad('experiment', 24)} ${pad('toxics', 22)} ${pad('during: reqs', 13)} ${pad('failed', 8)} ${pad('p95 ms', 9)} ${pad('after: reqs', 12)} ${pad('failed', 8)} ${pad('p95 ms', 9)} verdict`;
    lines.splice(lines.length - 2, 0, 'Resilience (faults injected through Toxiproxy):', h, '-'.repeat(h.length),
      ...chaos.map((r) => `${pad(r.experiment, 24)} ${pad(r.toxics, 22)} ${pad(r.during.requests, 13)} ${pad(pct(r.during.failed), 8)} ${pad(ms(r.during.p95), 9)} ${pad(r.after.requests, 12)} ${pad(pct(r.after.failed), 8)} ${pad(ms(r.after.p95), 9)} ${r.during.ok && r.after.ok ? 'PASS' : 'FAIL'}`), '');
  }
  const md = [
    `# Load test report — ${mode}`,
    '',
    `- Target: ${runtime.baseUrl}`,
    `- Data mode: ${runtime.dataMode}`,
    `- Requests: ${gr.count || 0} (${(gr.rate || 0).toFixed(1)} req/s), failed ${pct(gf.rate)}, p95 ${ms(g['p(95)'])} ms`,
    '',
    '| API | Endpoint | Requests | req/s | Failed | avg ms | p95 ms | p99 ms | max ms |',
    '|---|---|---:|---:|---:|---:|---:|---:|---:|',
    ...rows.map((r) => `| ${r.api} | \`${r.name}\` | ${r.requests} | ${r.rps === undefined ? '-' : r.rps.toFixed(2)} | ${pct(r.failed)} | ${ms(r.avg)} | ${ms(r.p95)} | ${ms(r.p99)} | ${ms(r.max)} |`),
    '',
    failedThresholds.length ? `**Thresholds failed:**\n\n${failedThresholds.map((t) => `- \`${t}\``).join('\n')}` : 'All thresholds passed.',
    '',
    ...(chaos.length ? ['## Resilience', '', '| Experiment | Proxy | Toxics | During: requests | failed | p95 ms | After: requests | failed | p95 ms | Verdict |', '|---|---|---|---:|---:|---:|---:|---:|---:|---|',
      ...chaos.map((r) => `| ${r.experiment} | ${r.proxy} | ${r.toxics} | ${r.during.requests} | ${pct(r.during.failed)} | ${ms(r.during.p95)} | ${r.after.requests} | ${pct(r.after.failed)} | ${ms(r.after.p95)} | ${r.during.ok && r.after.ok ? 'PASS' : 'FAIL'} |`), ''] : []),
  ].join('\n');
  const stamp = new Date().toISOString().replace(/[:.]/g, '-');
  const out = { stdout: lines.join('\n') };
  if (mode !== 'preview') {
    out[`reports/${mode}-${stamp}.json`] = JSON.stringify({ mode, dataMode: runtime.dataMode, baseUrl: runtime.baseUrl, apis: rows, resilience: chaos, failedThresholds, metrics: data.metrics }, null, 2);
    out[`reports/${mode}-${stamp}.md`] = md;
  }
  return out;
}
