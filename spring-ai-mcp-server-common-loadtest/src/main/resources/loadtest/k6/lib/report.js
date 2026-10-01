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

export function summary(data, runtime, mode) {
  if (mode === 'preview') return { stdout: '' };
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
  ].join('\n');
  const stamp = new Date().toISOString().replace(/[:.]/g, '-');
  const out = { stdout: lines.join('\n') };
  if (mode !== 'preview') {
    out[`reports/${mode}-${stamp}.json`] = JSON.stringify({ mode, dataMode: runtime.dataMode, baseUrl: runtime.baseUrl, apis: rows, failedThresholds, metrics: data.metrics }, null, 2);
    out[`reports/${mode}-${stamp}.md`] = md;
  }
  return out;
}
