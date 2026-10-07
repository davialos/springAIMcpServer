import { describe, expect, it } from 'vitest';
import { SseParser } from './sse';

describe('SseParser', () => {
  it('parses events with id, event name and data', () => {
    const p = new SseParser();
    const out = p.feed('id:t:1\nevent:text.delta\ndata:{"text":"hi"}\n\n');
    expect(out).toEqual([{ event: 'text.delta', data: '{"text":"hi"}', id: 't:1' }]);
  });

  it('waits for the rest when a chunk ends inside a line or an event', () => {
    const p = new SseParser();
    expect(p.feed('event:turn.st')).toEqual([]);
    expect(p.feed('art\ndata:{"a":')).toEqual([]);
    expect(p.feed('1}\n')).toEqual([]);
    expect(p.feed('\n')).toEqual([{ event: 'turn.start', data: '{"a":1}', id: null }]);
  });

  it('handles CRLF, a CR split across chunks, comments and multi-line data', () => {
    const p = new SseParser();
    expect(p.feed(': keep-alive\r\n')).toEqual([]);
    expect(p.feed('data: one\r')).toEqual([]);
    expect(p.feed('\ndata: two\r\n\r\n')).toEqual([{ event: 'message', data: 'one\ntwo', id: null }]);
  });

  it('returns several events from one chunk and ignores an event without data', () => {
    const p = new SseParser();
    const out = p.feed('event:a\ndata:1\n\nevent:b\n\nevent:c\ndata:3\n\n');
    expect(out.map((e) => e.event + ':' + e.data)).toEqual(['a:1', 'c:3']);
  });
});
