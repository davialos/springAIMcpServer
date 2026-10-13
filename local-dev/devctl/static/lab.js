// API Lab window: projects, discovered APIs, threads between response and request attributes, fake data, workflows,
// runs, and an AI drawer that drives the same apilab_* tools the MCP server exposes. Everything is stored as JSON files.
const $ = (s, e = document) => e.querySelector(s);
const esc = s => String(s ?? '').replace(/[&<>"]/g, c => ({'&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;'}[c]));
const api = async (m, p, b) => {
  const r = await fetch('/api' + p, {method: m, headers: {'X-Devctl': '1', 'Content-Type': 'application/json'}, body: b ? JSON.stringify(b) : undefined});
  const j = await r.json(); if (!r.ok) throw new Error(j.error || r.status); return j;
};
const toast = (t, bad) => { const d = document.createElement('div'); d.textContent = t; if (bad) d.style.borderLeftColor = 'var(--bad)'; $('#msg').append(d); setTimeout(() => d.remove(), bad ? 9000 : 3500); };
const L = {p: localStorage.labProject || '', apis: [], links: [], sel: null, tab: localStorage.labTab || 'Threads', showSuggested: true,
  showAll: false, minConf: 0.8, armed: null, wf: null, run: null, dataApi: null, aiSid: sessionStorage.labAiSid || '', aiLog: []};
const TABS = ['Threads', 'APIs', 'Data', 'Workflows', 'Runs'];
const byId = id => L.apis.find(a => a.id === id);
const mchip = m => `<span class="m ${esc(m)}">${esc(m)}</span>`;
const short = f => f.length > 34 ? '…' + f.slice(-33) : f;

// ------------------------------------------------------------------ projects
async function loadProjects() {
  const ps = await api('GET', '/lab/projects');
  if (!ps.find(p => p.name === L.p)) L.p = ps[0] ? ps[0].name : '';
  $('#proj').innerHTML = ps.map(p => `<option ${p.name === L.p ? 'selected' : ''}>${esc(p.name)}</option>`).join('') || '<option value="">no projects</option>';
  if (L.p) await loadProject(); else { $('#apilist').innerHTML = ''; $('#view').innerHTML = `<div class="empty">Create a project for a repo or a running service, then <b>Discover APIs</b>.<br><br><button class="p" onclick="newProject()">New project</button></div>`; }
}
async function loadProject() {
  localStorage.labProject = L.p;
  const [info, g] = await Promise.all([api('GET', `/lab/${L.p}`), api('GET', `/lab/${L.p}/graph`)]);
  L.info = info; L.apis = g.apis; L.links = g.links;
  const acc = L.links.filter(l => l.status === 'accepted').length;
  $('#pinfo').textContent = `${info.base_url || 'no base_url'} · ${info.repo ? info.repo.split('/').pop() : 'no repo'} · ${L.apis.length} APIs · ${acc}/${L.links.length} threads accepted`;
  renderList(); render();
}
async function newProject() {
  const [repos, cfg] = await Promise.all([api('GET', '/repos'), api('GET', '/config')]);
  showDetail(`<div class="sec">New API Lab project</div>
    <p><input id="np-name" placeholder="name e.g. orders" style="width:100%"></p>
    <p>Repository (Java sources)<br><select id="np-repo" style="width:100%"><option value="">-</option>${repos.map(r => `<option>${esc(r.name)}</option>`).join('')}</select></p>
    <p>Running service (OpenAPI / requests)<br><select id="np-svc" style="width:100%"><option value="">-</option>${Object.keys(cfg.services).map(s => `<option>${esc(s)}</option>`).join('')}</select></p>
    <p>or base URL<br><input id="np-url" placeholder="http://localhost:8080" style="width:100%"></p>
    <p>Default headers (JSON, e.g. auth)<br><input id="np-h" placeholder='{"Authorization":"Bearer …"}' style="width:100%"></p>
    <button class="p" id="np-go">Create</button> <span class="mut">Files: apilab_dir/&lt;name&gt;/</span>`);
  $('#np-go').onclick = async () => {
    try {
      const h = $('#np-h').value.trim();
      const p = await api('POST', '/lab/projects', {name: $('#np-name').value.trim(), repo: $('#np-repo').value, service: $('#np-svc').value,
        base_url: $('#np-url').value.trim(), headers: h ? JSON.parse(h) : null});
      L.p = p.name; closeDetail(); await loadProjects(); toast('project created - now Discover APIs');
    } catch (e) { toast(e.message, 1); }
  };
}
window.newProject = newProject;

// ------------------------------------------------------------------ side list
function renderList() {
  const q = ($('#q').value || '').toLowerCase();
  const inCount = id => L.links.filter(l => l.to.api === id && l.status === 'accepted').length;
  $('#apilist').innerHTML = L.apis.filter(a => !q || (a.path + a.id + a.method).toLowerCase().includes(q)).map(a =>
    `<div class="api ${L.sel === a.id ? 'sel' : ''}" data-api="${esc(a.id)}">${mchip(a.method)}<span class="mono">${esc(a.path)}</span>
     <div class="mut">${esc(a.id)} · ${a.request.length} in / ${a.response.length} out${inCount(a.id) ? ` · ${inCount(a.id)} threads` : ''}</div></div>`).join('')
    || '<div class="empty">No APIs yet</div>';
}

// ------------------------------------------------------------------ tabs
function render() {
  $('#tabs').innerHTML = TABS.map(t => `<button class="${t === L.tab ? 'on' : ''}" data-tab="${t}">${t}</button>`).join('');
  if (!L.p) return;
  ({Threads: renderThreads, APIs: renderApis, Data: renderData, Workflows: renderWorkflows, Runs: renderRuns})[L.tab]();
}

// ------------------------------------------------------------------ Threads (graph)
function visibleLinks() {
  return L.links.filter(l => l.status === 'accepted' || (L.showSuggested && l.status === 'suggested' && (l.confidence ?? 1) >= L.minConf));
}
function renderThreads() {
  const vis = visibleLinks();
  const ids = new Set(); vis.forEach(l => { ids.add(l.from.api); ids.add(l.to.api); });
  if (L.showAll || !vis.length) L.apis.forEach(a => ids.add(a.id));
  if (L.sel) ids.add(L.sel);
  const nodes = L.apis.filter(a => ids.has(a.id));
  // columns: longest path over ACCEPTED threads (a DAG by intent); APIs without accepted threads go by role
  const rank = {POST: 0, PUT: 1, PATCH: 1, GET: 2, DELETE: 3};
  const acc = L.links.filter(l => l.status === 'accepted' && l.from.api !== l.to.api);
  const inAcc = new Set(acc.flatMap(l => [l.from.api, l.to.api]));
  const level = Object.fromEntries(nodes.map(a => [a.id, inAcc.has(a.id) ? 0 : (rank[a.method] ?? 2)]));
  for (let i = 0; i < nodes.length; i++) {
    let changed = false;
    acc.forEach(l => { if (level[l.from.api] !== undefined && level[l.to.api] !== undefined && level[l.to.api] < level[l.from.api] + 1 && level[l.from.api] < 8) { level[l.to.api] = level[l.from.api] + 1; changed = true; } });
    if (!changed) break;
  }
  const cols = {}; nodes.forEach(a => (cols[level[a.id]] = cols[level[a.id]] || []).push(a));
  const W = 270, GAP = 150;
  $('#view').innerHTML = `<div class="bar"><span class="legend"><span><i style="background:var(--s1)"></i>accepted</span><span><i style="background:var(--s2);opacity:.6"></i>suggested</span></span>
    <label><input type="checkbox" id="t-sug" ${L.showSuggested ? 'checked' : ''}> suggested ≥ <input id="t-min" type="number" min="0" max="1" step="0.05" value="${L.minConf}" style="width:62px"></label>
    <label><input type="checkbox" id="t-all" ${L.showAll ? 'checked' : ''}> all fields &amp; APIs</label>
    <span class="mut">${L.armed ? `linking from <b>${esc(L.armed.api)}.${esc(L.armed.field)}</b> - click a request field (Esc cancels)` : 'click a response field (right), then a request field (left) to link them'}</span>
    <span style="flex:1"></span><button id="t-acc">Accept all ≥ 0.9</button></div><div id="canvas"></div>`;
  const cv = $('#canvas');
  const used = (a, side) => {
    const fs = a[side];
    if (L.showAll) return fs;
    const key = side === 'request' ? 'to' : 'from';
    const set = new Set(vis.filter(l => l[key].api === a.id).map(l => l[key].field));
    const out = fs.filter(f => set.has(f.path));
    if (L.armed && side === 'request') return fs;  // when linking, every request field is a target
    return out.length ? out : fs.slice(0, side === 'request' ? 4 : 3);
  };
  let maxH = 0;
  Object.keys(cols).sort((a, b) => a - b).forEach((lv, ci) => {
    let y = 16;
    cols[lv].sort((a, b) => a.path.localeCompare(b.path)).forEach(a => {
      const req = used(a, 'request'), res = used(a, 'response');
      const el = document.createElement('div');
      el.className = 'node' + (L.sel === a.id ? ' sel' : ''); el.style.left = (16 + ci * (W + GAP)) + 'px'; el.style.top = y + 'px';
      el.innerHTML = `<h4 data-api="${esc(a.id)}" title="${esc(a.id)}">${mchip(a.method)}${esc(a.path)}</h4><div class="ports">
        <div><div class="lbl">request</div>${req.map(f => `<div class="port in" data-api="${esc(a.id)}" data-field="${esc(f.path)}" title="${esc(f.path)} : ${esc(f.type)}${f.format ? ' (' + esc(f.format) + ')' : ''}">● ${esc(short(f.path))}</div>`).join('') || '<div class="lbl">-</div>'}
          ${!L.showAll && a.request.length > req.length ? `<div class="lbl">+${a.request.length - req.length} more</div>` : ''}</div>
        <div><div class="lbl" style="text-align:right">response</div>${res.map(f => `<div class="port out ${L.armed && L.armed.api === a.id && L.armed.field === f.path ? 'armed' : ''}" data-api="${esc(a.id)}" data-field="${esc(f.path)}" title="${esc(f.path)} : ${esc(f.type)}">${esc(short(f.path))} ●</div>`).join('') || '<div class="lbl" style="text-align:right">-</div>'}
          ${!L.showAll && a.response.length > res.length ? `<div class="lbl" style="text-align:right">+${a.response.length - res.length} more</div>` : ''}</div></div>`;
      cv.appendChild(el);
      y += el.offsetHeight + 18;
    });
    maxH = Math.max(maxH, y);
  });
  const ncol = Object.keys(cols).length;
  cv.style.width = (32 + ncol * (W + GAP)) + 'px'; cv.style.height = maxH + 'px';
  drawThreads(cv, vis);
  $('#t-sug').onchange = e => { L.showSuggested = e.target.checked; renderThreads(); };
  $('#t-min').onchange = e => { L.minConf = +e.target.value; renderThreads(); };
  $('#t-all').onchange = e => { L.showAll = e.target.checked; renderThreads(); };
  $('#t-acc').onclick = async () => {
    const ids = L.links.filter(l => l.status === 'suggested' && l.confidence >= 0.9).map(l => l.id);
    if (!ids.length) return toast('nothing ≥ 0.9 to accept');
    await api('POST', `/lab/${L.p}/links/status`, {ids, status: 'accepted'}); toast(`${ids.length} threads accepted`); loadProject();
  };
}
function drawThreads(cv, vis) {
  const NS = 'http://www.w3.org/2000/svg';
  const svg = document.createElementNS(NS, 'svg'); svg.setAttribute('class', 'threads');
  svg.setAttribute('width', cv.scrollWidth); svg.setAttribute('height', cv.scrollHeight);
  const box = cv.getBoundingClientRect();
  const port = (side, a, f) => cv.querySelector(`.port.${side}[data-api="${CSS.escape(a)}"][data-field="${CSS.escape(f)}"]`);
  const tip = document.createElement('div'); tip.className = 'tip'; tip.hidden = true; document.body.appendChild(tip);
  vis.forEach(l => {
    const o = port('out', l.from.api, l.from.field), i = port('in', l.to.api, l.to.field);
    if (!o || !i) return;
    const ro = o.getBoundingClientRect(), ri = i.getBoundingClientRect();
    const x1 = ro.right - box.left, y1 = ro.top + ro.height / 2 - box.top, x2 = ri.left - box.left, y2 = ri.top + ri.height / 2 - box.top;
    const dx = Math.max(60, Math.abs(x2 - x1) / 2);
    const p = document.createElementNS(NS, 'path');
    p.setAttribute('d', `M${x1},${y1} C${x1 + dx},${y1} ${x2 - dx},${y2} ${x2},${y2}`);
    const acc = l.status === 'accepted', backward = x2 <= x1;  // backward = suggestion against the producer->consumer flow
    p.setAttribute('stroke', acc ? 'var(--s1)' : 'var(--s2)'); p.setAttribute('stroke-width', acc ? 2 : 1.5);
    p.setAttribute('stroke-opacity', acc ? 0.9 : backward ? 0.25 : 0.55); p.setAttribute('stroke-linecap', 'round');
    p.addEventListener('mouseenter', ev => { p.setAttribute('stroke-width', 4); tip.hidden = false;
      tip.innerHTML = `<b>${esc(l.from.api)}.${esc(l.from.field)}</b> → <b>${esc(l.to.api)}.${esc(l.to.field)}</b><br>${esc(l.status)} · ${l.confidence ?? ''} · ${esc(l.source)}<br><span class="mut">${esc(l.reason || '')}</span>`;
      tip.style.left = (ev.clientX + 12) + 'px'; tip.style.top = (ev.clientY + 12) + 'px'; });
    p.addEventListener('mouseleave', () => { p.setAttribute('stroke-width', acc ? 2 : 1.5); tip.hidden = true; });
    p.addEventListener('click', () => { tip.hidden = true; showLink(l); });
    svg.appendChild(p);
  });
  cv.appendChild(svg);
}
function showLink(l) {
  showDetail(`<div class="sec">Thread</div><p><span class="mono">${esc(l.from.api)}</span><br>response <code>${esc(l.from.field)}</code></p>
    <p style="font-size:20px;margin:0">↓</p><p><span class="mono">${esc(l.to.api)}</span><br>request <code>${esc(l.to.field)}</code></p>
    <p>${esc(l.status)} · confidence ${l.confidence ?? '-'} · ${esc(l.source)}</p><p class="mut">${esc(l.reason || '')}</p>
    <p><button class="p" data-ls="accepted">Accept</button> <button data-ls="rejected">Reject</button> <button data-ls="suggested">Undecided</button> <button class="d" data-ls="delete">Delete</button></p>
    <p class="mut">Accepting makes this the only accepted producer for that request field; workflows bind it automatically.</p>`);
  $('#detail').querySelectorAll('[data-ls]').forEach(b => b.onclick = async () => {
    await api('POST', `/lab/${L.p}/links/status`, {ids: [l.id], status: b.dataset.ls}); toast(b.dataset.ls); closeDetail(); loadProject();
  });
}

// ------------------------------------------------------------------ APIs (fields + rules)
function renderApis() {
  if (!L.sel) { $('#view').innerHTML = `<div class="empty">Select an API on the left.</div>`; return; }
  showApi(L.sel, true);
}
const KINDS = ['', 'email', 'first_name', 'last_name', 'full_name', 'username', 'password', 'phone', 'company', 'city', 'country', 'iso_country', 'street', 'zip',
  'uuid', 'date', 'datetime', 'past_date', 'future_date', 'time', 'url', 'word', 'words', 'sentence', 'paragraph', 'int', 'decimal', 'money', 'bool',
  'currency', 'sku', 'code', 'ip', 'color', 'iban', 'lat', 'lon'];
async function showApi(id, inView) {
  const d = await api('GET', `/lab/${L.p}/apis/${encodeURIComponent(id)}`);
  const linkIn = Object.fromEntries(d.links_in.filter(l => l.status === 'accepted').map(l => [l.to.field, l]));
  const cons = f => ['format', 'enum', 'min', 'max', 'minLength', 'maxLength', 'pattern'].filter(k => f[k] !== undefined).map(k => `${k}=${Array.isArray(f[k]) ? f[k].join('|') : f[k]}`).join(' ');
  const html = `<div class="bar">${mchip(d.method)}<b class="mono">${esc(d.path)}</b><span class="mut">${esc(d.id)} · ${esc(d.source)}</span>
      <span style="flex:1"></span><input type="number" id="a-n" value="20" style="width:70px"><button class="p" id="a-gen">Generate</button></div>
    <div style="padding:10px"><div class="sec">Request fields &amp; fake-data rules</div>
    <table><thead><tr><th>field</th><th>type</th><th>constraints</th><th>rule</th><th></th></tr></thead><tbody>${d.request.map(f => {
      const r = d.rules[f.path] || {}; const lk = linkIn[f.path];
      return `<tr><td class="mono">${esc(f.path)}${f.required ? ' <span class="chip">req</span>' : ''}</td><td>${esc(f.type)}</td><td class="mut mono">${esc(cons(f))}</td>
      <td>${lk ? `<span class="link">⟵ ${esc(lk.from.api)}.${esc(lk.from.field)}</span>` : `<select data-k="${esc(f.path)}">${KINDS.map(k => `<option ${r.kind === k ? 'selected' : ''} value="${k}">${k || 'auto'}</option>`).join('')}</select>
        <input data-x="${esc(f.path)}" placeholder="enum a|b · pattern ORD-#### · template · const" value="${esc(r.enum ? r.enum.join('|') : r.pattern ? 'pattern ' + r.pattern : r.template ? 'template ' + r.template : r.const !== undefined ? 'const ' + r.const : '')}" style="width:100%;margin-top:3px">`}</td>
      <td>${lk ? '' : `<button data-save="${esc(f.path)}">Save</button>`}</td></tr>`; }).join('') || '<tr><td colspan="5" class="mut">no request fields</td></tr>'}</tbody></table>
    <div class="sec">Response fields</div><div class="mono">${d.response.map(f => esc(f.path) + ' <span class="mut">' + esc(f.type) + '</span>').join('<br>') || '<span class="mut">none known</span>'}</div>
    <div class="sec">Threads</div>${d.links_in.map(l => `<div class="link">⟵ ${esc(l.from.api)}.${esc(l.from.field)} → ${esc(l.to.field)} <span class="mut">(${esc(l.status)})</span></div>`).join('')}
      ${d.links_out.map(l => `<div class="link">${esc(l.from.field)} → ${esc(l.to.api)}.${esc(l.to.field)} <span class="mut">(${esc(l.status)})</span></div>`).join('') || (d.links_in.length ? '' : '<span class="mut">none</span>')}
    <div class="sec">Sample payload</div><pre>${d.sample ? esc(JSON.stringify(d.sample, null, 2)) : 'no data yet - Generate'}</pre></div>`;
  if (inView) $('#view').innerHTML = html; else showDetail(html);
  const host = inView ? $('#view') : $('#detail');
  host.querySelector('#a-gen').onclick = async () => { await api('POST', `/lab/${L.p}/generate`, {api: id, count: +host.querySelector('#a-n').value || 20}); toast('generated'); loadProject(); };
  host.querySelectorAll('[data-save]').forEach(b => b.onclick = async () => {
    const path = b.dataset.save, kind = host.querySelector(`[data-k="${CSS.escape(path)}"]`).value, x = host.querySelector(`[data-x="${CSS.escape(path)}"]`).value.trim();
    const rule = {}; if (kind) rule.kind = kind;
    if (x.startsWith('pattern ')) rule.pattern = x.slice(8); else if (x.startsWith('template ')) rule.template = x.slice(9);
    else if (x.startsWith('const ')) rule.const = x.slice(6); else if (x) rule.enum = x.split('|').map(s => s.trim()).filter(Boolean);
    try { const r = await api('POST', `/lab/${L.p}/rules/${encodeURIComponent(id)}`, {rules: {[path]: rule}}); toast('rule saved'); host.querySelector('pre').textContent = JSON.stringify(r.preview, null, 2); }
    catch (e) { toast(e.message, 1); }
  });
}

// ------------------------------------------------------------------ Data
async function renderData() {
  const withData = L.apis.filter(a => a.request.length);
  L.dataApi = L.dataApi && byId(L.dataApi) ? L.dataApi : (L.sel && byId(L.sel) ? L.sel : (withData[0] || {}).id);
  if (!L.dataApi) { $('#view').innerHTML = '<div class="empty">No APIs with request data.</div>'; return; }
  const d = await api('GET', `/lab/${L.p}/data/${encodeURIComponent(L.dataApi)}?limit=200`);
  const flat = (o, p = '', out = {}) => { if (o && typeof o === 'object' && !Array.isArray(o) && !('$link' in o)) Object.keys(o).forEach(k => flat(o[k], p ? p + '.' + k : k, out)); else out[p] = o; return out; };
  const rows = d.rows.map(r => flat({path: r.path, query: r.query, body: r.body}));
  const cols = [...new Set(rows.flatMap(r => Object.keys(r)))].filter(c => rows.some(r => r[c] !== undefined && !(typeof r[c] === 'object' && r[c] && !('$link' in r[c]) && !Object.keys(r[c]).length)));
  const cell = v => v && typeof v === 'object' && '$link' in v ? `<span class="link">⟵ ${esc(v.$link)}</span>` : esc(typeof v === 'object' ? JSON.stringify(v) : v);
  $('#view').innerHTML = `<div class="bar"><select id="d-api">${withData.map(a => `<option value="${esc(a.id)}" ${a.id === L.dataApi ? 'selected' : ''}>${esc(a.method)} ${esc(a.path)}</option>`).join('')}</select>
    <span class="mut">${d.total} rows · <code>${esc(d.file)}</code></span><span style="flex:1"></span>
    <input type="number" id="d-n" value="${d.total || 20}" style="width:70px"><button class="p" id="d-gen">Regenerate</button><button id="d-all">Generate all APIs</button><label><input type="checkbox" id="d-json"> JSON</label></div>
    <div id="d-body" style="padding:10px;overflow:auto">${d.total ? `<table><thead><tr><th>#</th>${cols.map(c => `<th class="mono">${esc(c)}</th>`).join('')}</tr></thead><tbody>${rows.map((r, i) => `<tr><td>${i + 1}</td>${cols.map(c => `<td class="mono">${cell(r[c])}</td>`).join('')}</tr>`).join('')}</tbody></table>` : '<div class="empty">No data yet - Regenerate.</div>'}</div>`;
  $('#d-api').onchange = e => { L.dataApi = e.target.value; renderData(); };
  $('#d-gen').onclick = async () => { await api('POST', `/lab/${L.p}/generate`, {api: L.dataApi, count: +$('#d-n').value || 20}); renderData(); };
  $('#d-all').onclick = async () => { const r = await api('POST', `/lab/${L.p}/generate`, {count: +$('#d-n').value || 20}); toast(`generated for ${Object.keys(r.generated).length} APIs`); renderData(); };
  $('#d-json').onchange = e => { if (e.target.checked) $('#d-body').innerHTML = `<pre style="max-height:none">${esc(JSON.stringify(d.rows, null, 2))}</pre>`; else renderData(); };
}

// ------------------------------------------------------------------ Workflows
async function renderWorkflows() {
  const wfs = await api('GET', `/lab/${L.p}/workflows`);
  if (!L.wf || !wfs.find(w => w.id === L.wf)) L.wf = wfs[0] ? wfs[0].id : null;
  const wf = wfs.find(w => w.id === L.wf);
  $('#view').innerHTML = `<div class="bar"><select id="w-sel">${wfs.map(w => `<option ${w.id === L.wf ? 'selected' : ''}>${esc(w.id)}</option>`).join('') || '<option value="">no workflows</option>'}</select>
    <button class="p" id="w-auto">Auto from accepted threads</button><button id="w-edit" ${wf ? '' : 'disabled'}>Edit JSON</button><button id="w-new">New (JSON)</button>
    <span style="flex:1"></span>${wf ? `<input type="number" id="w-it" value="1" min="1" style="width:60px"><button class="p" id="w-run">Run</button>
    <select id="w-prof">${['smoke', 'load', 'stress', 'spike', 'soak'].map(p => `<option ${p === 'load' ? 'selected' : ''}>${p}</option>`).join('')}</select><input id="w-vus" type="number" value="10" style="width:60px" title="VUs"><input id="w-dur" value="1m" style="width:55px"><button id="w-k6">Export k6</button>` : ''}</div>
    <div id="w-body" style="padding:14px 18px;position:relative"></div>`;
  $('#w-sel').onchange = e => { L.wf = e.target.value; L.run = null; renderWorkflows(); };
  $('#w-auto').onclick = async () => { try { const w = await api('POST', `/lab/${L.p}/workflows/auto`, {}); L.wf = w.id; toast(`workflow ${w.id}: ${w.steps.length} steps`); renderWorkflows(); } catch (e) { toast(e.message, 1); } };
  const editor = w => { showDetail(`<div class="sec">Workflow JSON</div><textarea id="w-json" style="width:100%;height:60vh" class="mono">${esc(JSON.stringify(w, null, 2))}</textarea>
      <p><button class="p" id="w-save">Save</button> <span class="mut">bind: {"body.customerId": "{{s1.response.id}}"}</span></p>`);
    $('#w-save').onclick = async () => { try { const s = await api('POST', `/lab/${L.p}/workflows`, JSON.parse($('#w-json').value)); L.wf = s.id; closeDetail(); renderWorkflows(); toast('saved'); } catch (e) { toast(e.message, 1); } }; };
  $('#w-new').onclick = () => editor({id: 'my-flow', name: 'My flow', think_time_ms: 300, steps: [{id: 's1', api: (L.apis[0] || {}).id, bind: {}}]});
  if (!wf) { $('#w-body').innerHTML = '<div class="empty">Accept some threads, then <b>Auto from accepted threads</b> - or ask the AI to design the business flow.</div>'; return; }
  $('#w-edit').onclick = () => editor(wf);
  $('#w-run').onclick = async () => { $('#w-run').disabled = true; try { const r = await api('POST', `/lab/${L.p}/workflows/${wf.id}/run`, {iterations: +$('#w-it').value || 1});
    toast(`run ${r.ok} ok / ${r.failed} failed`, r.failed > 0); L.run = await api('GET', `/lab/${L.p}/runs/${r.id}`); loadProject(); } catch (e) { toast(e.message, 1); } $('#w-run').disabled = false; };
  $('#w-k6').onclick = async () => { try { const r = await api('POST', `/lab/${L.p}/workflows/${wf.id}/k6`, {profile: $('#w-prof').value, vus: +$('#w-vus').value, duration: $('#w-dur').value});
    toast(`load test ${r.loadtest} registered - run it in Dashboard → Load tests`); } catch (e) { toast(e.message, 1); } };
  const last = L.run && L.run.workflow === wf.id ? L.run.iterations[L.run.iterations.length - 1] : null;
  const res = sid => last ? last.find(s => s.step === sid) : null;
  $('#w-body').innerHTML = wf.steps.map((st, i) => { const a = byId(st.api) || {method: '?', path: st.api}; const r = res(st.id);
    return `<div class="step" data-step="${esc(st.id)}"><span class="ok">${r ? (r.ok ? `<span class="st good">✓ ${r.status} · ${r.ms} ms</span>` : `<span class="st crit">✕ ${r.status || r.error}</span>`) : ''}</span>
      <b>${esc(st.id)}</b> ${mchip(a.method)}<span class="mono">${esc(a.path)}</span> <span class="mut">${esc(st.api)}</span>
      ${Object.keys(st.bind || {}).map(f => `<div class="bind"><span class="tgt" data-f="${esc(f)}">${esc(f)}</span> ⟵ <span class="link src">${esc(st.bind[f])}</span>${r && r.bound && r.bound[f] ? ` = <b>${esc(JSON.stringify(r.bound[f].value))}</b>` : ''}</div>`).join('') || '<div class="mut bind">no bindings</div>'}
      ${r ? `<details><summary class="mut">request / response</summary><pre>${esc(JSON.stringify({url: r.url, body: r.request.body, query: r.request.query}, null, 2))}</pre><pre>${esc(JSON.stringify(r.response, null, 2))}</pre>${(r.warnings || []).map(w => `<div class="mut">⚠ ${esc(w)}</div>`).join('')}</details>` : ''}</div>`; }).join('');
  // threads between step cards: from the producing step's card to the binding line
  const body = $('#w-body'), NS = 'http://www.w3.org/2000/svg', box = body.getBoundingClientRect();
  const svg = document.createElementNS(NS, 'svg'); svg.setAttribute('class', 'threads'); svg.setAttribute('width', body.scrollWidth); svg.setAttribute('height', body.scrollHeight);
  body.querySelectorAll('.bind').forEach(b => {
    const m = (b.querySelector('.src') || {}).textContent?.match(/\{\{\s*(s\w+)\./); if (!m) return;
    const from = body.querySelector(`.step[data-step="${CSS.escape(m[1])}"]`); if (!from) return;
    const rf = from.getBoundingClientRect(), rb = b.getBoundingClientRect();
    const x1 = rf.right - box.left - 4, y1 = rf.top + 14 - box.top, x2 = rb.left - box.left - 2, y2 = rb.top + rb.height / 2 - box.top;
    const p = document.createElementNS(NS, 'path'); const bend = Math.max(40, (y2 - y1) / 3);
    const xe = rf.right - box.left - 4;  // the bind line's own card edge; the dot marks which line it feeds
    p.setAttribute('d', `M${x1},${y1} C${x1 + bend},${y1} ${xe + bend},${y2} ${xe},${y2}`);
    p.setAttribute('stroke', 'var(--s1)'); p.setAttribute('stroke-width', 2); p.setAttribute('fill', 'none'); p.setAttribute('stroke-opacity', .7);
    const dot = document.createElementNS(NS, 'circle'); dot.setAttribute('cx', xe); dot.setAttribute('cy', y2); dot.setAttribute('r', 4);
    dot.setAttribute('fill', 'var(--s1)'); dot.setAttribute('stroke', 'var(--card)'); dot.setAttribute('stroke-width', 2);
    svg.appendChild(p); svg.appendChild(dot);
  });
  body.appendChild(svg);
}

// ------------------------------------------------------------------ Runs
async function renderRuns() {
  const rs = await api('GET', `/lab/${L.p}/runs`);
  $('#view').innerHTML = `<div style="padding:10px"><table><thead><tr><th>run</th><th>workflow</th><th>ok</th><th>failed</th><th>per step (avg ms)</th><th></th></tr></thead><tbody>${rs.map(r =>
    `<tr><td class="mono">${esc(r.id)}</td><td>${esc(r.workflow)}</td><td>${r.ok}</td><td>${r.failed ? `<span class="st crit">${r.failed}</span>` : 0}</td>
    <td class="mono">${Object.entries(r.summary || {}).map(([k, v]) => `${esc(k)}:${esc(v.api)} ${v.ok}/${v.calls} ${v.avg_ms}`).join('<br>')}</td><td><button data-run="${esc(r.id)}">Open</button></td></tr>`).join('') || '<tr><td colspan="6" class="mut">No runs yet - run a workflow.</td></tr>'}</tbody></table></div>`;
  $('#view').querySelectorAll('[data-run]').forEach(b => b.onclick = async () => {
    const r = await api('GET', `/lab/${L.p}/runs/${b.dataset.run}`);
    showDetail(`<div class="sec">${esc(r.id)}</div>${r.iterations.map((it, i) => `<div class="sec">iteration ${i + 1}</div>${it.map(s => `<details><summary>${s.ok ? '✓' : '✕'} ${esc(s.step)} ${mchip(s.method)} ${esc(s.status || s.error)} · ${s.ms} ms</summary>
      <div class="mono mut">${esc(s.url)}</div><pre>${esc(JSON.stringify(s.request, null, 2))}</pre><pre>${esc(JSON.stringify(s.response, null, 2))}</pre></details>`).join('')}`).join('')}`);
  });
}

// ------------------------------------------------------------------ detail drawer
function showDetail(html) { $('#detail').innerHTML = `<div style="text-align:right"><button onclick="closeDetail()">✕</button></div>` + html; $('#detail').classList.add('open'); }
function closeDetail() { $('#detail').classList.remove('open'); }
window.closeDetail = closeDetail;

// ------------------------------------------------------------------ AI drawer (same assistant + tools as the dashboard)
const QUICK = () => [
  ['Decide threads', `For API Lab project "${L.p}": review the APIs and suggested threads (apilab_apis, apilab_api, apilab_links) and decide which response attribute really feeds which request attribute. Accept the right ones, reject wrong ones, add missing ones - each with a reason. Then summarise the relationships.`],
  ['Realistic data', `For API Lab project "${L.p}": set realistic fake-data rules (apilab_set_rules) for every request field of every API - formats, enums, ranges, business codes, consistent naming - then apilab_generate 20 rows each and show me one example per API.`],
  ['Design workflow', `For API Lab project "${L.p}": design the main business workflow (apilab_workflow_save) using the accepted threads, run it once (apilab_workflow_run), fix rules/threads until every step passes, and explain the flow.`],
  ['Explain failures', `For API Lab project "${L.p}": look at the latest workflow run (apilab_runs) and explain every failing step with the request and response; propose and apply fixes to rules or threads.`],
  ['Load test it', `For API Lab project "${L.p}": export the best workflow to k6 (apilab_export_k6, profile load) and start it with loadtest_run; tell me the run id.`],
];
const md = t => esc(t).replace(/`([^`\n]+)`/g, '<code>$1</code>').replace(/\*\*([^*\n]+)\*\*/g, '<b>$1</b>').replace(/^[-*] (.*)$/gm, '• $1').replace(/\n/g, '<br>');
function paintAi() {
  $('#aiq').innerHTML = QUICK().map(([l], i) => `<button data-q="${i}">${esc(l)}</button>`).join('');
  $('#aichat').innerHTML = L.aiLog.map((e, i) => e.type === 'user' ? `<div class="msgb user">${esc(e.text)}</div>` : e.type === 'text' ? `<div class="msgb ai">${md(e.text)}</div>`
    : e.type === 'tool' ? `<div class="tool mut">▸ ${esc(e.name)} ${esc(JSON.stringify(e.input)).slice(0, 140)}</div>`
    : e.type === 'result' ? `<details class="tool"><summary class="mut">${e.error ? '✕' : '✓'} ${esc(e.name)}</summary><pre>${esc(e.text)}</pre></details>`
    : e.type === 'pending' ? `<div class="msgb ai"><b>Approve?</b>${e.calls.map(c => `<div class="mono">${esc(c.name)} ${esc(JSON.stringify(c.input)).slice(0, 200)}</div>`).join('')}${e.resolved ? `<div class="mut">${e.resolved}</div>` : `<button class="p" data-ap="1" data-i="${i}">Approve</button> <button data-ap="0" data-i="${i}">Decline</button>`}</div>`
    : `<div class="msgb ai" style="border-color:var(--crit)">${esc(e.text)}</div>`).join('') + (L.aiBusy ? '<div class="mut">working…</div>' : '');
  $('#aichat').scrollTop = $('#aichat').scrollHeight;
}
async function aiTalk(path, body) {
  L.aiBusy = true; paintAi();
  try { const r = await api('POST', path, body); L.aiSid = r.session; sessionStorage.labAiSid = L.aiSid; L.aiLog.push(...r.events); if (r.done) loadProject(); }
  catch (e) { L.aiLog.push({type: 'error', text: e.message}); }
  L.aiBusy = false; paintAi();
}
async function aiSend(text) { if (!text || L.aiBusy) return; L.aiLog.push({type: 'user', text}); await aiTalk('/assistant/chat', {session: L.aiSid || null, message: text}); }

// ------------------------------------------------------------------ events
document.addEventListener('click', async e => {
  const t = e.target;
  const tab = t.closest('[data-tab]'); if (tab) { L.tab = localStorage.labTab = tab.dataset.tab; closeDetail(); return render(); }
  const item = t.closest('.api[data-api], .node h4[data-api]');
  if (item) { L.sel = item.dataset.api; renderList(); if (L.tab === 'APIs') return renderApis(); if (L.tab === 'Threads') { showApi(L.sel, false); return renderThreads(); } return showApi(L.sel, false); }
  const port = t.closest('.port');
  if (port) {
    if (port.classList.contains('out')) { L.armed = {api: port.dataset.api, field: port.dataset.field}; return renderThreads(); }
    if (L.armed && port.classList.contains('in')) {
      try { await api('POST', `/lab/${L.p}/links`, {from_api: L.armed.api, from_field: L.armed.field, to_api: port.dataset.api, to_field: port.dataset.field, reason: 'linked by hand in API Lab'});
        toast('thread added'); } catch (err) { toast(err.message, 1); }
      L.armed = null; return loadProject();
    }
  }
  const q = t.closest('[data-q]'); if (q) return aiSend(QUICK()[+q.dataset.q][1]);
  const ap = t.closest('[data-ap]'); if (ap) { L.aiLog[+ap.dataset.i].resolved = ap.dataset.ap === '1' ? 'approved' : 'declined'; return aiTalk('/assistant/decide', {session: L.aiSid, approve: ap.dataset.ap === '1'}); }
});
document.addEventListener('keydown', e => { if (e.key === 'Escape' && L.armed) { L.armed = null; renderThreads(); } });
$('#proj').onchange = e => { L.p = e.target.value; L.sel = null; L.wf = null; L.run = null; loadProject(); };
$('#newp').onclick = newProject;
$('#q').oninput = renderList;
$('#disc').onclick = async () => {
  if (!L.p) return newProject();
  $('#disc').disabled = true;
  try { const r = await api('POST', `/lab/${L.p}/discover`, {source: 'auto'}); toast(`${r.apis} APIs from ${r.source}, ${r.suggested_links} thread suggestions`); await loadProject(); }
  catch (err) { toast(err.message, 1); }
  $('#disc').disabled = false;
};
$('#aibtn').onclick = async () => { $('#ai').classList.toggle('open'); if ($('#ai').classList.contains('open')) {
  const s = await api('GET', '/assistant/status').catch(() => ({}));
  $('#aistatus').textContent = s.ready ? s.model : (s.hint || 'assistant not ready - agents can use the apilab_* MCP tools instead');
  paintAi(); } };
$('#aisend').onclick = () => { const v = $('#aitxt').value.trim(); $('#aitxt').value = ''; aiSend(v); };
$('#aitxt').addEventListener('keydown', e => { if (e.key === 'Enter' && !e.shiftKey) { e.preventDefault(); $('#aisend').click(); } });
$('#aireset').onclick = async () => { if (L.aiSid) await api('POST', '/assistant/reset', {session: L.aiSid}).catch(() => 0); L.aiSid = ''; L.aiLog = []; paintAi(); };
window.addEventListener('resize', () => { if (L.tab === 'Threads' && L.p) renderThreads(); });
loadProjects().catch(e => toast(e.message, 1));
