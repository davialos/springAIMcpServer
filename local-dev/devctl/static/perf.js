// Performance (live service metrics), Load tests (setup wizard, runs, results) and Profiling (JFR) tabs.
// Uses the helpers defined in index.html: $, esc, api, act, toast, bd, S, tab, render.
const P = {svc: localStorage.perfSvc || '', pts: [], last: 0, win: +(localStorage.perfWin || 300), timer: null,
  ltRun: null, ltTimer: null, disc: null, jfrView: null};
const fmt = v => Charts.fmtNum(v);
const statusChip = (ok, label) => ok == null ? bd('n/a', '') : `<span class="st ${ok ? 'good' : 'crit'}">${ok ? '✓' : '✕'} ${esc(label)}</span>`;
const sevChip = s => `<span class="st ${s === 'CRITICAL' || s === 'RED' ? 'crit' : s === 'WARNING' || s === 'AMBER' ? 'warn' : s === 'GREEN' ? 'good' : 'info'}">${
  s === 'CRITICAL' || s === 'RED' ? '✕' : s === 'WARNING' || s === 'AMBER' ? '!' : s === 'GREEN' ? '✓' : 'i'} ${esc(s)}</span>`;
const bytes = b => b == null ? '–' : b >= 1073741824 ? (b / 1073741824).toFixed(2) + ' GB' : b >= 1048576 ? (b / 1048576).toFixed(1) + ' MB' : b >= 1024 ? (b / 1024).toFixed(0) + ' KB' : b + ' B';
const tile = (label, value, unit, extra) => `<div class="tile"><div class="mut">${esc(label)}</div><div class="hero">${value}<small> ${esc(unit || '')}</small></div>${extra || ''}</div>`;
const has = (pts, k) => pts.some(p => p[k] != null);
const LABELS = {rps: 'req/s', avg_ms: 'avg ms', p95_ms: 'p95 ms', p99_ms: 'p99 ms', err_pct: '5xx %', heap_mb: 'heap MB',
  cpu_pct: 'CPU %', threads: 'threads', gc_ms_s: 'GC ms/s', pool_pending: 'pool waiting', probe_ms: 'probe ms'};

// Chart catalogue shared by the live view and load-run results.
const CHARTS = [
  {title: 'Throughput', unit: 'req/s', series: [{key: 'rps', label: 'req/s', color: '--s1'}]},
  {title: 'Latency', unit: 'ms', series: [{key: 'avg_ms', label: 'avg', color: '--s1'}, {key: 'p95_ms', label: 'p95', color: '--s2'}]},
  {title: 'Server errors (5xx)', unit: '%', series: [{key: 'err_pct', label: '5xx %', color: '--s1'}]},
  {title: 'Heap used', unit: 'MB', series: [{key: 'heap_mb', label: 'heap', color: '--s1'}], ref: 'heap_max_mb'},
  {title: 'Process CPU', unit: '%', series: [{key: 'cpu_pct', label: 'cpu', color: '--s1'}]},
  {title: 'GC pause time', unit: 'ms/s', series: [{key: 'gc_ms_s', label: 'gc', color: '--s1'}]},
  {title: 'Live threads', unit: '', series: [{key: 'threads', label: 'threads', color: '--s1'}]},
  {title: 'DB connection pool', unit: 'conns', series: [{key: 'pool_active', label: 'active', color: '--s1'}, {key: 'pool_pending', label: 'waiting', color: '--s2'}]},
  {title: 'Tomcat busy threads', unit: '', series: [{key: 'tomcat_busy', label: 'busy', color: '--s1'}]},
  {title: 'Error log lines', unit: '/min', series: [{key: 'err_logs_min', label: 'errors', color: '--s1'}]},
  {title: 'Health probe latency', unit: 'ms', series: [{key: 'probe_ms', label: 'probe', color: '--s1'}]},
];
function drawCharts(host, pts, now) {
  const defs = CHARTS.map(c => ({...c, series: c.series.filter(s => has(pts, s.key))})).filter(c => c.series.length);
  host.innerHTML = defs.map((_, i) => `<div class="cbox" id="cb${i}"></div>`).join('') || '<p class="mut">No data yet.</p>';
  defs.forEach((c, i) => {
    const last = [...pts].reverse().find(p => c.ref && p[c.ref]);
    Charts.lineChart($('#cb' + i), {...c, points: pts, now, ref: last ? {value: last[c.ref], label: 'max'} : null,
      refNote: last ? ` of ${fmt(last[c.ref])} max` : ''});
  });
}

