// Assistant tab (Claude with the devctl tools; write/destroy calls wait for approval) and Agents tab (MCP setup).
// Uses helpers from index.html: $, esc, api, toast, bd, tab.
const A = {sid: sessionStorage.aiSid || '', log: JSON.parse(sessionStorage.aiLog || '[]'), busy: false, st: null};
const saveA = () => { sessionStorage.aiSid = A.sid; sessionStorage.aiLog = JSON.stringify(A.log.slice(-200)); };
const md = t => esc(t)
  .replace(/```([\s\S]*?)```/g, (_, c) => `<pre>${c.replace(/^\w*\n/, '')}</pre>`)
  .replace(/`([^`\n]+)`/g, '<code>$1</code>').replace(/\*\*([^*\n]+)\*\*/g, '<b>$1</b>')
  .replace(/^#{1,4} (.*)$/gm, '<b>$1</b>').replace(/^[-*] (.*)$/gm, '• $1').replace(/\n/g, '<br>');
const effectChip = e => e === 'destroy' ? `<span class="st crit">✕ destroy</span>` : e === 'write' ? `<span class="st warn">! write</span>` : `<span class="st info">read</span>`;
const SUGGEST = ['What services do I have and are they healthy?', 'Why is my slowest service slow? Use the latest profile.',
  'Run a smoke load test against the first service and summarize it.', 'Onboard a repo from my workspace as a service.'];

async function Assistant() {
  A.st = await api('GET', '/assistant/status').catch(e => ({ready: false, hint: e.message}));
  $('#main').innerHTML = `<div class="card row"><b>Assistant</b><span class="mut">${esc(A.st.model || '')} · effort ${esc(A.st.effort || '')} ·
    ${A.st.confirm_writes ? 'asks before changing anything' : 'runs actions without asking'}</span><span style="flex:1"></span><button data-ai="reset">New conversation</button></div>
    ${A.st.ready ? '' : `<div class="card"><span class="st warn">! not ready</span> ${esc(A.st.hint || '')}<p class="mut">The MCP server works without this - see the Agents tab.</p></div>`}
    <div class="card chat" id="chat"></div>
    <div class="card"><div class="row" id="sugg">${SUGGEST.map(s => `<button class="chip" data-ai="sugg">${esc(s)}</button>`).join('')}</div>
    <div class="row" style="margin-top:8px"><textarea id="aiq" rows="2" placeholder="Ask about your local services, builds, logs, load tests, profiles… (Enter to send, Shift+Enter for a new line)" style="flex:1;resize:vertical"></textarea>
    <button class="p" data-ai="send" ${A.st.ready ? '' : 'disabled'}>Send</button></div></div>`;
  paintChat();
  $('#aiq').addEventListener('keydown', e => { if (e.key === 'Enter' && !e.shiftKey) { e.preventDefault(); send(); } });
}
function paintChat() {
  const box = $('#chat'); if (!box) return;
  box.innerHTML = A.log.map((e, i) => {
    if (e.type === 'user') return `<div class="msg user">${esc(e.text)}</div>`;
    if (e.type === 'text') return `<div class="msg ai">${md(e.text)}</div>`;
    if (e.type === 'tool') return `<div class="tool">▸ ${effectChip(e.effect)} <code>${esc(e.name)}</code> <span class="mut">${esc(JSON.stringify(e.input))}</span></div>`;
    if (e.type === 'result') return `<details class="tool"><summary class="${e.error ? 'bad' : 'mut'}">${e.error ? '✕' : '✓'} ${esc(e.name)} result</summary><pre>${esc(e.text)}</pre></details>`;
    if (e.type === 'pending') return `<div class="pending card"><b>Claude wants to run:</b>${e.calls.map(c => `<div>${effectChip(c.effect)} <code>${esc(c.name)}</code> <span class="mut">${esc(JSON.stringify(c.input))}</span></div>`).join('')}
      ${e.resolved ? `<div class="mut">${esc(e.resolved)}</div>` : `<div class="row" style="margin-top:6px"><button class="p" data-ai="approve" data-i="${i}">Approve</button><button data-ai="decline" data-i="${i}">Decline</button></div>`}</div>`;
    if (e.type === 'error') return `<div class="msg err"><span class="st crit">✕</span> ${esc(e.text)}</div>`;
    return '';
  }).join('') + (A.busy ? '<div class="msg ai mut">working…</div>' : '') || '<p class="mut">Ask anything about your local environment. Claude uses the same tools your coding agent gets over MCP.</p>';
  box.scrollTop = box.scrollHeight;
}
async function talk(path, body) {
  A.busy = true; paintChat();
  try {
    const r = await api('POST', path, body);
    A.sid = r.session; A.log.push(...r.events);
  } catch (e) { A.log.push({type: 'error', text: e.message}); }
  A.busy = false; saveA(); paintChat();
}
async function send(text) {
  const q = text || ($('#aiq') && $('#aiq').value.trim()); if (!q || A.busy) return;
  if ($('#aiq')) $('#aiq').value = '';
  A.log.push({type: 'user', text: q}); await talk('/assistant/chat', {session: A.sid || null, message: q});
}
document.addEventListener('click', async e => {
  const b = e.target.closest('[data-ai]'); if (!b) return;
  const x = b.dataset.ai;
  if (x === 'send') return send();
  if (x === 'sugg') return send(b.textContent);
  if (x === 'reset') { if (A.sid) await api('POST', '/assistant/reset', {session: A.sid}).catch(() => 0); A.sid = ''; A.log = []; saveA(); return paintChat(); }
  if (x === 'approve' || x === 'decline') {
    const ev = A.log[+b.dataset.i]; ev.resolved = x === 'approve' ? 'approved' : 'declined';
    return talk('/assistant/decide', {session: A.sid, approve: x === 'approve'});
  }
  if (x === 'copy') { navigator.clipboard.writeText($('#ag-' + b.dataset.n).textContent).then(() => toast('copied'), () => toast('copy failed', 1)); }
});

async function Agents() {
  const r = await api('GET', '/agents');
  const order = ['claude-code', 'cursor', 'antigravity', 'vscode', 'claude-desktop', 'windsurf', 'gemini-cli', 'codex', 'http'];
  const label = {'claude-code': 'Claude Code', cursor: 'Cursor', antigravity: 'Antigravity', vscode: 'VS Code (Copilot agent)', 'claude-desktop': 'Claude Desktop',
    windsurf: 'Windsurf', 'gemini-cli': 'Gemini CLI', codex: 'OpenAI Codex CLI', http: 'Any agent with an MCP URL'};
  $('#main').innerHTML = `<div class="card"><b>Connect a coding agent</b><p>The agent starts <code>${esc(r.mcp_bin)}</code> (stdio) - nothing else needs to run.
    URL-based agents can use <code>${esc(r.http_url)}</code> while this dashboard runs. One command installs it: <code>local-dev/bin/devctl agent-setup &lt;agent&gt; --install</code>.
    Full guide: <code>local-dev/docs/mcp-agents.md</code>.</p></div>
    <div class="cgrid">${order.filter(k => r.snippets[k]).map(k => { const s = r.snippets[k]; return `<div class="card"><div class="row"><b>${esc(label[k] || k)}</b><span style="flex:1"></span><button data-ai="copy" data-n="${k}">Copy</button></div>
      <div class="mut">${esc(s.path)}</div><pre id="ag-${k}">${esc(s.rendered)}</pre>${s.note ? `<div class="mut">${esc(s.note)}</div>` : ''}</div>`; }).join('')}</div>
    <div class="card"><b>Try it</b><p class="mut">Ask your agent: "use devctl - what services do I have and are they healthy?" · "ship branch feature/x of orders as orders-api" ·
    "load test orders-api and tell me the slowest code line". Prompts (slash commands in most agents): ship-branch, investigate-service, performance-check, onboard-project.</p></div>`;
}
