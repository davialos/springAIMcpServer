// Standalone mock backend for trying <saimcp-chat> without a Spring host: `npm run demo` (or
// `node demo/mock-server.mjs [port]`), then open http://localhost:8099/. It speaks the same dai-stream/1 protocol and
// supporting APIs as the library (LLD-13 §3) with scripted answers. Development aid only: not packaged in the jar.
//
// Try: "show me everything", "which order?", "fail please", or anything else.

import { createServer } from 'node:http';
import { readFile } from 'node:fs/promises';
import { randomUUID } from 'node:crypto';
import { extname, join, normalize } from 'node:path';
import { fileURLToPath } from 'node:url';

const port = Number(process.argv[2] || process.env.PORT || 8099);
const here = fileURLToPath(new URL('.', import.meta.url));
const assets = join(here, '..', 'src', 'main', 'resources', 'META-INF', 'resources');
const TYPES = { '.js': 'text/javascript', '.html': 'text/html', '.css': 'text/css', '.json': 'application/json' };

// conversationId -> { messages: [], components: Map, feedback: Map }
const conversations = new Map();

function conversation(id) {
  if (!conversations.has(id)) conversations.set(id, { messages: [], components: new Map(), feedback: new Map() });
  return conversations.get(id);
}

const SHOWCASE = `## Open orders overview

Here is what I found for **ACME Corp** — 3 open orders, one of them *overdue*.

| Order | Status | Total (EUR) | Promised |
|:------|:------:|------------:|----------|
| PO-1001 | open | 1,250.00 | 2026-10-08 |
| PO-1002 | ~~shipped~~ open | 89.90 | 2026-10-02 |
| PO-1003 | open | 12,400.00 | 2026-10-20 |

### Next steps
1. Confirm the delivery date of **PO-1002**
   - it is 4 days late
   - the customer was notified on Oct 3
2. Review the credit limit
   - [x] limit checked
   - [ ] approval requested

The rule that flags an order as overdue, in CEL:

\`\`\`cel
// overdue when promised in the past and not yet shipped
order.status in ["open", "packed"] &&
  timestamp(order.promisedOn) < now &&
  !has(order.shippedAt) && size(order.lines) > 0u
\`\`\`

And the same check in the service:

\`\`\`java
@Transactional(readOnly = true)
public List<Order> overdue(Instant now) {
    return orders.findByStatusIn(List.of(OPEN, PACKED)).stream()
            .filter(o -> o.promisedOn().isBefore(now) && o.shippedAt() == null)
            .toList();
}
\`\`\`

How an order moves through the process:

\`\`\`mermaid
flowchart LR
  A[Open] --> B{Credit OK?}
  B -- yes --> C[Packed]
  B -- no --> D[On hold]
  C --> E[Shipped]
  D --> B
\`\`\`

> Amounts include VAT. See the [order policy](https://example.com/policy) for details.

---
Questions? Inline \`code\`, **bold**, *italic* and links like https://example.com all work.`;

const DISPLAY = {
  version: 1,
  blocks: [
    { type: 'text', text: 'Open orders overview' },
    { type: 'fields', title: 'Customer', items: [
      { key: 'name', label: 'Name', format: 'text', value: 'ACME Corp' },
      { key: 'email', label: 'E-mail', format: 'text', value: '[redacted email]' },
      { key: 'creditLimit', label: 'Credit limit', format: 'number', value: 50000 },
    ] },
  ],
  redactions: { EMAIL: 1 },
  masked: 0,
};

function sse(res, turnId, seq, data) {
  res.write(`id: ${turnId}:${seq}\nevent: ${data.type}\ndata: ${JSON.stringify(data)}\n\n`);
}

const sleep = (ms) => new Promise((r) => setTimeout(r, ms));

async function streamText(res, send, text, chunk = 18, delay = 25) {
  for (let i = 0; i < text.length; i += chunk) {
    send({ type: 'text.delta', seq: i, text: text.slice(i, i + chunk) });
    await sleep(delay);
  }
}