// ------------------------------------------------------------------ Performance
async function Performance() {
  const names = Object.keys(S.services);
  if (!P.svc || !names.includes(P.svc)) P.svc = names[0] || '';
  $('#main').innerHTML = `<div class="card row"><b>Service</b><select id="psvc">${names.map(n => `<option ${n === P.svc ? 'selected' : ''}>${esc(n)}</option>`).join('')}</select>
    <select id="pwin">${[[300, '5 min'], [900, '15 min'], [1800, '30 min']].map(([v, l]) => `<option value="${v}" ${v === P.win ? 'selected' : ''}>${l}</option>`).join('')}</select>
    <span id="psrc"></span><span style="flex:1"></span><button data-x="ready">Check readiness</button><button data-x="perf-lt">Load test this service</button></div>
    <div id="ptiles" class="tiles"></div><div id="pcharts" class="cgrid"></div>
    <div class="card scroll"><b>Busiest endpoints</b> <span class="mut">(last interval, from the service's own metrics)</span><table id="peps"></table></div>
    <div class="card" id="pready" hidden></div>`;
  if (!names.length) { $('#main').innerHTML = '<div class="card">No services in config.json</div>'; return; }
  $('#psvc').onchange = e => { P.svc = localStorage.perfSvc = e.target.value; P.pts = []; P.last = 0; Performance(); };
  $('#pwin').onchange = e => { P.win = +e.target.value; localStorage.perfWin = P.win; paintPerf(); };
  P.pts = []; P.last = 0;
  clearInterval(P.timer);
  const poll = async () => {
    if (tab !== 'Performance') return clearInterval(P.timer);
    try {
      const r = await api('GET', `/metrics/${encodeURIComponent(P.svc)}?since=${P.last}`);
      P.pts.push(...r.points); if (P.pts.length) P.last = P.pts[P.pts.length - 1].t;
      P.src = r.source; P.eps = r.endpoints; paintPerf();
    } catch (e) { toast(e.message, 1); }
  };
  await poll(); P.timer = setInterval(poll, 2000);
}
function paintPerf() {
  if (!$('#ptiles')) return;
  const now = Date.now() / 1000, pts = P.pts.filter(p => p.t >= now - P.win), L = pts[pts.length - 1] || {};
  const srcHelp = {prometheus: 'reading /actuator/prometheus', actuator: 'reading /actuator/metrics (no p95, no endpoint table)',
    probe: 'health probe only - add actuator for metrics', down: 'service not answering'};
  $('#psrc').innerHTML = `${bd(P.src || '…', P.src === 'prometheus' ? 'ok' : P.src === 'down' ? 'bad' : 'warn')} <span class="mut">${esc(srcHelp[P.src] || '')}</span>`;
  const lat = L.p95_ms != null ? ['p95 latency', L.p95_ms] : ['avg latency', L.avg_ms];
  $('#ptiles').innerHTML = tile('Status', statusChip(L.up, L.up ? 'up' : 'down'), '') + tile('Requests / s', fmt(L.rps), '') +
    tile(lat[0], fmt(lat[1]), 'ms') + tile('5xx', fmt(L.err_pct), '%') +
    tile('Heap', fmt(L.heap_mb), L.heap_max_mb ? `/ ${fmt(L.heap_max_mb)} MB` : 'MB') + tile('CPU', fmt(L.cpu_pct), '%');
  drawCharts($('#pcharts'), pts, now);
  const eps = P.eps || [];
  $('#peps').innerHTML = eps.length ? `<thead><tr><th>Endpoint</th><th>req/s</th><th>avg ms</th><th>max ms</th><th>5xx</th><th>total</th></tr></thead><tbody>${
    eps.map(e => `<tr><td><code>${esc(e.endpoint)}</code></td><td>${fmt(e.rps)}</td><td>${fmt(e.avg_ms)}</td><td>${fmt(e.max_ms)}</td><td>${e.errors}</td><td>${e.total}</td></tr>`).join('')}</tbody>`
    : '<tbody><tr><td class="mut">Needs /actuator/prometheus with HTTP traffic.</td></tr></tbody>';
}
async function showReadiness(svc, host) {
  host.hidden = false; host.innerHTML = 'checking…';
  const r = await api('GET', `/readiness/${encodeURIComponent(svc)}`);
  host.innerHTML = `<b>Readiness of ${esc(svc)}</b><table>${r.map(c => `<tr><td>${statusChip(c.ok, c.ok ? 'ok' : 'missing')}</td><td>${esc(c.check)}</td><td class="mut">${esc(c.detail)}</td><td>${c.ok ? '' : `<code>${esc(c.fix)}</code>`}</td></tr>`).join('')}</table>
  <details><summary>Minimal Spring Boot change (copy into the service)</summary><pre>${esc(SPRING_SNIPPET)}</pre></details>`;
}
const SPRING_SNIPPET = `<!-- pom.xml -->
<dependency><groupId>org.springframework.boot</groupId><artifactId>spring-boot-starter-actuator</artifactId></dependency>
<dependency><groupId>io.micrometer</groupId><artifactId>micrometer-registry-prometheus</artifactId><scope>runtime</scope></dependency>
<!-- optional, for endpoint discovery: org.springdoc:springdoc-openapi-starter-webmvc-ui -->

# src/main/resources/application-local.properties  (run with --spring.profiles.active=local)
management.endpoints.web.exposure.include=health,info,metrics,prometheus,mappings
management.metrics.distribution.percentiles-histogram.http.server.requests=true
management.endpoint.health.probes.enabled=true`;

