/** One Server-Sent Event: its `event:` name (default "message"), joined `data:` lines and `id:`. */
export interface SseEvent {
  event: string;
  data: string;
  id: string | null;
}

/**
 * Incremental parser of a `text/event-stream` body. Feed it decoded text chunks as they arrive (a chunk may end in the
 * middle of a line or an event); it returns the events completed by that chunk. Comment lines (`: keep-alive`) are ignored.
 */
export class SseParser {
  private buffer = '';
  private event = 'message';
  private data: string[] = [];
  private id: string | null = null;

  feed(chunk: string): SseEvent[] {
    this.buffer += chunk;
    const out: SseEvent[] = [];
    let at: number;
    // lines end with \n, \r\n or \r; a trailing lone \r may be the first half of \r\n, so keep it for the next chunk
    while ((at = this.nextBreak()) >= 0) {
      const line = this.buffer.slice(0, at);
      this.buffer = this.buffer.slice(this.buffer[at] === '\r' && this.buffer[at + 1] === '\n' ? at + 2 : at + 1);
      const done = this.line(line);
      if (done) {
        out.push(done);
      }
    }
    return out;
  }

  private nextBreak(): number {
    for (let i = 0; i < this.buffer.length; i++) {
      const c = this.buffer[i];
      if (c === '\n') {
        return i;
      }
      if (c === '\r') {
        return i + 1 < this.buffer.length ? i : -1;
      }
    }
    return -1;
  }

  private line(line: string): SseEvent | null {
    if (line === '') {
      if (this.data.length === 0) {
        this.event = 'message';
        return null;
      }
      const e: SseEvent = { event: this.event, data: this.data.join('\n'), id: this.id };
      this.event = 'message';
      this.data = [];
      return e;
    }
    if (line.startsWith(':')) {
      return null;
    }
    const colon = line.indexOf(':');
    const field = colon < 0 ? line : line.slice(0, colon);
    let value = colon < 0 ? '' : line.slice(colon + 1);
    if (value.startsWith(' ')) {
      value = value.slice(1);
    }
    if (field === 'event') {
      this.event = value;
    } else if (field === 'data') {
      this.data.push(value);
    } else if (field === 'id') {
      this.id = value;
    }
    return null;
  }
}