async function handleStream(req, res, body) {
  const conversationId = body.conversationId || randomUUID();
  const turnId = randomUUID();
  const conv = conversation(conversationId);
  conv.messages.push({ role: 'USER', content: body.message, turnId });
  res.writeHead(200, { 'Content-Type': 'text/event-stream', 'Cache-Control': 'no-cache, no-transform' });
  let seq = 0;
  let closed = false;
  req.on('close', () => { closed = true; });
  const send = (data) => { if (!closed) sse(res, turnId, seq++, data); };
  send({ type: 'turn.start', turnId, conversationId, agent: 'order-helper', revision: 3, protocol: 'dai-stream/1',
    ui: { steps: true, feedback: true, copy: true, choices: true } });
  send({ type: 'step', stepId: 'check', title: 'Checked your request', status: 'done' });
  const msg = String(body.message || '').toLowerCase();
  let answer;
  if (msg.includes('fail')) {
    await sleep(300);
    send({ type: 'error', errorType: '/errors/agent/model-unavailable', title: 'The AI service is temporarily unavailable.',
      code: 'model-unavailable', retryable: true, turnId });
    res.end();
    return;
  }
  if (msg.startsWith('my answer to')) {
    answer = `Thanks — ${body.message.replace(/^my answer to "[^"]*":\s*/i, '**')}** it is. It was promised for Oct 2 and is `
      + 'now **4 days late**; I can draft a note to the customer if you like.';
    await streamText(res, send, answer);
  } else if (msg.includes('which order') || msg.includes('choose')) {
    send({ type: 'tool.call', callId: 'tool-1', tool: 'find_orders', argsPreview: '{"customer":"ACME","status":"open"}' });
    await sleep(500);
    send({ type: 'tool.result', callId: 'tool-1', status: 'ok', summary: '3 Orders returned.' });
    const payload = { componentId: 'choice-1', question: 'Which order do you mean?', multiple: false, allowOther: true,
      options: [
        { value: 'PO-1001', label: 'PO-1001', description: 'open · 1,250.00 EUR' },
        { value: 'PO-1002', label: 'PO-1002', description: 'overdue · 89.90 EUR' },
        { value: 'PO-1003', label: 'PO-1003', description: 'open · 12,400.00 EUR' },
      ] };
    conv.components.set(`${turnId}/choice-1`, { turnId, componentId: 'choice-1', componentType: 'choice', payload,
      answer: null, shownAt: new Date().toISOString(), answeredAt: null });
    send({ type: 'ui.component', componentType: 'choice', componentId: 'choice-1', payload: JSON.stringify(payload) });
    answer = 'ACME has three open orders. **Which one** should I look at?';
    await streamText(res, send, answer);
  } else if (msg.includes('everything') || msg.includes('show')) {
    send({ type: 'tool.call', callId: 'tool-1', tool: 'find_orders', argsPreview: '{"customer":"ACME","status":"open"}' });
    await sleep(600);
    send({ type: 'tool.result', callId: 'tool-1', status: 'ok', summary: '3 Orders returned.' });
    send({ type: 'tool.call', callId: 'tool-2', tool: 'get_credit_limit', argsPreview: '{"customer":"ACME"}' });
    await sleep(400);
    send({ type: 'tool.result', callId: 'tool-2', status: 'ok', summary: '1 Customer returned.' });
    answer = SHOWCASE;
    await streamText(res, send, answer, 24, 18);
    send({ type: 'ui.component', componentType: 'structured-response', payload: JSON.stringify(DISPLAY) });
  } else {
    answer = 'I can help with **orders**, **invoices** and **customers**. Try *"show me everything"* or '
      + '*"which order is late?"*.';
    await streamText(res, send, answer);
  }
  conv.messages.push({ role: 'ASSISTANT', content: answer, turnId });
  send({ type: 'usage', inputTokens: 812, outputTokens: 240, costMicros: 0, model: 'mock' });
  send({ type: 'turn.end', finishReason: 'stop' });
  res.end();
}

function json(res, status, body) {
  res.writeHead(status, { 'Content-Type': status >= 400 ? 'application/problem+json' : 'application/json' });
  res.end(body === undefined ? '' : JSON.stringify(body));
}

async function readBody(req) {
  let raw = '';
  for await (const chunk of req) raw += chunk;
  return raw ? JSON.parse(raw) : {};
}