// ------------------------------------------------------------------ Load tests
async function LoadTests() {
  if (P.ltRun) return showRun(P.ltRun);
  const [tests, runs] = await Promise.all([api('GET', '/loadtests'), api('GET', '/loadruns?limit=20')]);
  const svcs = Object.keys(S.services);
  $('#main').innerHTML = `<div class="card"><b>New load test</b>
    <div class="row" style="margin-top:8px"><select id="ltsvc">${svcs.map(n => `<option ${n === P.svc ? 'selected' : ''}>${esc(n)}</option>`).join('')}</select>
    <button data-x="disc">Discover endpoints</button><button data-x="ready-lt">Check readiness</button><span class="mut" id="ltsrc"></span></div>
    <div id="ltready" class="card" hidden></div><div id="lteps"></div></div>
    <div class="card scroll"><b>Load tests</b><table><thead><tr><th>Name</th><th>Service</th><th>Profile</th><th>VUs</th><th>Duration</th><th>Override</th><th></th></tr></thead><tbody>${
      tests.map(t => `<tr><td><b>${esc(t.name)}</b><div class="mut">${esc(t.script)}</div></td><td>${esc(t.service || '-')}</td><td>${esc(t.profile || '-')}</td><td>${esc(t.vus || '-')}</td><td>${esc(t.duration || '-')}</td>
      <td class="row"><input type="number" min="1" placeholder="VUs" style="width:70px" id="ov-${esc(t.name)}"><input placeholder="e.g. 2m" style="width:70px" id="od-${esc(t.name)}"></td>
      <td><button class="p" data-x="run" data-n="${esc(t.name)}">Run</button></td></tr>`).join('') || '<tr><td class="mut" colspan="7">None yet - create one above.</td></tr>'}</tbody></table></div>
    <div class="card scroll"><b>Runs</b><table><thead><tr><th>Run</th><th>Status</th><th>req/s</th><th>p95 ms</th><th>errors</th><th>JFR</th><th></th></tr></thead><tbody>${
      runs.map(r => { const k = r.k6 || {}, j = r.jfr || {}; return `<tr><td>${esc(r.id)}</td><td>${runChip(r.status)}</td><td>${fmt(k.rps)}</td><td>${fmt((k.latency_ms || {})['p(95)'])}</td>
      <td>${k.error_rate == null ? '–' : fmt(k.error_rate * 100) + ' %'}</td><td>${esc(j.status || '-')}${j.analysis && j.analysis.health ? ' ' + sevChip(j.analysis.health) : ''}</td>
      <td><button data-x="open-run" data-n="${esc(r.id)}">Open</button></td></tr>`; }).join('') || '<tr><td class="mut" colspan="7">No runs yet.</td></tr>'}</tbody></table></div>`;
}
const runChip = s => s === 'running' ? bd('running', 'warn') : s === 'passed' ? `<span class="st good">✓ passed</span>` :
  s === 'thresholds_failed' ? `<span class="st warn">! thresholds failed</span>` : s === 'stopped' ? bd('stopped', '') : `<span class="st crit">✕ ${esc(s)}</span>`;

