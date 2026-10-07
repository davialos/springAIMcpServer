import { TestBed } from '@angular/core/testing';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { ChatService, type ChatEvent } from './chat.service';
import { SessionService } from './session.service';

function stream(chunks: string[], status = 200): Response {
  const enc = new TextEncoder();
  const body = new ReadableStream<Uint8Array>({
    start(c) {
      chunks.forEach((x) => c.enqueue(enc.encode(x)));
      c.close();
    },
  });
  return new Response(body, { status, headers: { 'Content-Type': 'text/event-stream' } });
}

async function collect(it: AsyncGenerator<ChatEvent>): Promise<ChatEvent[]> {
  const out: ChatEvent[] = [];
  for await (const e of it) out.push(e);
  return out;
}

describe('ChatService', () => {
  let chat: ChatService;
  const fetchMock = vi.fn();

  beforeEach(() => {
    sessionStorage.clear();
    vi.stubGlobal('fetch', fetchMock);
    fetchMock.mockReset();
    TestBed.resetTestingModule();
    TestBed.inject(SessionService).start({
      session: { userId: 'u', username: 'a', displayName: 'A', role: 'ROLE_USER', tenantId: 't', tenantName: 'T', organizationId: '', organizationName: '' },
      accessToken: 'tok',
      expiresAtEpochSeconds: Math.floor(Date.now() / 1000) + 3600,
    });
    chat = TestBed.inject(ChatService);
  });

  afterEach(() => vi.unstubAllGlobals());

  it('posts the message with the bearer token and maps the streamed events, whatever the chunking', async () => {
    const turn = [
      'id:x:0\nevent:turn.start\ndata:{"conversationId":"c1","turnId":"t1","type":"turn.start"}\n\n',
      'id:x:1\nevent:text.delta\ndata:{"seq":1,"text":"Hel',
      'lo","type":"text.delta"}\n\nid:x:2\nevent:text.delta\ndata:{"seq":2,"text":" there","type":"text.delta"}\n\n',
      'event:usage\ndata:{"type":"usage"}\n\nevent:turn.end\ndata:{"finishReason":"stop"}\n\n',
    ];
    fetchMock.mockResolvedValue(stream(turn));

    const events = await collect(chat.turn('rule-assistant-abc', 'hi', 'c0'));

    expect(events).toEqual([
      { type: 'start', conversationId: 'c1', turnId: 't1' },
      { type: 'text', text: 'Hello' },
      { type: 'text', text: ' there' },
      { type: 'end' },
    ]);
    const [url, init] = fetchMock.mock.calls[0] as [string, RequestInit];
    expect(url).toBe('/dynamic-ai/api/agents/rule-assistant-abc/chat/stream');
    expect((init.headers as Record<string, string>)['Authorization']).toBe('Bearer tok');
    expect(JSON.parse(init.body as string)).toEqual({ message: 'hi', conversationId: 'c0' });
  });

  it('turns a refusal before the stream into an ApiError and signs out on 401', async () => {
    fetchMock.mockResolvedValue(new Response(JSON.stringify({ code: 'access_denied', detail: 'Access denied' }), { status: 403 }));
    await expect(collect(chat.turn('s', 'hi', null))).rejects.toMatchObject({ status: 403, code: 'access_denied' });

    fetchMock.mockResolvedValue(new Response('{}', { status: 401 }));
    await expect(collect(chat.turn('s', 'hi', null))).rejects.toMatchObject({ status: 401 });
    expect(TestBed.inject(SessionService).signedIn()).toBe(false);
  });

  it('reports a server error event and an unreachable server', async () => {
    fetchMock.mockResolvedValue(stream(['event:error\ndata:{"code":"model-timeout","title":"The model did not respond in time."}\n\n']));
    expect(await collect(chat.turn('s', 'hi', null))).toEqual([{ type: 'error', code: 'model-timeout', title: 'The model did not respond in time.' }]);

    fetchMock.mockRejectedValue(new TypeError('failed'));
    await expect(collect(chat.turn('s', 'hi', null))).rejects.toMatchObject({ code: 'network' });
  });
});