createServer(async (req, res) => {
  try {
    const url = new URL(req.url, 'http://localhost');
    const p = url.pathname;
    let m;
    if (req.method === 'GET' && (p === '/' || p === '/index.html')) {
      res.writeHead(200, { 'Content-Type': 'text/html' });
      res.end(await readFile(join(here, 'index.html')));
    } else if (req.method === 'GET' && p.startsWith('/dynamic-ai/ui/')) {
      const file = normalize(join(assets, p));
      if (!file.startsWith(assets)) return json(res, 404, { title: 'Not found' });
      res.writeHead(200, { 'Content-Type': TYPES[extname(file)] || 'application/octet-stream' });
      res.end(await readFile(file));
    } else if (req.method === 'GET' && /^\/dynamic-ai\/api\/agents\/[^/]+\/chat\/config$/.test(p)) {
      json(res, 200, { agent: 'order-helper', displayName: 'Order Assistant', protocol: 'dai-stream/1',
        ui: { steps: true, feedback: true, copy: true, choices: true }, maxMessageChars: 4000, persistentState: true });
    } else if (req.method === 'POST' && /^\/dynamic-ai\/api\/agents\/[^/]+\/chat\/stream$/.test(p)) {
      await handleStream(req, res, await readBody(req));
    } else if (req.method === 'GET' && (m = /^\/dynamic-ai\/api\/agents\/[^/]+\/conversations\/([^/]+)\/ui-state$/.exec(p))) {
      const c = conversation(m[1]);
      json(res, 200, { persistent: true, components: [...c.components.values()],
        feedback: [...c.feedback.entries()].map(([turnId, f]) => ({ turnId, ...f })) });
    } else if (req.method === 'POST'
      && (m = /^\/dynamic-ai\/api\/agents\/[^/]+\/conversations\/([^/]+)\/turns\/([^/]+)\/components\/([^/]+)\/answer$/.exec(p))) {
      const body = await readBody(req);
      const comp = conversation(m[1]).components.get(`${m[2]}/${m[3]}`);
      if (!comp) return json(res, 404, { title: 'Unknown component' });
      if (comp.answer) return json(res, 409, { title: 'Already answered' });
      const values = body.values || [];
      const labels = comp.payload.options.filter((o) => values.includes(o.value)).map((o) => o.label);
      if (labels.length !== values.length || (!values.length && !body.other)) {
        return json(res, 400, { title: 'Invalid answer', detail: 'unknown option' });
      }
      comp.answer = { values, labels, ...(body.other ? { other: body.other } : {}) };
      comp.answeredAt = new Date().toISOString();
      json(res, 200, { turnId: m[2], componentId: m[3], answer: comp.answer,
        message: `My answer to "${comp.payload.question}": ${[...labels, body.other].filter(Boolean).join(', ')}` });
    } else if ((m = /^\/dynamic-ai\/api\/agents\/[^/]+\/conversations\/([^/]+)\/turns\/([^/]+)\/feedback$/.exec(p))) {
      const c = conversation(m[1]);
      if (req.method === 'DELETE') c.feedback.delete(m[2]);
      else {
        const body = await readBody(req);
        if (!['up', 'down'].includes(body.rating)) return json(res, 400, { title: 'Invalid rating' });
        c.feedback.set(m[2], { rating: body.rating, reason: body.reason || null, updatedAt: new Date().toISOString() });
      }
      res.writeHead(204).end();
    } else if (req.method === 'GET' && (m = /^\/dynamic-ai\/api\/conversations\/([^/]+)\/messages$/.exec(p))) {
      if (!conversations.has(m[1])) return json(res, 404, { title: 'Conversation not found' });
      json(res, 200, conversation(m[1]).messages.map((x, i) => ({ seq: i, redacted: false, ...x })));
    } else {
      json(res, 404, { title: 'Not found' });
    }
  } catch (e) {
    console.error(e);
    if (!res.headersSent) json(res, 500, { title: 'Mock server error' });
    else res.end();
  }
}).listen(port, () => console.log(`saimcp-chat demo on http://localhost:${port}/`));