function paintDiscovery(d) {
  const params = [...new Set(d.endpoints.flatMap(e => e.params))];
  $('#ltsrc').textContent = `${d.endpoints.length} endpoints from ${d.source} at ${d.base_url}`;
  $('#lteps').innerHTML = `<div class="scroll" style="max-height:320px;overflow:auto;margin-top:8px"><table><thead><tr><th><input type="checkbox" id="ltall"></th><th>Method</th><th>Path</th><th>Weight</th><th class="mut">Summary</th></tr></thead><tbody>${
    d.endpoints.map((e, i) => `<tr><td><input type="checkbox" class="ltep" data-i="${i}" ${e.method === 'GET' ? 'checked' : ''}></td><td><code>${esc(e.method)}</code></td><td><code>${esc(e.path)}</code></td>
    <td><input type="number" min="1" value="1" style="width:60px" id="ltw${i}"></td><td class="mut">${esc(e.summary)}</td></tr>`).join('')}</tbody></table></div>
    <p class="mut">Only GET endpoints are selected by default. Non-GET calls send no body unless you edit the generated script.</p>
    ${params.length ? `<p><b>Path values</b> <span class="mut">comma-separated values per parameter (picked at random)</span></p>${params.map(p => `<div class="row"><code style="min-width:120px">{${esc(p)}}</code><input data-param="${esc(p)}" placeholder="1,2,3" style="flex:1"></div>`).join('')}` : ''}
    <div class="row" style="margin-top:10px"><input id="ltname" placeholder="name e.g. orders-load" value="${esc(P.svc || 'svc')}-load">
    <select id="ltprof">${['smoke', 'load', 'stress', 'spike', 'soak'].map(p => `<option ${p === 'load' ? 'selected' : ''}>${p}</option>`).join('')}</select>
    <label>VUs <input id="ltvus" type="number" value="10" min="1" style="width:70px"></label><label>hold <input id="ltdur" value="1m" style="width:60px"></label>
    <label>p95 &lt; <input id="ltp95" type="number" value="500" style="width:70px"> ms</label><label>errors &lt; <input id="lterr" type="number" value="1" step="0.1" style="width:60px"> %</label>
    <button class="p" data-x="create">Create load test</button></div>`;
  $('#ltall').onchange = e => document.querySelectorAll('.ltep').forEach(c => c.checked = e.target.checked);
}

