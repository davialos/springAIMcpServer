// Transport for <saimcp-chat>: an incremental Server-Sent Events parser over fetch() (EventSource cannot POST or send
// bearer tokens, LLD-13 §10) and a small client for the agent chat APIs:
//
//   POST {base}/dynamic-ai/api/agents/{slug}/chat/stream                         the turn stream (dai-stream/1)
//   GET  {base}/dynamic-ai/api/agents/{slug}/chat/config                         features, limits
//   GET  {base}/dynamic-ai/api/agents/{slug}/conversations/{id}/ui-state         components, answers, feedback
//   POST {base}/dynamic-ai/api/agents/{slug}/conversations/{id}/turns/{t}/components/{c}/answer
//   PUT|DELETE {base}/dynamic-ai/api/agents/{slug}/conversations/{id}/turns/{t}/feedback
//   GET  {base}/dynamic-ai/api/conversations/{id}/messages                       transcript (when recording is on)

/** Incremental SSE parser (WHATWG event-stream format). Feed text chunks, get complete events back. */
export class SseParser {
  constructor() {
    this.buffer = '';
    this.event = '';
    this.data = [];
    this.id = null;
  }

  /**
   * Adds a chunk of the response body.
   *
   * @param {string} chunk decoded text
   * @returns {{event: string, data: string, id: string|null}[]} events completed by this chunk
   */
  push(chunk) {
    this.buffer += chunk;
    const events = [];
    let nl;
    while ((nl = this.buffer.search(/\r\n|\r|\n/)) >= 0) {
      const line = this.buffer.slice(0, nl);
      const len = this.buffer[nl] === '\r' && this.buffer[nl + 1] === '\n' ? 2 : 1;
      if (this.buffer[nl] === '\r' && nl + 1 === this.buffer.length) {
        break; // a lone \r at the end may be the first half of \r\n
      }
      this.buffer = this.buffer.slice(nl + len);
      this.line(line, events);
    }
    return events;
  }

  /** Flushes a final event that was not followed by a blank line (stream ended). */
  end() {
    const events = [];
    if (this.buffer) {
      this.line(this.buffer, events);
      this.buffer = '';
    }
    this.line('', events);
    return events;
  }

  line(line, events) {
    if (line === '') {
      if (this.data.length) {
        events.push({ event: this.event || 'message', data: this.data.join('\n'), id: this.id });
      }
      this.event = '';
      this.data = [];
      return;
    }
    if (line.startsWith(':')) {
      return; // comment (heartbeat)
    }
    const colon = line.indexOf(':');
    const field = colon < 0 ? line : line.slice(0, colon);
    let value = colon < 0 ? '' : line.slice(colon + 1);
    if (value.startsWith(' ')) value = value.slice(1);
    if (field === 'event') this.event = value;
    else if (field === 'data') this.data.push(value);
    else if (field === 'id') this.id = value;
  }
}

/** An HTTP error with the RFC 9457 problem body when the server sent one. */
export class ChatApiError extends Error {
  constructor(status, problem) {
    super(problem?.detail || problem?.title || `HTTP ${status}`);
    this.name = 'ChatApiError';
    this.status = status;
    this.problem = problem || null;
  }
}

/** Client for one agent's chat APIs. */
export class ChatApi {
  /**
   * @param {{baseUrl?: string, agent: string, credentials?: RequestCredentials,
   *          headers?: () => (Record<string,string>|Promise<Record<string,string>>), fetch?: typeof fetch}} opts
   */
  constructor(opts) {
    if (!opts?.agent) throw new Error('agent is required');
    this.base = String(opts.baseUrl || '').replace(/\/+$/, '');
    this.agent = encodeURIComponent(opts.agent);
    this.credentials = opts.credentials || 'same-origin';
    this.headers = opts.headers || (() => ({}));
    this.fetchImpl = opts.fetch || ((...a) => fetch(...a));
  }

  agentPath(path) {
    return `${this.base}/dynamic-ai/api/agents/${this.agent}${path}`;
  }

  async request(method, url, body, extra = {}) {
    const headers = { Accept: 'application/json', ...(await this.headers()), ...extra.headers };
    if (body !== undefined) headers['Content-Type'] = 'application/json';
    const res = await this.fetchImpl(url, {
      method, headers, credentials: this.credentials, signal: extra.signal,
      body: body === undefined ? undefined : JSON.stringify(body),
    });
    if (!res.ok) {
      let problem = null;
      try {
        problem = await res.json();
      } catch {
        // not JSON
      }
      throw new ChatApiError(res.status, problem);
    }
    if (res.status === 204) return null;
    const text = await res.text();
    return text ? JSON.parse(text) : null;
  }

  /** Features and limits of the agent's chat. */
  config() {
    return this.request('GET', this.agentPath('/chat/config'));
  }

  /** Components, answers and feedback of a conversation. */
  uiState(conversationId) {
    return this.request('GET', this.agentPath(`/conversations/${encodeURIComponent(conversationId)}/ui-state`));
  }

  /** The stored transcript of a conversation (404 when conversation recording is off). */
  messages(conversationId, limit = 200) {
    return this.request('GET', `${this.base}/dynamic-ai/api/conversations/${encodeURIComponent(conversationId)}`
      + `/messages?limit=${limit}`);
  }

  /** Answers a choice; returns {answer, message}. */
  answer(conversationId, turnId, componentId, body) {
    return this.request('POST', this.agentPath(`/conversations/${encodeURIComponent(conversationId)}/turns/`
      + `${encodeURIComponent(turnId)}/components/${encodeURIComponent(componentId)}/answer`), body);
  }

  /** Sets ({rating: 'up'|'down', reason?, comment?}) or, with null, withdraws feedback on an answer. */
  feedback(conversationId, turnId, body) {
    const url = this.agentPath(`/conversations/${encodeURIComponent(conversationId)}/turns/`
      + `${encodeURIComponent(turnId)}/feedback`);
    return body ? this.request('PUT', url, body) : this.request('DELETE', url);
  }

  /**
   * Runs one streamed turn. Calls onEvent with every parsed event ({type, ...data}); resolves when the stream ends.
   *
   * @param {{message: string, conversationId?: string, clientRequestId?: string}} body
   * @param {{signal?: AbortSignal, onEvent: (event: object) => void}} handlers
   */
  async stream(body, { signal, onEvent }) {
    const headers = {
      Accept: 'text/event-stream', 'Content-Type': 'application/json', ...(await this.headers()),
    };
    const res = await this.fetchImpl(this.agentPath('/chat/stream'), {
      method: 'POST', headers, credentials: this.credentials, signal, body: JSON.stringify(body),
    });
    if (!res.ok) {
      let problem = null;
      try {
        problem = await res.json();
      } catch {
        // not JSON
      }
      throw new ChatApiError(res.status, problem);
    }
    const parser = new SseParser();
    const emit = (raw) => {
      let data;
      try {
        data = JSON.parse(raw.data);
      } catch {
        return; // not one of ours
      }
      onEvent({ type: data.type || raw.event, ...data, sseId: raw.id });
    };
    const reader = res.body.getReader();
    const decoder = new TextDecoder();
    for (;;) {
      const { value, done } = await reader.read();
      if (done) break;
      parser.push(decoder.decode(value, { stream: true })).forEach(emit);
    }
    parser.push(decoder.decode());
    parser.end().forEach(emit);
  }
}
