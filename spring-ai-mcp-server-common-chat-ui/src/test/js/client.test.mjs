import { test } from 'node:test';
import assert from 'node:assert/strict';
import { ChatApi, ChatApiError, SseParser } from '../../main/resources/META-INF/resources/dynamic-ai/ui/chat/client.js';

test('SSE parser: split chunks, CRLF, comments, multi-line data, ids', () => {
  const p = new SseParser();
  const events = [
    ...p.push(': keep-alive\r\nid: t:0\r\nevent: turn.start\r\ndata: {"type":"turn.st'),
    ...p.push('art"}\r\n\r\nevent: text.delta\ndata: {"a":\ndata: 1}\n\n'),
    ...p.push('data: last'),
    ...p.end(),
  ];
  assert.deepEqual(events, [
    { event: 'turn.start', data: '{"type":"turn.start"}', id: 't:0' },
    { event: 'text.delta', data: '{"a":\n1}', id: 't:0' },
    { event: 'message', data: 'last', id: 't:0' },
  ]);
});

function sseResponse(chunks, status = 200) {
  const enc = new TextEncoder();
  const body = new ReadableStream({
    start(c) {
      chunks.forEach((x) => c.enqueue(enc.encode(x)));
      c.close();
    },
  });
  return new Response(body, { status, headers: { 'Content-Type': 'text/event-stream' } });
}

test('stream() posts the turn with auth headers and emits parsed events', async () => {
  const calls = [];
  const api = new ChatApi({
    agent: 'order helper', baseUrl: 'https://host/app/', headers: async () => ({ Authorization: 'Bearer t' }),
    fetch: async (url, init) => {
      calls.push({ url, init });
      return sseResponse(['event: turn.start\ndata: {"type":"turn.start","turnId":"1"}\n\n',
        'event: text.delta\ndata: {"type":"text.delta","seq":1,"text":"Hi"}\n\n']);
    },
  });
  const seen = [];
  await api.stream({ message: 'hello' }, { onEvent: (e) => seen.push(e) });
  assert.equal(calls[0].url, 'https://host/app/dynamic-ai/api/agents/order%20helper/chat/stream');
  assert.equal(calls[0].init.headers.Authorization, 'Bearer t');
  assert.equal(calls[0].init.headers.Accept, 'text/event-stream');
  assert.deepEqual(JSON.parse(calls[0].init.body), { message: 'hello' });
  assert.deepEqual(seen.map((e) => e.type), ['turn.start', 'text.delta']);
  assert.equal(seen[1].text, 'Hi');
});

test('errors carry the problem body; feedback with null deletes', async () => {
  const methods = [];
  const api = new ChatApi({
    agent: 'a',
    fetch: async (url, init) => {
      methods.push(init.method + ' ' + url);
      if (url.endsWith('/answer')) {
        return new Response(JSON.stringify({ title: 'Already answered', code: 'conflict' }), { status: 409 });
      }
      return new Response(null, { status: 204 });
    },
  });
  await assert.rejects(api.answer('c', 't', 'choice-1', { values: ['x'] }), (e) => {
    assert.ok(e instanceof ChatApiError);
    assert.equal(e.status, 409);
    assert.equal(e.problem.title, 'Already answered');
    return true;
  });
  assert.equal(await api.feedback('c', 't', null), null);
  await api.feedback('c', 't', { rating: 'up' });
  assert.deepEqual(methods.slice(1), ['DELETE /dynamic-ai/api/agents/a/conversations/c/turns/t/feedback',
    'PUT /dynamic-ai/api/agents/a/conversations/c/turns/t/feedback']);
});