async function showRun(id) {
  clearInterval(P.ltTimer);
  const paint = async () => {
    if (tab !== 'Load tests' || P.ltRun !== id) return clearInterval(P.ltTimer);
    const [m, tl] = await Promise.all([api('GET', `/loadruns/${id}`), api('GET', `/loadruns/${id}/timeline`)]);
    const k = m.k6 || {}, lat = k.latency_ms || {}, j = m.jfr || {}, ss = m.service_summary || {};
    const el = ((m.finished || Date.now() / 1000) - m.started), pct = m.expected_seconds ? Math.min(100, el / m.expected_seconds * 100) : null;
    const logEl = $('#rlog'), logOff = logEl ? +logEl.dataset.off : 0, logTxt = logEl ? logEl.textContent : '';
    $('#main').innerHTML = `<div class="card row"><button data-x="back">← All runs</button><b>${esc(m.test)}</b> ${runChip(m.status)} <span class="mut">${esc(m.phase || '')}</span>
      <span style="flex:1"></span><span class="mut">${Math.round(el)}s${m.expected_seconds ? ' / ~' + Math.round(m.expected_seconds) + 's' : ''}</span>
      ${m.status === 'running' ? `<button class="d" data-x="stop-run" data-n="${esc(id)}">Stop</button>` : ''}</div>
      ${pct != null && m.status === 'running' ? `<div class="bar"><i style="width:${pct.toFixed(1)}%"></i></div>` : ''}
      ${m.error ? `<div class="card"><span class="st crit">✕ error</span> ${esc(m.error)}</div>` : ''}
      <div class="tiles">${tile('Requests', fmt(k.requests), '') + tile('Throughput', fmt(k.rps), 'req/s') + tile('p95 (client)', fmt(lat['p(95)']), 'ms') +
        tile('p99 (client)', fmt(lat['p(99)']), 'ms') + tile('HTTP errors', k.error_rate == null ? '–' : fmt(k.error_rate * 100), '%') + tile('Checks passed', k.checks_rate == null ? '–' : fmt(k.checks_rate * 100), '%')}</div>
      ${Object.keys(ss).length ? `<div class="card scroll"><b>Service side during the run</b> <span class="mut">(${esc(m.service)}, sampled every 2 s)</span><table><thead><tr><th></th>${Object.keys(ss).map(x => `<th>${esc(LABELS[x] || x)}</th>`).join('')}</tr></thead>
        <tbody><tr><td class="mut">avg</td>${Object.values(ss).map(v => `<td>${fmt(v.avg)}</td>`).join('')}</tr><tr><td class="mut">peak</td>${Object.values(ss).map(v => `<td>${fmt(v.max)}</td>`).join('')}</tr></tbody></table></div>` : ''}
      <div id="rcharts" class="cgrid"></div>
      <div class="card"><b>JFR profile</b> ${j.status ? esc(j.status) : '<span class="mut">not recorded (load test has no service, or jfr off)</span>'} ${esc(j.reason || '')}
        ${j.recording ? `<button data-x="jfr-open" data-n="${esc(j.recording)}">View analysis</button><a href="/files/jfr?id=${encodeURIComponent(j.recording)}&f=report.html" target="_blank" rel="noopener">full report</a>` : ''}
        ${j.analysis ? ` ${j.analysis.health ? sevChip(j.analysis.health) : ''} ${j.analysis.score != null ? 'score ' + j.analysis.score : ''} <span class="mut">${esc(j.analysis.headline || j.analysis.status || '')}</span>` : ''}</div>
      ${k.thresholds && k.thresholds.length ? `<div class="card"><b>Thresholds</b> <span class="mut">${m.status === 'thresholds_failed' ? 'at least one failed (k6 exit 99)' : 'all passed'}</span><ul>${k.thresholds.map(t => `<li><code>${esc(t)}</code></li>`).join('')}</ul></div>` : ''}
      <div class="card"><b>Run log</b><pre id="rlog" data-off="${logOff}">${esc(logTxt)}</pre></div>`;
    drawCharts($('#rcharts'), tl, m.finished || undefined);
    const r = await api('GET', `/loadruns/${id}/log?offset=${logOff}`), pre = $('#rlog');
    if (pre) { pre.textContent += r.text; pre.dataset.off = r.offset; pre.scrollTop = pre.scrollHeight; }
    if (m.status !== 'running' && !(j.analysis && j.analysis.status === 'running')) clearInterval(P.ltTimer);
  };
  await paint(); P.ltTimer = setInterval(paint, 2000);
}

