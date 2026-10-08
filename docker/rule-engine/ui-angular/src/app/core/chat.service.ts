import { Injectable, inject } from '@angular/core';
import { ApiError } from './api.service';
import { SessionService } from './session.service';
import { SseParser } from './sse';

/** What a turn tells the panel while it runs. */
export type ChatEvent =
  | { type: 'start'; conversationId: string; turnId: string }
  | { type: 'text'; text: string }
  | { type: 'tool-call'; callId: string; tool: string }
  | { type: 'tool-result'; callId: string; status: string }
  | { type: 'end' }
  | { type: 'error'; code: string; title: string };

/**
 * Talks to the library's agent chat endpoint: `POST /dynamic-ai/api/agents/{slug}/chat/stream` with the user's own access
 * token. The browser's EventSource cannot send a POST body or a bearer header, so the stream is read with fetch and parsed
 * here ({@link SseParser}). The server replies with typed events; the panel only needs the ones mapped below.
 */
@Injectable({ providedIn: 'root' })
export class ChatService {
  private readonly session = inject(SessionService);

  /**
   * Sends one user message and yields the turn's events as they arrive.
   *
   * @param slug           the agent to talk to (from {@code GET /api/v1/assistant})
   * @param message        what the user typed
   * @param conversationId continue this conversation, or start a new one when null
   * @param signal         aborts the turn
   */
  async *turn(slug: string, message: string, conversationId: string | null, signal?: AbortSignal): AsyncGenerator<ChatEvent> {
    const token = this.session.token();
    let res: Response;
    try {
      res = await fetch(`/dynamic-ai/api/agents/${encodeURIComponent(slug)}/chat/stream`, {
        method: 'POST',
        signal,
        headers: {
          'Content-Type': 'application/json',
          Accept: 'text/event-stream',
          ...(token ? { Authorization: `Bearer ${token}` } : {}),
        },
        body: JSON.stringify({ message, conversationId: conversationId ?? undefined }),
      });
    } catch (e) {
      if (signal?.aborted) {
        return;
      }
      throw new ApiError(0, 'network', 'the assistant cannot be reached');
    }
    if (res.status === 401) {
      this.session.end();
    }
    if (!res.ok || !res.body) {
      throw await refusal(res);
    }
    const reader = res.body.pipeThrough(new TextDecoderStream()).getReader();
    const parser = new SseParser();
    try {
      for (;;) {
        const { value, done } = await reader.read();
        if (done) {
          return;
        }
        for (const raw of parser.feed(value)) {
          const e = map(raw.event, raw.data);
          if (e) {
            yield e;
          }
        }
      }
    } catch (e) {
      if (signal?.aborted) {
        return;
      }
      throw new ApiError(0, 'stream', 'the answer was interrupted');
    } finally {
      reader.releaseLock();
    }
  }
}

async function refusal(res: Response): Promise<ApiError> {
  let code = 'http_' + res.status;
  let message = res.statusText || 'request failed';
  try {
    const body = (await res.json()) as { code?: string; detail?: string; title?: string };
    code = body.code ?? code;
    message = body.detail ?? body.title ?? message;
  } catch {
    // not a problem document
  }
  return new ApiError(res.status, code, message);
}

function map(event: string, data: string): ChatEvent | null {
  let d: Record<string, unknown>;
  try {
    d = JSON.parse(data) as Record<string, unknown>;
  } catch {
    return null;
  }
  switch (event) {
    case 'turn.start':
      return { type: 'start', conversationId: String(d['conversationId'] ?? ''), turnId: String(d['turnId'] ?? '') };
    case 'text.delta':
      return { type: 'text', text: String(d['text'] ?? '') };
    case 'tool.call':
      return { type: 'tool-call', callId: String(d['callId'] ?? ''), tool: String(d['tool'] ?? '') };
    case 'tool.result':
      return { type: 'tool-result', callId: String(d['callId'] ?? ''), status: String(d['status'] ?? '') };
    case 'turn.end':
      return { type: 'end' };
    case 'error':
      return { type: 'error', code: String(d['code'] ?? 'error'), title: String(d['title'] ?? 'The assistant failed.') };
    default:
      return null; // usage, ui.component, proposal.* : not shown in this panel
  }
}