// ------------------------------------------------------------------ Profiling
async function Profiling() {
  if (P.jfrView) return showJfr(P.jfrView);
  const [jvms, recs] = await Promise.all([api('GET', '/jfr/jvms').catch(e => ({error: e.message})), api('GET', '/jfr')]);
  const anChip = a => a.status === 'success' ? (a.health ? sevChip(a.health) + (a.score != null ? ` <span class="mut">score ${a.score}</span>` : '') : bd('analyzed', 'ok'))
    : a.status === 'running' ? bd('analyzing…', 'warn') : a.status === 'failed' ? `<span class="st crit">✕ analysis failed</span>` : bd('not analyzed', '');
  $('#main').innerHTML = `<div class="card scroll"><b>Local JVMs</b> <span class="mut">devctl-started JVMs record continuously (jfr.auto); others can be recorded by pid</span>
    ${jvms.error ? `<p class="bad">${esc(jvms.error)}</p>` : `<table><thead><tr><th>PID</th><th>Service</th><th>Main</th><th>Actions</th></tr></thead><tbody>${
      jvms.map(v => `<tr><td>${v.pid}</td><td>${v.service ? `<b>${esc(v.service)}</b>` : '<span class="mut">external</span>'}</td><td class="mut">${esc(v.main)}</td>
      <td class="row">${v.service ? `<button class="p" data-x="snap" data-n="${esc(v.service)}">Snapshot</button>` : ''}
      <input type="number" value="60" min="5" style="width:70px" id="sec-${v.pid}"><button data-x="rec" data-n="${v.pid}" data-s="${esc(v.service || '')}">Record s</button></td></tr>`).join('') || '<tr><td class="mut">No JVMs running.</td></tr>'}</tbody></table>`}</div>
    <div class="card scroll"><b>Recordings</b><table><thead><tr><th>Service</th><th>Kind</th><th>When</th><th>Size</th><th>Analysis</th><th></th></tr></thead><tbody>${
      recs.map(r => `<tr><td>${esc(r.service)}</td><td>${esc(r.kind || '-')}${r.loadrun ? `<div class="mut">${esc(r.loadrun)}</div>` : ''}</td><td>${new Date(r.created * 1000).toLocaleString()}</td>
      <td>${r.pending ? bd(`recording, ${r.ready_in}s left`, 'warn') : bytes(r.size)}</td><td>${r.pending ? '' : anChip(r.analysis)}<div class="mut">${esc(r.analysis.headline || '')}</div></td>
      <td class="row">${r.pending ? '' : `${r.analysis.status === 'success' ? `<button class="p" data-x="jfr-open" data-n="${esc(r.id)}">View</button><a href="/files/jfr?id=${encodeURIComponent(r.id)}&f=report.html" target="_blank" rel="noopener">full report</a><a href="/files/jfr?id=${encodeURIComponent(r.id)}&f=report.xlsx">xlsx</a>` : ''}
      <button data-x="analyze" data-n="${esc(r.id)}">${r.analysis.status === 'none' ? 'Analyze' : 'Re-analyze'}</button>${r.analysis.status === 'failed' ? `<a href="/files/jfr?id=${encodeURIComponent(r.id)}&f=analyzer.log" target="_blank">log</a>` : ''}`}</td></tr>`).join('') || '<tr><td class="mut" colspan="6">No recordings yet.</td></tr>'}</tbody></table></div>`;
}

async function showJfr(id) {
  const s = await api('GET', `/jfr/summary?id=${encodeURIComponent(id)}`).catch(e => ({error: e.message}));
  if (s.error) { $('#main').innerHTML = `<div class="card"><button data-x="jfr-back">← Recordings</button> ${esc(s.error)}</div>`; return; }
  const ex = s.executiveSummary || {}, rec = s.recording || {}, gc = s.gc || {}, mem = s.memory || {};
  const hsTable = (title, h) => !h || !h.hotspots || !h.hotspots.length ? '' : `<div class="card scroll"><b>${esc(title)}</b> <span class="mut">${fmt(h.percentInPackages)}% reached through your packages · unit ${esc(h.unit)}</span>
    <table><tbody>${h.hotspots.map(x => `<tr><td>${x.rank}</td><td><code>${esc(x.location || x.method)}</code>${x.topDetail ? `<div class="mut">paid in ${esc(x.topDetail)}</div>` : ''}</td>
    <td style="width:35%"><div class="pbar"><i style="width:${Math.min(100, x.percent || 0)}%"></i></div></td><td>${fmt(x.percent)}%</td></tr>`).join('')}</tbody></table></div>`;
  const hs = s.topHotspots || {};
  $('#main').innerHTML = `<div class="card row"><button data-x="jfr-back">← Recordings</button><b>${esc(rec.file)}</b><span class="mut">${esc(rec.duration)} · JVM ${esc(rec.jvmVersion)} · ${esc(rec.youngCollector)}/${esc(rec.oldCollector)}</span>
      <span style="flex:1"></span><a href="/files/jfr?id=${encodeURIComponent(id)}&f=report.html" target="_blank" rel="noopener">Full interactive report</a><a href="/files/jfr?id=${encodeURIComponent(id)}&f=report.xlsx">Excel</a></div>
    <div class="card banner ${ex.status === 'RED' ? 'crit' : ex.status === 'AMBER' ? 'warn' : ex.status === 'GREEN' ? 'good' : ''}">${sevChip(ex.status || 'UNKNOWN')}
      <span class="hero" style="margin:0 12px">${ex.healthScore ?? '–'}<small> / 100</small></span><span>${esc(ex.headline || '')}</span></div>
    <div class="tiles">${(ex.keyMetrics || []).map(k => `<div class="tile" title="${esc(k.meaning)}"><div class="mut">${esc(k.name)}</div><div class="hero">${esc(k.display)}</div>${sevChip(k.status)}</div>`).join('')}</div>
    ${(ex.topIssues || []).length ? `<div class="card"><b>Top issues</b>${ex.topIssues.map(i => `<div class="issue">${sevChip(i.severity)} <b>${esc(i.title)}</b> <span class="mut">${esc(i.area)}</span>
      <div>${esc(i.impact)}</div><div><b>Do:</b> ${esc(i.action)}</div>${i.location ? `<code>${esc(i.location)}</code>` : ''}</div>`).join('')}</div>` : ''}
    ${(ex.recommendations || []).length ? `<div class="card"><b>Recommendations</b><ol>${ex.recommendations.map(r => `<li>${esc(r)}</li>`).join('')}</ol></div>` : ''}
    <div class="cgrid">${hsTable('CPU hot spots', hs.cpu)}${hsTable('Allocation hot spots', hs.allocation)}${hsTable('Lock contention', hs.lockContention)}${hsTable('Parked / waiting', hs.parked)}${hsTable('Exceptions', hs.exceptions)}</div>
    <div class="cgrid"><div class="card"><b>GC</b><table><tbody>${[['collections', gc.collections], ['per minute', fmt(gc.collectionsPerMinute)], ['total pause', fmt(gc.totalPauseMs) + ' ms'],
      ['p95 / p99 / max pause', `${fmt(gc.p95PauseMs)} / ${fmt(gc.p99PauseMs)} / ${fmt(gc.maxPauseMs)} ms`], ['overhead', fmt(gc.overheadPercent) + ' %']].map(([a, b]) => `<tr><td class="mut">${a}</td><td>${b ?? '–'}</td></tr>`).join('')}</tbody></table></div>
    <div class="card"><b>Memory</b><table><tbody>${[['heap max', bytes(mem.heapMaxBytes)], ['heap peak used', bytes(mem.heapPeakUsedBytes)], ['live set (avg after GC)', bytes(mem.heapAfterGcAvgBytes)],
      ['live set growth', bytes(mem.heapAfterGcGrowthBytesPerMin) + '/min'], ['allocation rate', bytes(mem.allocationRateBytesPerSec) + '/s']].map(([a, b]) => `<tr><td class="mut">${a}</td><td>${b}</td></tr>`).join('')}
      ${(mem.topAllocatedTypes || []).map(t => `<tr><td><code>${esc(t.type)}</code></td><td>${fmt(t.percent)}%</td></tr>`).join('')}</tbody></table></div></div>
    ${(s.findings || []).length ? `<div class="card"><b>All findings</b>${s.findings.map(f => `<div class="issue">${sevChip(f.severity)} <b>${esc(f.title)}</b> <span class="mut">${esc(f.category)}</span><div>${esc(f.detail)}</div>${f.location ? `<code>${esc(f.location)}</code>` : ''}</div>`).join('')}</div>` : ''}`;
}

// ------------------------------------------------------------------ actions for the three tabs
document.addEventListener('click', async e => {
  const b = e.target.closest('[data-x]'); if (!b) return;
  const x = b.dataset.x, n = b.dataset.n;
  try {
    if (x === 'ready') return showReadiness(P.svc, $('#pready'));
    if (x === 'ready-lt') return showReadiness($('#ltsvc').value, $('#ltready'));
    if (x === 'perf-lt') { tab = localStorage.tab = 'Load tests'; P.ltRun = null; return render(); }
    if (x === 'disc') { const svc = $('#ltsvc').value; P.svc = svc; $('#ltsrc').textContent = 'discovering…';
      P.disc = await api('GET', `/loadtests/discover?service=${encodeURIComponent(svc)}`); return paintDiscovery(P.disc); }
    if (x === 'create') {
      const eps = [...document.querySelectorAll('.ltep')].filter(c => c.checked).map(c => { const ep = P.disc.endpoints[+c.dataset.i];
        return {method: ep.method, path: ep.path, weight: +$('#ltw' + c.dataset.i).value || 1}; });
      const pv = {}; document.querySelectorAll('[data-param]').forEach(i => { if (i.value.trim()) pv[i.dataset.param] = i.value.split(',').map(s => s.trim()).filter(Boolean); });
      const r = await api('POST', '/loadtests', {name: $('#ltname').value.trim(), service: $('#ltsvc').value, endpoints: eps, profile: $('#ltprof').value,
        vus: +$('#ltvus').value, duration: $('#ltdur').value.trim(), path_values: pv, p95_ms: +$('#ltp95').value, max_error_rate: (+$('#lterr').value) / 100});
      toast(`created ${r.name} (${r.endpoints} endpoints)`); P.disc = null; return LoadTests();
    }
    if (x === 'run') { const m = await api('POST', `/loadtests/${encodeURIComponent(n)}/run`, {vus: +($('#ov-' + n).value || 0), duration: $('#od-' + n).value.trim()});
      toast('load test started'); P.ltRun = m.id; return showRun(m.id); }
    if (x === 'open-run') { P.ltRun = n; return showRun(n); }
    if (x === 'back') { P.ltRun = null; clearInterval(P.ltTimer); return LoadTests(); }
    if (x === 'stop-run') { await api('POST', `/loadruns/${n}/stop`); return toast('stopping - k6 writes its summary, JFR is saved'); }
    if (x === 'snap') { await api('POST', '/jfr/snapshot', {service: n}); toast('snapshot saved, analysis started'); return Profiling(); }
    if (x === 'rec') { const r = await api('POST', '/jfr/record', {pid: +n, service: b.dataset.s, seconds: +($('#sec-' + n).value || 60)});
      toast(`recording ${r.ready_in_seconds}s`); return Profiling(); }
    if (x === 'analyze') { await api('POST', '/jfr/analyze', {id: n}); toast('analysis started'); return Profiling(); }
    if (x === 'jfr-open') { tab = localStorage.tab = 'Profiling'; P.jfrView = n; return render(); }
    if (x === 'jfr-back') { P.jfrView = null; return Profiling(); }
  } catch (err) { toast(err.message, 1); }
});
// refresh the Profiling list while something is recording or being analyzed
setInterval(() => { if (tab === 'Profiling' && !P.jfrView && document.querySelector('#main .badge.warn')) Profiling(); }, 4000);
